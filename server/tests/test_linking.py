"""The live linkage: what moves between whom, as each viewer may see it, and the page that draws it."""

import re
import time

import pytest

from app import autopilot, pulse
from app.db import SessionLocal

from .conftest import PASSWORD, aadhaar, issue, photo, send_report

BASE = "/competitions/IQOO"
PHONE = {"User-Agent": "Dalvik/2.1.0 (Linux; U; Android 16; I2501 Build/BP2A)"}
BROWSER = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130"}


@pytest.fixture(autouse=True)
def fresh():
    pulse.clear()
    yield
    pulse.clear()


def linkage(client, headers, since=0) -> dict:
    answer = client.get("/api/linking", params={"since": since}, headers=headers)
    assert answer.status_code == 200, answer.text
    return answer.json()


def moves(told: dict, *kinds: str) -> list[tuple]:
    """The pulses of these kinds as (kind, from, to, words)."""
    return [(one["kind"], one["from"], one["to"], one["text"]) for one in told["pulses"] if not kinds or one["kind"] in kinds]


def the_issue(client, headers, title: str) -> dict:
    return next(row for row in client.get("/api/issues", headers=headers).json() if row["title"] == title)


def test_a_walk_arriving_is_a_pulse_from_the_phone_and_one_from_the_assistant_to_the_manager(client, mailbox, admin, company):
    inspector, manager, technical = company.inspector, company.manager, company.technical
    send_report(client, inspector.headers | PHONE, built=False, issues=[
        issue("a1", title="Exposed live wire", severity="CRITICAL", category="Electrical", owner="Arjun"),
    ])
    told = linkage(client, admin)
    walked = [one for one in told["pulses"] if one["kind"] == "walk"]
    assert len(walked) == 1
    arrived = walked[0]
    assert (arrived["from"], arrived["to"], arrived["device"], arrived["ai"]) == (f"p:{inspector.id}", "server", "phone", True)
    assert arrived["text"] == "raised “Exposed live wire” at Tower B"
    routed = next(one for one in told["pulses"] if one["kind"] == "route")
    # Ranked by the rules, put on the desk of whoever gives it out, with who is best placed.
    assert (routed["from"], routed["to"], routed["ai"]) == ("assistant", f"p:{manager.id}", True)
    assert routed["text"].startswith("ranked “Exposed live wire” Act now (") and routed["text"].endswith(f"on Ravi Khan's desk to give out, Arjun Das best placed")
    # The pulses come in the order they happened and carry their number and time.
    numbers = [one["n"] for one in told["pulses"]]
    assert numbers == sorted(numbers) and all(re.match(r"\d{4}-\d\d-\d\dT", one["at"]) for one in told["pulses"])
    # A built report is said so, with its count and its signature.
    send_report(client, inspector.headers | PHONE, built=True, signed=True, issues=[issue("a1", title="Exposed live wire", severity="CRITICAL")])
    assert moves(linkage(client, admin), "walk")[-1][3] == "sent the report for Tower B: 1 issue, signed with a fingerprint"


def test_the_work_on_an_issue_moves_from_person_to_person(client, mailbox, admin, company):
    inspector, manager, technical, md = company.inspector, company.manager, company.technical, company.md
    send_report(client, inspector.headers, issues=[issue("a1", title="Cracked beam", severity="HIGH")])
    found = the_issue(client, manager.headers, "Cracked beam")
    pulse.clear()
    md_id = _id_of(client, admin, "Asha Verma")
    assert client.post(f"/api/issues/{found['id']}/send", json={"to": md_id, "text": "Asha, please look at this."}, headers=manager.headers | BROWSER).status_code == 200
    assert client.post(f"/api/issues/{found['id']}/assign", json={"to": technical.id, "due_on": "2026-10-20", "note": "Start with the beam."}, headers=manager.headers | BROWSER).status_code == 200
    assert client.post(f"/api/issues/{found['id']}/fixed", data={"note": "Braced and sealed."}, files={"photo": ("fix.jpg", photo(), "image/jpeg")}, headers=technical.headers | PHONE).status_code == 200
    assert client.post(f"/api/issues/{found['id']}/reopen", json={"note": "The seal is cracked again."}, headers=manager.headers).status_code == 200
    assert client.post(f"/api/issues/{found['id']}/fixed", data={"note": "Sealed again."}, files={"photo": ("fix.jpg", photo(), "image/jpeg")}, headers=technical.headers | PHONE).status_code == 200
    assert client.post(f"/api/issues/{found['id']}/close", json={"note": "Good."}, headers=manager.headers).status_code == 200
    told = moves(linkage(client, admin), "message", "assign", "fixed", "reopened", "closed")
    assert told == [
        ("message", f"p:{manager.id}", f"p:{md_id}", "sent “Cracked beam” to Asha Verma: “Asha, please look at this.”"),
        ("assign", f"p:{manager.id}", f"p:{technical.id}", "gave “Cracked beam” to Arjun Das, due 20 Oct 2026, with a note"),
        ("fixed", f"p:{technical.id}", f"p:{manager.id}", "marked “Cracked beam” fixed, with a photo of the repair; Ravi Khan is to check it"),
        ("reopened", f"p:{manager.id}", f"p:{technical.id}", "sent “Cracked beam” back: The seal is cracked again."),
        ("fixed", f"p:{technical.id}", f"p:{manager.id}", "marked “Cracked beam” fixed, with a photo of the repair; Ravi Khan is to check it"),
        ("closed", f"p:{manager.id}", f"p:{inspector.id}", "checked the repair and closed “Cracked beam”: Good."),
    ]
    # An email going out is drawn to the person it is for, found by the address; here, with no mail
    # server set, it is the one that would have gone.
    from app.mailer import Mailer

    assert Mailer().send(technical.email, "DeskLess: an issue for you at Tower B", "Hello Arjun") is False
    mails = moves(linkage(client, admin), "mail")
    assert mails == [("mail", "mail", f"p:{technical.id}", "would have emailed “DeskLess: an issue for you at Tower B”, but no mail server is set")]
    # The managing director sees it too, the technician being theirs; a stranger's address goes nowhere they can see.
    assert moves(linkage(client, md.headers), "mail") == mails
    Mailer().send("someone@elsewhere.test", "Hello", "x")
    assert len(moves(linkage(client, md.headers), "mail")) == 1 and moves(linkage(client, admin), "mail")[-1][2] == "mail"
    # And every question each person's phone or browser asked is there too, in words, with what asked it.
    client.get("/api/issues", headers=manager.headers | BROWSER)
    client.get("/api/desk", headers=technical.headers | PHONE)
    asked = {(one["from"], one["text"], one["device"]) for one in linkage(client, admin)["pulses"] if one["kind"] == "ask"}
    assert {(f"p:{manager.id}", "read the issues", "browser"), (f"p:{technical.id}", "looked at the desk", "phone")} <= asked


def _id_of(client, headers, name: str) -> str:
    return next(row["id"] for row in client.get("/api/people", headers=headers).json() if row["name"] == name)


def test_each_viewer_sees_what_their_dashboard_shows(client, mailbox, admin, company):
    inspector, manager, technical, md = company.inspector, company.manager, company.technical, company.md
    # A second company, whose doings are nobody else's business.
    other_md = _another_company(client, mailbox, admin)
    send_report(client, inspector.headers, issues=[issue("a1", title="Cracked beam")])
    send_report(client, other_md.headers, issues=[issue("b1", title="Broken lift")], place="Orchid Residency")
    found = the_issue(client, manager.headers, "Cracked beam")
    client.post(f"/api/issues/{found['id']}/assign", json={"to": technical.id}, headers=manager.headers)

    everything = linkage(client, admin)
    assert everything["me"] == {"node": "admin", "name": "Administrator", "role": "admin", "role_name": "Administrator"}
    assert {firm["name"] for firm in everything["companies"]} == {"Green Valley Builders", "Orchid Residency Ltd"}
    assert {person["name"] for person in everything["people"]} >= {"Asha Verma", "Ravi Khan", "Meena Iyer", "Arjun Das", "Priya Nair"}
    assert {one["text"] for one in everything["pulses"]} >= {"sent the report for Tower B: 1 issue, signed with a fingerprint", "sent the report for Orchid Residency: 1 issue, signed with a fingerprint"}
    assert everything["services"]["assistant"]["on"] is True and everything["services"]["writer"]["on"] is False and everything["services"]["mail"]["set"] is False
    assert [kind["key"] for kind in everything["kinds"]][:3] == ["walk", "sent_in", "route"]
    technician = next(person for person in everything["people"] if person["name"] == "Arjun Das")
    assert (technician["open_work"], technician["role"], technician["company_id"] == technician["company_id"]) == (1, "technical", True)
    assert next(person for person in everything["people"] if person["name"] == "Meena Iyer")["online"] is True

    # The managing director: their company, all of it, and nothing of the other.
    mine = linkage(client, md.headers)
    assert [firm["name"] for firm in mine["companies"]] == ["Green Valley Builders"]
    assert {person["name"] for person in mine["people"]} == {"Asha Verma", "Ravi Khan", "Meena Iyer", "Arjun Das"}
    texts = {one["text"] for one in mine["pulses"]}
    assert "sent the report for Tower B: 1 issue, signed with a fingerprint" in texts and "gave “Cracked beam” to Arjun Das" in texts
    assert not any("Orchid Residency" in text or "Broken lift" in text for text in texts)

    # The manager: their team and its work.
    team = linkage(client, manager.headers)
    assert {person["name"] for person in team["people"]} == {"Ravi Khan", "Meena Iyer", "Arjun Das"}
    assert "gave “Cracked beam” to Arjun Das" in {one["text"] for one in team["pulses"]}
    # The technician: only what concerns them.
    own = linkage(client, technical.headers)
    assert [person["name"] for person in own["people"]] == ["Arjun Das"] and own["me"]["node"] == f"p:{technical.id}"
    assert {one["kind"] for one in own["pulses"]} <= {"assign", "ask", "mail", "signin"}
    assert "gave “Cracked beam” to Arjun Das" in {one["text"] for one in own["pulses"]}
    # Nobody sees the other company's managing director reading their own linkage, except the administrator.
    assert all(one["from"] != f"p:{other_md.id}" for one in mine["pulses"])


def _another_company(client, mailbox, admin):
    from types import SimpleNamespace

    from .conftest import approved, signed_in

    unique_id = approved(client, mailbox, admin, name="Priya Nair", email="priya@example.test", aadhaar=aadhaar(77), organisation="Orchid Residency Ltd")
    headers = signed_in(client, unique_id)
    return SimpleNamespace(id=_id_of(client, admin, "Priya Nair"), headers=headers)


def test_what_a_user_of_the_place_sends_is_routed_by_the_assistant(client, mailbox, admin, company):
    md, inspector = company.md, company.inspector
    assert client.post("/api/company/door", json={"open": True, "kind": "office"}, headers=md.headers).status_code == 200
    assert client.post("/api/signup/user", json={"name": "Nisha Rao", "email": "nisha@example.test", "password": PASSWORD}).status_code == 200
    assert client.post("/api/signup/verify", json={"email": "nisha@example.test", "code": mailbox.code_for("nisha@example.test")}).status_code == 200
    from .conftest import signed_in

    user = signed_in(client, "DL-USR-0001")
    place = client.get("/api/places", headers=user).json()["places"][0]["id"]
    pulse.clear()
    sent = client.post("/api/requests", data={"place": place, "kind": "issue", "title": "Water leaking on the stairs", "note": "By the lift.", "severity": "HIGH", "check": "MATCH"}, headers=user | PHONE)
    assert sent.status_code == 200, sent.text
    told = linkage(client, admin)
    user_id = _id_of(client, admin, "Nisha Rao")
    assert moves(told, "sent_in", "route") == [
        ("sent_in", f"p:{user_id}", "server", "sent in an issue to Green Valley Builders: “Water leaking on the stairs”"),
        ("route", "assistant", f"p:{inspector.id}", "chose Meena Iyer (inspector, least waiting) and ranked it Soon (50)"),
    ]
    # The user stands beside the place they wrote to, in the managing director's view too.
    nisha = next(person for person in linkage(client, md.headers)["people"] if person["name"] == "Nisha Rao")
    assert nisha["role"] == "member" and nisha["near"] == place
    # The inspector's answer goes back to the user, and the user's own view shows it.
    answered = client.post(f"/api/issues/{sent.json()['id']}/send", json={"to": user_id, "text": "We are on it."}, headers=inspector.headers)
    assert answered.status_code == 200, answered.text
    own = linkage(client, user)
    assert ("message", f"p:{inspector.id}", f"p:{user_id}", "sent “Water leaking on the stairs” to Nisha Rao: “We are on it.”") in moves(own, "message")
    assert {person["name"] for person in own["people"]} == {"Nisha Rao", "Meena Iyer"}
    assert [firm["name"] for firm in own["companies"]] == ["Green Valley Builders"]


def test_the_assistants_own_rounds_and_a_waiting_phone_are_pulses_too(client, mailbox, admin, company):
    inspector, manager = company.inspector, company.manager
    send_report(client, inspector.headers, built=False, issues=[
        issue("a1", title="Exposed live wire", severity="CRITICAL", captured_at=int((time.time() - 3 * 3600) * 1000)),
    ])
    pulse.clear()
    with SessionLocal() as db:
        assert autopilot.run_once(db, mailbox) >= 1
    rounds = moves(linkage(client, admin), "rounds")
    assert rounds[0][:3] == ("rounds", "rounds", f"p:{manager.id}") and rounds[0][3].startswith("on its rounds, told Ravi Khan that nobody has taken it")
    # A phone waiting on its desk is seen listening, and is seen being told.
    pulse.clear()
    desk = client.get("/api/desk", headers=manager.headers | PHONE).json()
    waited = client.get("/api/desk/wait", params={"mark": "something-else", "seconds": 1}, headers=manager.headers | PHONE).json()
    assert waited["changed"] is True
    assert moves(linkage(client, admin), "listen", "push") == [
        ("listen", f"p:{manager.id}", "server", "listening for the desk to change, up to 1 s"),
        ("push", "server", f"p:{manager.id}", "the desk changed: the phone is told and reads it again"),
    ]
    assert ("ask", f"p:{manager.id}", "server", "looked at the desk") in moves(linkage(client, admin), "ask")
    assert desk["mark"]


def test_the_page_waits_and_answers_the_moment_something_it_may_show_happens(client, mailbox, admin, company):
    inspector, manager = company.inspector, company.manager
    told = linkage(client, admin)
    # Nothing new: the wait runs out, and says how far it looked.
    began = time.monotonic()
    quiet = client.get("/api/linking/wait", params={"since": told["mark"], "seconds": 1}, headers=admin).json()
    assert quiet == {"mark": told["mark"], "pulses": []} and 0.9 <= time.monotonic() - began < 3
    # Something new: answered at once.
    send_report(client, inspector.headers, built=False, issues=[issue("a1", title="Loose cable")])
    began = time.monotonic()
    fresh = client.get("/api/linking/wait", params={"since": told["mark"], "seconds": 20}, headers=admin).json()
    assert time.monotonic() - began < 2
    assert [one["kind"] for one in fresh["pulses"]][:2] == ["walk", "route"] and fresh["mark"] == fresh["pulses"][-1]["n"]
    # The page's own looking leaves no trace: nothing is drawn for a viewer watching.
    assert not any("/api/linking" in one["text"] for one in linkage(client, admin)["pulses"])
    # Pulses that are not this viewer's are skipped over, and the mark moves past them.
    from .conftest import signed_in

    other = linkage(client, manager.headers)
    other_md = _another_company(client, mailbox, admin)
    send_report(client, other_md.headers, built=False, issues=[issue("b1", title="Broken lift")], place="Orchid Residency")
    skipped = client.get("/api/linking/wait", params={"since": other["mark"], "seconds": 1}, headers=manager.headers).json()
    assert skipped["pulses"] == [] and skipped["mark"] > other["mark"]
    assert client.get("/api/linking/wait", params={"since": 0, "seconds": 1}).status_code == 401
    assert signed_in


def test_signing_in_is_a_pulse_from_the_phone_or_browser(client, mailbox, admin, company):
    pulse.clear()
    answer = client.post("/api/login", json={"unique_id": company.inspector.unique_id, "password": PASSWORD}, headers=PHONE)
    assert answer.status_code == 200
    assert client.post("/api/admin/login", json={"email": "admin@example.test", "password": "admin-test-password"}, headers=BROWSER).status_code == 200
    told = linkage(client, admin)
    assert [(one["from"], one["device"]) for one in told["pulses"] if one["kind"] == "signin"] == [(f"p:{company.inspector.id}", "phone"), ("admin", "browser")]
    # The newest are handed over on a first look, so a page that opens late still has something to show.
    assert linkage(client, admin, since=told["mark"])["pulses"] == []


def test_the_linkage_page_is_served_beside_the_dashboard(client):
    page = client.get(BASE + "/linking")
    assert page.status_code == 200 and "Live linkage" in page.text
    assert 'href="/' not in page.text and 'src="/' not in page.text
    assert re.search(r'href="static/linking\.css\?v=\w+"', page.text) and re.search(r'src="static/linking\.js\?v=\w+"', page.text)
    for address in re.findall(r'"(static/linking\.\w+\?v=\w+)"', page.text):
        answer = client.get(f"{BASE}/{address}")
        assert answer.status_code == 200 and "immutable" in answer.headers["cache-control"], address
    # Reached from the dashboard's own address, under the server's path.
    moved = client.get(BASE + "/dashboard/linking", follow_redirects=False)
    assert moved.status_code == 307 and moved.headers["location"] == BASE + "/linking"
    # The dashboard's side bar leads there.
    assert '"linking", "Live linkage"' in client.get(BASE + "/static/app.js").text
