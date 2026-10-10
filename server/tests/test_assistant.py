"""The assistant: how it ranks issues, whom it names, the next step it gives each role, and an issue passed from one person to the next."""

from datetime import date, datetime, timedelta, timezone

from app import writer
from app.assistant import rank
from app.db import SessionLocal
from app.models import ASSIGNED, FIXED, OPEN, Brief, Issue

from .conftest import issue, join, photo, send_report

NOON = datetime(2026, 10, 10, 6, 30, tzinfo=timezone.utc)
TODAY = date(2026, 10, 10)


def found(severity: str = "MEDIUM", status: str = OPEN, hours_ago: float = 1, **about) -> Issue:
    return Issue(severity=severity, status=status, title="Loose cable", note="", captured_at=NOON - timedelta(hours=hours_ago), ai_check="MATCH", **about)


def the_issue(client, headers, title: str = "Cracked beam") -> dict:
    return next(row for row in client.get("/api/issues", headers=headers).json() if row["title"] == title)


def detail(client, headers, issue_id: str) -> dict:
    answer = client.get(f"/api/issues/{issue_id}", headers=headers)
    assert answer.status_code == 200, answer.text
    return answer.json()


def test_the_rank_follows_severity_lateness_and_what_the_note_says():
    score = lambda one, **more: rank(one, TODAY, NOON, **more)[0]  # noqa: E731
    critical, high, medium, low = (score(found(severity)) for severity in ("CRITICAL", "HIGH", "MEDIUM", "LOW"))
    assert critical > high > medium > low
    # Anything critical is to be acted on now, however new.
    assert critical >= 70 > high

    late = found("HIGH", ASSIGNED, due_on=TODAY - timedelta(days=2))
    points, why = rank(late, TODAY, NOON)
    assert points >= 70 and why[0] == "2 days past its day"
    assert score(found("MEDIUM", due_on=TODAY)) > medium
    assert score(found("MEDIUM", hours_ago=80)) > score(found("MEDIUM", hours_ago=30)) > medium
    assert score(found("MEDIUM"), sent_back=True) > medium
    assert score(found("MEDIUM"), alike=3) > medium

    wires = found("MEDIUM")
    wires.note = "There are exposed wires next to the water tank."
    points, why = rank(wires, TODAY, NOON)
    assert points > medium and "The note says “exposed”" in why

    # A repair that waits for its check ranks below the same issue still open, and rises while it waits.
    shown = found("HIGH", FIXED, fixed_at=NOON - timedelta(hours=2))
    stale = found("HIGH", FIXED, fixed_at=NOON - timedelta(hours=50))
    assert high > score(shown) and score(stale) > score(shown)
    assert rank(found("CRITICAL", "closed"), TODAY, NOON) == (0, ["Closed"])


def test_the_issues_come_with_their_rank_and_the_dashboard_with_what_needs_each_person(client, mailbox, admin, company):
    send_report(client, company.inspector.headers, issues=[
        issue("a1", title="Cracked beam", severity="HIGH"),
        issue("a2", title="Loose cable", severity="LOW", category="Electrical"),
        issue("a3", title="Exposed live wire", severity="CRITICAL", category="Electrical", note="An exposed live wire by the stairs."),
    ])
    rows = client.get("/api/issues", headers=company.manager.headers).json()
    by_title = {row["title"]: row["priority"] for row in rows}
    assert by_title["Exposed live wire"]["band"] == "now" and by_title["Exposed live wire"]["band_name"] == "Act now"
    assert by_title["Exposed live wire"]["score"] > by_title["Cracked beam"]["score"] > by_title["Loose cable"]["score"]
    assert by_title["Loose cable"]["band"] == "routine"

    board = client.get("/api/dashboard", headers=company.manager.headers).json()
    assert [entry["title"] for entry in board["queue"]] == ["Exposed live wire", "Cracked beam", "Loose cable"]
    first = board["queue"][0]
    assert first["next"]["kind"] == "assign" and first["next"]["to"] == company.technical.id and first["next"]["label"].startswith("Give it to Arjun Das, due ")
    assert "Arjun, please take this up: Exposed live wire at Tower B, Second floor." in first["next"]["text"]
    assert board["tiles"]["act_now"] == 1 and board["tiles"]["needs_me"] == 3 and board["tiles"]["inbox"] == 0
    assert [line["title"] for line in board["triage"]][:1] == ["Exposed live wire"]
    assert board["load"] == [{"id": company.technical.id, "name": "Arjun Das", "open": 0, "late": 0, "done": 0, "on_time": None, "typical_hours": None, "online": False}]
    assert board["ai"] == {"on": False, "set": False, "model": "", "busy": False, "written": 0, "last_ms": 0}

    # The inspector and the technician have nothing to decide about these, so nothing is queued for them.
    assert client.get("/api/dashboard", headers=company.inspector.headers).json()["queue"] == []
    assert client.get("/api/dashboard", headers=company.technical.headers).json()["queue"] == []
    # The administrator hands out no work: what needs them is to bring the pressing ones to the manager.
    queue = client.get("/api/dashboard", headers=admin).json()["queue"]
    assert [(entry["title"], entry["next"]["kind"], entry["next"]["to_name"]) for entry in queue] == [
        ("Exposed live wire", "send", "Ravi Khan"), ("Cracked beam", "send", "Ravi Khan"),
    ]


def test_the_person_named_is_the_one_with_the_record_and_the_room(client, mailbox, company):
    busy = company.technical
    spare = join(client, mailbox, company.manager.headers, "technical", "Kiran Rao")
    send_report(client, company.inspector.headers, issues=[
        issue("e1", title="Burnt socket", category="Electrical"), issue("e2", title="Loose cable", category="Electrical"),
        issue("e3", title="Flickering light", category="Electrical"), issue("s1", title="Cracked beam", category="Structural"),
    ])
    lead = company.manager.headers

    def suggested(title: str) -> list[dict]:
        return detail(client, lead, the_issue(client, lead, title)["id"])["brief"]["suggested"]

    # With nothing to tell them apart, both are free and the list is in order of name. Neither has used
    # DeskLess since signing in, so neither counts as online.
    assert [(one["name"], one["why"]) for one in suggested("Flickering light")] == [("Arjun Das", ["Free now"]), ("Kiran Rao", ["Free now"])]

    # Arjun repairs one electrical issue: he now has the record for the next.
    socket = the_issue(client, lead, "Burnt socket")
    client.post(f"/api/issues/{socket['id']}/assign", json={"to": busy.id}, headers=lead)
    client.post(f"/api/issues/{socket['id']}/fixed", data={"note": "Replaced."}, files={"photo": ("fix.jpg", photo(), "image/jpeg")}, headers=busy.headers)
    first = suggested("Flickering light")[0]
    assert first["name"] == "Arjun Das" and "Has repaired 1 electrical issue" in first["why"] and "Knows Tower B: 1 job there" in first["why"]

    # Then he is given two more: the one with room comes first for a different kind of issue.
    for title in ("Loose cable", "Flickering light"):
        client.post(f"/api/issues/{the_issue(client, lead, title)['id']}/assign", json={"to": busy.id}, headers=lead)
    names = suggested("Cracked beam")
    assert [one["name"] for one in names] == ["Kiran Rao", "Arjun Das"] and "2 jobs in hand" in names[1]["why"]

    # A name spoken in the inspector's note outweighs the rest.
    send_report(client, company.inspector.headers, walk="1002", issues=[issue("n1", title="Broken tap", category="Plumbing", owner="Arjun")])
    assert suggested("Broken tap")[0]["why"][0] == "Named in the inspector’s note"
    # Someone who gives out no work is told of nobody.
    mine = company.inspector.headers
    assert detail(client, mine, the_issue(client, mine, "Broken tap")["id"])["brief"]["suggested"] == []


def test_an_issue_is_passed_from_the_administrator_down_to_the_technician_and_answered(client, mailbox, admin, company):
    send_report(client, company.inspector.headers, issues=[issue("a1", title="Exposed live wire", severity="CRITICAL", category="Electrical")])
    wire = the_issue(client, admin, "Exposed live wire")
    seen = detail(client, admin, wire["id"])
    brief = seen["brief"]
    assert brief["engine"] == "" and brief["writing"] is False
    assert brief["summary"].startswith("Critical electrical issue at Tower B, Second floor: Exposed live wire.")
    assert brief["standing"].endswith("by Meena Iyer; nobody has it yet.")
    assert brief["priority"]["band"] == "now" and brief["priority"]["why"][0] == "Critical severity"
    step = brief["next"]
    assert (step["kind"], step["to"], step["label"]) == ("send", company.manager.id, "Send it to Ravi Khan (Manager)")
    assert "Ravi, this one needs you." in step["text"] and "Best placed to take it: Arjun Das" in step["text"]
    # The administrator reaches anyone in the issue's company, the one who gives it out first.
    assert [one["name"] for one in seen["send_to"]] == ["Ravi Khan", "Asha Verma", "Arjun Das", "Meena Iyer"]

    sent = client.post(f"/api/issues/{wire['id']}/send", json={"to": step["to"], "text": step["text"]}, headers=admin)
    assert sent.status_code == 200, sent.text
    subject, text = mailbox.last_to(company.manager.email)
    assert subject == "DeskLess: Administrator sent you an issue at Tower B" and "Ravi, this one needs you." in text
    # It now waits on the manager, and the administrator is told so instead of being asked again.
    assert detail(client, admin, wire["id"])["brief"]["next"]["kind"] == "wait"
    assert client.get("/api/dashboard", headers=admin).json()["queue"] == []
    waiting = client.get("/api/inbox", headers=admin).json()
    assert waiting["for_me"] == [] and [(row["to"], row["title"]) for row in waiting["by_me"]] == [("Ravi Khan", "Exposed live wire")]

    manager = company.manager.headers
    board = client.get("/api/dashboard", headers=manager).json()
    assert board["tiles"]["inbox"] == 1
    assert [(row["from"], row["from_role"], row["title"], row["band"]) for row in board["inbox"]] == [("Administrator", "Administrator", "Exposed live wire", "now")]
    mine = detail(client, manager, wire["id"])
    assert [(line["from"], line["to"], line["for_me"], line["waiting"]) for line in mine["thread"]] == [("Administrator", "Ravi Khan", True, True)]
    assert "Administrator sent it to Ravi Khan" in mine["history"][-1]["text"]
    # The manager may answer the administrator, who wrote to them.
    assert "admin" in {one["id"] for one in mine["send_to"]}
    step = mine["brief"]["next"]
    assert step["kind"] == "assign" and step["to"] == company.technical.id

    # Giving it out with the drafted words settles what waited on the manager, and now waits on the technician.
    given = client.post(f"/api/issues/{wire['id']}/assign", json={"to": step["to"], "due_on": step["due_on"], "note": step["text"]}, headers=manager)
    assert given.status_code == 200 and given.json()["status"] == "assigned"
    assert "Ravi Khan writes:\nArjun, please take this up: Exposed live wire" in mailbox.last_to(company.technical.email)[1]
    assert client.get("/api/dashboard", headers=manager).json()["tiles"]["inbox"] == 0
    assert client.get("/api/inbox", headers=admin).json()["by_me"] == []

    technician = company.technical.headers
    job = detail(client, technician, wire["id"])
    assert job["brief"]["next"]["kind"] == "fix" and job["brief"]["replies"][0] == "I have seen it and will start on it today."
    assert [(line["from"], line["for_me"], line["waiting"]) for line in job["thread"]] == [("Administrator", False, False), ("Ravi Khan", True, True)]
    # A technician answers the person they answer to, and nobody else.
    assert [one["name"] for one in job["send_to"]] == ["Ravi Khan"]
    assert client.post(f"/api/issues/{wire['id']}/send", json={"to": "admin", "text": "Hello"}, headers=technician).status_code == 422
    assert client.post(f"/api/issues/{wire['id']}/send", json={"to": company.manager.id, "text": "  "}, headers=technician).status_code == 422
    answered = client.post(f"/api/issues/{wire['id']}/send", json={"to": company.manager.id, "text": job["brief"]["replies"][0]}, headers=technician)
    assert answered.status_code == 200
    board = client.get("/api/dashboard", headers=manager).json()
    assert [(row["from"], row["text"]) for row in board["inbox"]] == [("Arjun Das", "I have seen it and will start on it today.")]
    assert client.get("/api/dashboard", headers=technician).json()["tiles"]["inbox"] == 0

    # The repair settles what is left; the check is then the manager's next step.
    client.post(f"/api/issues/{wire['id']}/fixed", data={"note": "Insulated."}, files={"photo": ("fix.jpg", photo(), "image/jpeg")}, headers=technician)
    assert detail(client, manager, wire["id"])["brief"]["next"]["kind"] == "check"
    client.post(f"/api/issues/{wire['id']}/close", json={}, headers=manager)
    assert client.get("/api/dashboard", headers=manager).json()["tiles"]["inbox"] == 0
    assert detail(client, manager, wire["id"])["brief"]["priority"] == {"score": 0, "band": "done", "band_name": "Done", "why": ["Closed"]}


def test_an_issue_sent_to_someone_is_theirs_to_see_and_nobody_elses(client, mailbox, admin, company):
    other = join(client, mailbox, company.md.headers, "manager", "Sunil Mehta")
    outsider = join(client, mailbox, other.headers, "technical", "Dev Nair")
    send_report(client, company.inspector.headers)
    beam = the_issue(client, company.md.headers)
    # The second manager's team has nothing to do with this issue.
    assert client.get(f"/api/issues/{beam['id']}", headers=other.headers).status_code == 404
    assert client.post(f"/api/issues/{beam['id']}/send", json={"to": company.md.unique_id, "text": "Hello"}, headers=other.headers).status_code == 404

    # The managing director sends it to the second manager: now it is in that manager's view, picture and all.
    sent = client.post(f"/api/issues/{beam['id']}/send", json={"to": other.id, "text": "Can your team take this one?"}, headers=company.md.headers)
    assert sent.status_code == 200
    assert client.get(f"/api/issues/{beam['id']}", headers=other.headers).status_code == 200
    assert client.get(f"/api/issues/{beam['id']}/photo", headers=other.headers).status_code == 200
    assert [row["title"] for row in client.get("/api/issues", headers=other.headers).json()] == ["Cracked beam"]
    # Their own technician still does not see it, and can be given it.
    assert client.get(f"/api/issues/{beam['id']}", headers=outsider.headers).status_code == 404
    step = detail(client, other.headers, beam["id"])["brief"]["next"]
    assert step["kind"] == "assign" and step["to"] == outsider.id
    # An inspector cannot send an issue across to another team's manager.
    assert client.post(f"/api/issues/{beam['id']}/send", json={"to": other.id, "text": "Hello"}, headers=company.inspector.headers).status_code == 422
    # A walk told again without this issue keeps it, because people have written about it.
    send_report(client, company.inspector.headers, issues=[issue("a2", title="Loose cable")])
    assert client.get(f"/api/issues/{beam['id']}", headers=company.md.headers).status_code == 200


def test_a_late_job_and_a_waiting_repair_bring_a_reminder(client, mailbox, admin, company):
    send_report(client, company.inspector.headers, issues=[issue("a1")])
    lead = company.manager.headers
    beam = the_issue(client, lead)
    yesterday = (date.today() - timedelta(days=3)).isoformat()
    client.post(f"/api/issues/{beam['id']}/assign", json={"to": company.technical.id, "due_on": yesterday}, headers=lead)
    step = detail(client, lead, beam["id"])["brief"]["next"]
    assert step["kind"] == "send" and step["to"] == company.technical.id and step["label"] == "Ask Arjun Das when it will be done"
    assert "past its day" in step["text"]
    assert detail(client, admin, beam["id"])["brief"]["next"]["label"] == "Ask Ravi Khan why it is late"
    # Asked once, the manager is not told to ask again while the answer is awaited.
    client.post(f"/api/issues/{beam['id']}/send", json={"to": step["to"], "text": step["text"]}, headers=lead)
    assert detail(client, lead, beam["id"])["brief"]["next"]["kind"] == "wait"
    assert detail(client, company.technical.headers, beam["id"])["brief"]["next"]["kind"] == "fix"


def test_the_writing_model_words_an_issue_once_and_again_when_it_changes(client, company, monkeypatch):
    send_report(client, company.inspector.headers, issues=[issue("a1"), issue("a2", title="Exposed live wire", severity="CRITICAL")])
    lead = company.manager.headers
    asked = []

    def model(prompt: str) -> str:
        asked.append(prompt)
        if "wrote these messages" in prompt:
            return '{"talk": "Ravi asked which beam it is; Meena said the one by the stairs."}'
        return 'Here you go: {"summary": "A beam is cracked and may not carry its load."}'

    monkeypatch.setattr(writer, "ask", model)
    monkeypatch.setitem(writer._state, "model", "gemma-3-1b-it")
    with SessionLocal() as db:
        # The most severe issue is written about first.
        assert writer.write_next(db) and "Issue: Exposed live wire" in asked[0] and "Inspector's note: There is a crack" in asked[0]
        assert writer.write_next(db) and "Issue: Cracked beam" in asked[1]
        assert not writer.write_next(db) and len(asked) == 2
    beam = the_issue(client, lead)
    brief = detail(client, lead, beam["id"])["brief"]
    assert brief["engine"] == "gemma-3-1b-it" and brief["summary"] == "A beam is cracked and may not carry its load." and brief["talk"] == ""
    # The words drafted for the technician stay the server's own: the model is not asked how to repair anything.
    assert brief["next"]["text"] == f"Arjun, please take this up: Cracked beam at Tower B, Second floor. It is high severity, due {brief['next']['label'].split('due ')[1]}. Mark it fixed with a photo of the repair."

    # One message needs no summing up. From the second on the model says what they come to, and is shown
    # the messages only: the issue's own summary is not asked for again.
    client.post(f"/api/issues/{beam['id']}/send", json={"to": company.inspector.id, "text": "Which beam exactly?"}, headers=lead)
    with SessionLocal() as db:
        assert not writer.write_next(db)
    client.post(f"/api/issues/{beam['id']}/send", json={"to": company.manager.id, "text": "The one by the stairs."}, headers=company.inspector.headers)
    with SessionLocal() as db:
        assert writer.write_next(db) and len(asked) == 3
        assert "- Ravi Khan (Manager) to Meena Iyer: Which beam exactly?" in asked[2] and "- Meena Iyer (Inspector) to Ravi Khan: The one by the stairs." in asked[2]
        assert "Inspector's note" not in asked[2]
        assert not writer.write_next(db)
    brief = detail(client, lead, beam["id"])["brief"]
    assert brief["talk"] == "Ravi asked which beam it is; Meena said the one by the stairs." and brief["engine"] == "gemma-3-1b-it"
    # A newer message than the model has read: what it said of the earlier ones is no longer shown.
    client.post(f"/api/issues/{beam['id']}/send", json={"to": company.inspector.id, "text": "Thank you."}, headers=lead)
    assert detail(client, lead, beam["id"])["brief"]["talk"] == ""

    # The phone tells the issue differently: the old summary is dropped at once, and written again.
    send_report(client, company.inspector.headers, built=False, issues=[issue("a1", note="The crack has grown to a metre."), issue("a2", title="Exposed live wire", severity="CRITICAL")])
    assert detail(client, lead, beam["id"])["brief"]["summary"].startswith("High structural issue at Tower B")
    with SessionLocal() as db:
        # One turn does both: the summary again, and the messages read up to the last one.
        assert writer.write_next(db) and "Inspector's note: The crack has grown to a metre." in asked[3] and "Thank you." in asked[4]
        assert not writer.write_next(db)

    # An issue with no note and no reading of its picture gives the model nothing to go on: it is not asked.
    send_report(client, company.inspector.headers, walk="1003", issues=[issue("c1", title="", note="", seen="")])
    with SessionLocal() as db:
        assert not writer.write_next(db)
    bare = detail(client, lead, the_issue(client, lead, "Untitled issue")["id"])["brief"]
    assert bare["engine"] == "" and bare["writing"] is False and bare["summary"] == "High structural issue at Tower B, Second floor: Untitled issue."

    # A model that answers nonsense is tried a few times, then left alone; the built-in wording stands.
    send_report(client, company.inspector.headers, walk="1002", issues=[issue("b1", title="Broken tap", category="Plumbing")])
    monkeypatch.setattr(writer, "ask", lambda prompt: "I cannot help with that.")
    with SessionLocal() as db:
        tries = 0
        while writer.write_next(db):
            tries += 1
        assert tries == writer.MAX_TRIES
        assert db.get(Brief, the_issue(client, lead, "Broken tap")["id"]).summary == ""
    tap = detail(client, lead, the_issue(client, lead, "Broken tap")["id"])["brief"]
    assert tap["engine"] == "" and tap["summary"].startswith("High plumbing issue at Tower B")
