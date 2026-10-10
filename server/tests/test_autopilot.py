"""The assistant's own rounds, and what the numbers say."""

import time
from datetime import date, timedelta

from app import autopilot
from app.db import SessionLocal
from app.models import Issue, Message, now

from .conftest import issue, photo, send_report


def ago(minutes: float) -> int:
    """A moment that many minutes back, as a phone writes it."""
    return int((time.time() - minutes * 60) * 1000)


def the_issue(client, headers, title: str = "Cracked beam") -> dict:
    return next(row for row in client.get("/api/issues", headers=headers).json() if row["title"] == title)


def rounds(mailbox=None) -> int:
    with SessionLocal() as db:
        return autopilot.run_once(db, mailbox)


def inbox(client, headers) -> list[tuple[str, str, str]]:
    return [(row["from"], row["from_role"], row["title"]) for row in client.get("/api/inbox", headers=headers).json()["for_me"]]


def test_an_urgent_issue_nobody_takes_is_brought_to_the_manager_then_to_the_director(client, mailbox, admin, company):
    send_report(client, company.inspector.headers, issues=[
        issue("a1", title="Exposed live wire", severity="CRITICAL", captured_at=ago(40)),
        issue("a2", title="Loose tile", severity="LOW", captured_at=ago(40)),
        issue("a3", title="Gas smell", severity="CRITICAL", captured_at=ago(5)),
    ])
    # Only what is ranked to act on now, and has waited long enough, is spoken of.
    assert rounds(mailbox) == 1
    assert inbox(client, company.manager.headers) == [("DeskLess assistant", "Acting by itself", "Exposed live wire")]
    subject, text = mailbox.last_to(company.manager.email)
    assert subject == "DeskLess: The DeskLess assistant sent you an issue at Tower B"
    assert "Ravi, nobody has taken this yet and it is ranked to act on now: Exposed live wire at Tower B, Second floor." in text and "Best placed to take it: Arjun Das." in text
    # Each rule speaks once about an issue.
    assert rounds(mailbox) == 0
    feed = client.get("/api/dashboard", headers=company.md.headers).json()["activity"]
    assert feed[0]["kind"] == "auto" and feed[0]["text"] == "The assistant told Ravi Khan that nobody has taken it"

    # What the assistant has already brought to the manager, the administrator is not told to bring again.
    told = client.get(f"/api/issues/{the_issue(client, admin, 'Exposed live wire')['id']}", headers=admin).json()["brief"]["next"]
    assert told["kind"] == "wait" and told["label"].startswith("DeskLess assistant told Ravi Khan")

    # The manager cannot answer the assistant, and gains no way to the administrator by it. Their next step is the work itself.
    wire = the_issue(client, company.manager.headers, "Exposed live wire")
    seen = client.get(f"/api/issues/{wire['id']}", headers=company.manager.headers).json()
    assert seen["brief"]["next"]["kind"] == "assign" and "admin" not in {one["id"] for one in seen["send_to"]}
    assert [(line["from"], line["from_role"], line["for_me"]) for line in seen["thread"]] == [("DeskLess assistant", "Acting by itself", True)]

    # Left untaken for two hours, it goes one level up as well.
    with SessionLocal() as db:
        for found in db.query(Issue).filter(Issue.title == "Exposed live wire"):
            found.captured_at = now() - timedelta(minutes=130)
        db.commit()
    assert rounds(mailbox) == 1 and rounds(mailbox) == 0
    assert inbox(client, company.md.headers) == [("DeskLess assistant", "Acting by itself", "Exposed live wire")]
    assert "Asha, this has waited 2 h with Ravi Khan and nobody has taken it" in mailbox.last_to(company.md.email)[1]

    # Giving it out settles what waited on the manager.
    client.post(f"/api/issues/{wire['id']}/assign", json={"to": company.technical.id}, headers=company.manager.headers)
    assert inbox(client, company.manager.headers) == []


def test_a_late_job_and_an_unchecked_repair_are_chased(client, mailbox, company):
    send_report(client, company.inspector.headers, issues=[issue("a1"), issue("a2", title="Loose cable"), issue("a3", title="Broken tap")])
    lead = company.manager.headers
    beam, cable, tap = (the_issue(client, lead, title) for title in ("Cracked beam", "Loose cable", "Broken tap"))
    day = lambda back: (date.today() - timedelta(days=back)).isoformat()  # noqa: E731
    client.post(f"/api/issues/{beam['id']}/assign", json={"to": company.technical.id, "due_on": day(1)}, headers=lead)
    client.post(f"/api/issues/{cable['id']}/assign", json={"to": company.technical.id, "due_on": day(4)}, headers=lead)
    client.post(f"/api/issues/{tap['id']}/assign", json={"to": company.technical.id}, headers=lead)
    client.post(f"/api/issues/{tap['id']}/fixed", data={"note": "New washer."}, files={"photo": ("fix.jpg", photo(), "image/jpeg")}, headers=company.technical.headers)

    # One day late: the technician is asked. Four days late: the manager is told too. The repair was shown just now.
    assert rounds(mailbox) == 3 and rounds(mailbox) == 0
    assert sorted(title for _, _, title in inbox(client, company.technical.headers)) == ["Cracked beam", "Loose cable"]
    # The manager is not told to ask the technician what the assistant has just asked.
    waits = client.get(f"/api/issues/{beam['id']}", headers=lead).json()["brief"]["next"]
    assert waits["kind"] == "wait" and waits["label"].startswith("DeskLess assistant asked Arjun Das")
    assert inbox(client, lead) == [("DeskLess assistant", "Acting by itself", "Loose cable")]
    with SessionLocal() as db:
        said = sorted((message.rule.split(":")[0], message.text) for message in db.query(Message))
    assert [rule for rule, _ in said] == ["late", "late", "overdue"]
    assert any(text.startswith("Arjun, this is 1 day past its day: Cracked beam at Tower B, Second floor.") for _, text in said)
    assert any(text.startswith("Ravi, this has been with Arjun Das for 4 days past its day: Loose cable") for _, text in said)

    # A repair that has waited a day for its check.
    with SessionLocal() as db:
        for found in db.query(Issue).filter(Issue.title == "Broken tap"):
            found.fixed_at = now() - timedelta(hours=30)
        db.commit()
    assert rounds(mailbox) == 1 and rounds(mailbox) == 0
    assert ("DeskLess assistant", "Acting by itself", "Broken tap") in inbox(client, lead)
    # A new day for a late job lets the assistant ask again when that day passes too.
    client.post(f"/api/issues/{beam['id']}/assign", json={"to": company.technical.id, "due_on": day(2)}, headers=lead)
    assert rounds(mailbox) == 1


def test_the_managing_director_can_switch_the_rounds_off(client, mailbox, company):
    send_report(client, company.inspector.headers, issues=[issue("a1", title="Exposed live wire", severity="CRITICAL", captured_at=ago(40))])
    board = client.get("/api/dashboard", headers=company.md.headers).json()["autopilot"]
    assert (board["on"], board["may_switch"], board["sent"]) == (True, True, 0) and len(board["rules"]) == 4
    assert client.get("/api/dashboard", headers=company.manager.headers).json()["autopilot"]["may_switch"] is False
    assert client.post("/api/company/autopilot", json={"on": False}, headers=company.manager.headers).status_code == 403

    assert client.post("/api/company/autopilot", json={"on": False}, headers=company.md.headers).json() == {"on": False}
    assert rounds(mailbox) == 0 and client.get("/api/dashboard", headers=company.md.headers).json()["autopilot"]["on"] is False
    client.post("/api/company/autopilot", json={"on": True}, headers=company.md.headers)
    assert rounds(mailbox) == 1
    assert client.get("/api/dashboard", headers=company.md.headers).json()["autopilot"]["sent"] == 1


def test_the_numbers_are_put_into_sentences(client, mailbox, admin, company):
    assert client.get("/api/dashboard", headers=company.md.headers).json()["insights"] == []
    send_report(client, company.inspector.headers, issues=[
        issue("a1", title="Exposed live wire", severity="CRITICAL", category="Electrical"),
        issue("a2", title="Burnt socket", category="Electrical"),
        issue("a3", title="Loose cable", category="Electrical", check="MISMATCH"),
    ])
    send_report(client, company.inspector.headers, walk="1002", place="Depot", issues=[issue("b1", title="Oil spill", severity="LOW", category="Housekeeping")])
    said = {one["text"]: one for one in client.get("/api/dashboard", headers=company.md.headers).json()["insights"]}
    assert "The backlog is growing: 4 found against 0 closed in the last 7 days, 4 more open than a week ago." in said
    assert "4 issues were found today, against a usual 0.0 a day over the fortnight before." in said
    assert said["Tower B carries the most risk: 1 of its 3 open issues to act on now."]["search"] == "Tower B"
    assert said["Electrical issues are on the rise: 3 in the last 7 days, 0 in the 7 before."]["tone"] == "warn"
    assert "1 open issue has a picture the phone’s AI doubts: look before giving it out." in said
    # Three of a kind found on one walk are not something that "keeps coming back".
    assert not any("keeps coming back" in text for text in said)
    # Someone who sees only their own work gets the findings for that work alone.
    assert client.get("/api/dashboard", headers=company.technical.headers).json()["insights"] == []

    # With everything closed, the pile is not growing any more.
    for row in client.get("/api/issues", headers=company.manager.headers).json():
        client.post(f"/api/issues/{row['id']}/close", json={}, headers=company.manager.headers)
    after = [one["text"] for one in client.get("/api/dashboard", headers=company.md.headers).json()["insights"]]
    assert not any("backlog" in text or "risk" in text for text in after)
