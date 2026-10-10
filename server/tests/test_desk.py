"""A phone's desk: what it is given, the mark that tells one desk from another, and waiting to be told of a change."""

import threading
import time
from datetime import timedelta

from app import assistant, autopilot, changes
from app.db import SessionLocal, engine
from app.models import now
from app.service import today

from .conftest import issue, send_report

BASE = "/competitions/IQOO"
PARTS = ["mark", "me", "may_direct", "queue", "triage", "inbox", "technicians"]
#: What a row of the desk's queue carries beyond the dashboard's.
ADDED = ("location", "note", "due", "send_to")


def ago(minutes: float) -> int:
    """A moment that many minutes back, as a phone writes it."""
    return int((time.time() - minutes * 60) * 1000)


def the_issue(client, headers, title: str = "Cracked beam") -> dict:
    return next(row for row in client.get("/api/issues", headers=headers).json() if row["title"] == title)


def desk(client, headers) -> dict:
    answer = client.get("/api/desk", headers=headers)
    assert answer.status_code == 200, answer.text
    return answer.json()


def wait(client, headers, **asked) -> tuple[dict, float]:
    """Waits on the desk as a phone does. Returns the answer and how many seconds it took to come."""
    began = time.monotonic()
    answer = client.get("/api/desk/wait", params=asked, headers=headers)
    assert answer.status_code == 200, answer.text
    return answer.json(), time.monotonic() - began


def test_a_managers_desk_is_the_dashboards_queue_with_what_a_phone_needs_to_act(client, mailbox, admin, company):
    # The server's own day, not the computer's: the two differ for some hours of every night.
    late = today() - timedelta(days=2)
    send_report(client, company.inspector.headers, issues=[
        issue("a1", title="Cracked beam", severity="HIGH", due=late.strftime("%d %b %Y")),
        issue("a2", title="Loose cable", severity="LOW", category="Electrical", due="As soon as possible"),
        issue("a3", title="Exposed live wire", severity="CRITICAL", category="Electrical", location="Stairwell", note="An exposed live wire by the stairs."),
    ])
    lead = company.manager.headers
    board = client.get("/api/dashboard", headers=lead).json()
    told = desk(client, lead)
    assert list(told) == PARTS and len(told["mark"]) == 16
    assert (told["me"], told["may_direct"], told["triage"], told["inbox"]) == (board["me"], True, board["triage"], board["inbox"])
    # Each row is the dashboard's own, and after it comes what the phone would otherwise have to ask for.
    assert [{key: value for key, value in row.items() if key not in ADDED} for row in told["queue"]] == board["queue"]
    assert [row["title"] for row in told["queue"]] == ["Exposed live wire", "Cracked beam", "Loose cable"]
    rows = {row["title"]: row for row in told["queue"]}
    wire = rows["Exposed live wire"]
    assert (wire["location"], wire["note"], wire["due"]) == ("Stairwell", "An exposed live wire by the stairs.", "")
    assert wire["next"]["kind"] == "assign" and wire["next"]["to"] == company.technical.id
    # The deadline in words: the day when the inspector named one, otherwise what they said.
    assert rows["Cracked beam"]["due"] == late.strftime("%d %b %Y").lstrip("0") and rows["Loose cable"]["due"] == "As soon as possible"
    # Whom it may be sent to is what the issue's own page says.
    assert [(one["name"], one["role_name"]) for one in wire["send_to"]] == [("Asha Verma", "Managing Director"), ("Arjun Das", "Technical"), ("Meena Iyer", "Inspector")]
    assert wire["send_to"] == client.get(f"/api/issues/{wire['id']}", headers=lead).json()["send_to"]
    # Their technicians come with it, so giving an issue out takes no second question.
    assert told["technicians"] == client.get("/api/technicians", headers=lead).json()
    assert told["technicians"] == [{"id": company.technical.id, "name": "Arjun Das", "unique_id": company.technical.unique_id, "open_work": 0}]

    # The administrator has a desk too: the pressing ones to bring to the manager, and no technicians of their own.
    office = desk(client, admin)
    assert list(office) == PARTS and office["me"] == client.get("/api/dashboard", headers=admin).json()["me"]
    assert (office["me"]["role"], office["may_direct"], office["technicians"]) == ("admin", False, [])
    assert [(row["title"], row["next"]["kind"], row["next"]["to_name"]) for row in office["queue"]] == [
        ("Exposed live wire", "send", "Ravi Khan"), ("Cracked beam", "send", "Ravi Khan"),
    ]
    assert office["queue"][0]["send_to"][0]["name"] == "Ravi Khan"


def test_a_technicians_desk_is_empty_until_work_is_given_and_then_has_another_mark(client, mailbox, company):
    send_report(client, company.inspector.headers, issues=[issue("a1")])
    lead, technician = company.manager.headers, company.technical.headers
    empty = desk(client, technician)
    assert (empty["queue"], empty["triage"], empty["inbox"], empty["technicians"], empty["may_direct"]) == ([], [], [], [], False)
    assert empty["me"]["name"] == "Arjun Das"
    # Asked again with nothing done in between, it is the same desk and has the same mark.
    assert desk(client, technician)["mark"] == empty["mark"]
    # Someone who gives out no work is told of no technicians, whatever lies on their desk.
    mine = desk(client, company.inspector.headers)
    assert mine["technicians"] == [] and mine["queue"] == [] and [line["title"] for line in mine["triage"]] == ["Cracked beam"]
    before = desk(client, lead)
    assert [row["title"] for row in before["queue"]] == ["Cracked beam"] and desk(client, lead)["mark"] == before["mark"]

    beam = the_issue(client, lead)
    given = client.post(f"/api/issues/{beam['id']}/assign", json={"to": company.technical.id, "note": "Arjun, please take this up."}, headers=lead)
    assert given.status_code == 200, given.text
    full = desk(client, technician)
    assert full["mark"] != empty["mark"] and desk(client, technician)["mark"] == full["mark"]
    job = full["queue"][0]
    assert (job["title"], job["status"], job["next"]["kind"], job["location"]) == ("Cracked beam", "assigned", "fix", "Second floor")
    assert job["note"] == "There is a crack in the beam on the second floor." and [one["name"] for one in job["send_to"]] == ["Ravi Khan"]
    assert [(row["from"], row["text"]) for row in full["inbox"]] == [("Ravi Khan", "Arjun, please take this up.")]
    # The same step changed the desks of the others it touches: it left the manager's queue, and the
    # inspector's line for it now says who has it.
    after = desk(client, lead)
    assert after["queue"] == [] and after["technicians"][0]["open_work"] == 1 and after["mark"] != before["mark"]
    assert desk(client, company.inspector.headers)["mark"] != mine["mark"]


def test_the_mark_stays_while_only_the_clock_moves(client, company, monkeypatch):
    send_report(client, company.inspector.headers, issues=[issue("a1")])
    lead = company.manager.headers
    first = desk(client, lead)
    # Five hours on and nobody has done anything: the reasons read differently, but it is the same desk.
    later = now() + timedelta(hours=5)
    monkeypatch.setattr(assistant, "now", lambda: later)
    second = desk(client, lead)
    assert "Nobody has taken it for 5 h" in second["queue"][0]["why"] and second["queue"][0]["why"] != first["queue"][0]["why"]
    assert second["mark"] == first["mark"]


def test_waiting_ends_when_the_time_is_up_and_at_once_when_the_desk_is_already_another(client, admin, company):
    send_report(client, company.inspector.headers, issues=[issue("a1")])
    lead = company.manager.headers
    mark = desk(client, lead)["mark"]
    # Nothing happens: the phone is answered when its second is up, and told the desk is as it was.
    answer, took = wait(client, lead, mark=mark, seconds=1)
    assert answer == {"mark": mark, "changed": False} and 0.9 <= took < 10
    # A mark from an earlier desk, or none at all, is answered straight away with the one that holds now.
    for asked in ({"mark": "0123456789abcdef", "seconds": 30}, {"seconds": 30}):
        answer, took = wait(client, lead, **asked)
        assert answer == {"mark": mark, "changed": True} and took < 10
    # The administrator waits the same way.
    answer, took = wait(client, admin, mark="0123456789abcdef")
    assert answer == {"mark": desk(client, admin)["mark"], "changed": True} and took < 10


def test_only_what_is_written_moves_the_count_a_waiting_phone_watches(client, mailbox, company):
    send_report(client, company.inspector.headers, issues=[issue("a1", title="Exposed live wire", severity="CRITICAL", captured_at=ago(40))])
    lead = company.manager.headers
    wire = the_issue(client, lead, "Exposed live wire")
    was = changes.count()
    # A question writes nothing, and neither does a request that is refused.
    desk(client, lead)
    client.get("/api/dashboard", headers=lead)
    assert client.post(f"/api/issues/{wire['id']}/assign", json={"to": company.inspector.id}, headers=lead).status_code == 422
    assert client.post(f"/api/issues/{wire['id']}/close", headers=company.technical.headers).status_code in (403, 404)
    assert changes.count() == was
    # The assistant writes on its rounds with no request at all, and says so itself.
    with SessionLocal() as db:
        assert autopilot.run_once(db, mailbox) == 1
    assert changes.count() == was + 1
    # A request that did its work counts, under the site's own path as well.
    assert client.post(f"{BASE}/api/issues/{wire['id']}/assign", json={"to": company.technical.id}, headers=lead).status_code == 200
    assert changes.count() == was + 2


def test_a_phone_that_waits_is_told_when_work_is_given_to_it(client, company):
    send_report(client, company.inspector.headers, issues=[issue("a1")])
    lead, technician = company.manager.headers, company.technical.headers
    beam = the_issue(client, lead)
    mark = desk(client, technician)["mark"]
    heard = {}

    def phone() -> None:
        heard["answer"], heard["took"] = wait(client, technician, mark=mark, seconds=20)

    waiting = threading.Thread(target=phone, daemon=True)
    waiting.start()
    # Long enough for the phone's question to have arrived and found the desk as it was. While it is
    # kept waiting it holds no connection to the database.
    time.sleep(1.5)
    assert waiting.is_alive() and engine.pool.checkedout() == 0
    given = client.post(f"/api/issues/{beam['id']}/assign", json={"to": company.technical.id}, headers=lead)
    assert given.status_code == 200, given.text
    waiting.join(40)
    assert not waiting.is_alive() and "answer" in heard
    # It was told because of what was written, long before its twenty seconds were up.
    assert heard["answer"]["changed"] is True and heard["answer"]["mark"] != mark and heard["took"] < 15
    assert heard["answer"]["mark"] == desk(client, technician)["mark"]


def test_the_desk_is_only_for_whoever_is_signed_in(client, company):
    forged = {"Authorization": "Bearer not-a-sign-in"}
    usual = client.get("/api/dashboard", headers=forged)
    assert (usual.status_code, usual.json()) == (401, {"detail": "Sign in again."})
    for address in ("/api/desk", "/api/desk/wait"):
        nobody = client.get(address)
        assert (nobody.status_code, nobody.json()) == (401, client.get("/api/dashboard").json())
        refused = client.get(address, headers=forged)
        assert (refused.status_code, refused.json()) == (401, usual.json())
    # An account that is shut out is no longer answered, whether it asks or waits.
    technician = company.technical.headers
    mark = desk(client, technician)["mark"]
    assert client.post(f"/api/people/{company.technical.id}/suspend", headers=company.manager.headers).status_code == 200
    assert client.get("/api/desk", headers=technician).status_code == 401
    assert client.get("/api/desk/wait", params={"mark": mark, "seconds": 1}, headers=technician).status_code == 401
