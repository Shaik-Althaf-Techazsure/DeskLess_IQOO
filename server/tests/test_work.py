"""Reports arriving from phones, the work on their issues, and what each role's dashboard shows."""

from datetime import date, timedelta

from .conftest import PDF, aadhaar, approved, issue, join, photo, send_report, signed_in


def titles(client, headers) -> list[str]:
    return sorted(row["title"] for row in client.get("/api/issues", headers=headers).json())


def the_issue(client, headers, title: str = "Cracked beam") -> dict:
    return next(row for row in client.get("/api/issues", headers=headers).json() if row["title"] == title)


def tiles(client, headers) -> dict:
    return client.get("/api/dashboard", headers=headers).json()["tiles"]


def test_a_phone_sends_a_report_and_those_above_see_it(client, company):
    sent = send_report(client, company.inspector.headers)
    assert sent.status_code == 200 and sent.json()["issues"] == 2 and sent.json()["updated"] is False

    for viewer in (company.inspector, company.manager, company.md):
        reports = client.get("/api/reports", headers=viewer.headers).json()
        assert [(row["place"], row["issues"], row["open_issues"], row["verdict"], row["signed"], row["sent_by"]) for row in reports] == [
            ("Tower B", 2, 2, "GENUINE", True, "Meena Iyer")
        ]
        assert titles(client, viewer.headers) == ["Cracked beam", "Loose cable"]
        pdf = client.get(f"/api/reports/{reports[0]['id']}/pdf", headers=viewer.headers)
        assert pdf.content == PDF and pdf.headers["content-type"] == "application/pdf"
    # The technician has been given nothing yet, so sees nothing.
    assert client.get("/api/reports", headers=company.technical.headers).json() == []
    assert titles(client, company.technical.headers) == []

    beam = the_issue(client, company.manager.headers)
    assert (beam["status"], beam["severity"], beam["site"], beam["location"], beam["reported_by"]) == ("open", "HIGH", "Tower B", "Second floor", "Meena Iyer")
    picture = client.get(f"/api/issues/{beam['id']}/photo", headers=company.manager.headers)
    assert picture.status_code == 200 and picture.content[:2] == b"\xff\xd8"
    assert client.get(f"/api/issues/{beam['id']}/photo").status_code == 401
    assert client.get(f"/api/issues/{beam['id']}/photo", headers=company.technical.headers).status_code == 404


def test_what_is_not_a_report_is_refused(client, company):
    me = company.inspector.headers
    assert client.post("/api/reports", data={"meta": "{}"}, files={"pdf": ("r.pdf", PDF, "application/pdf")}, headers=me).status_code == 422
    assert client.post("/api/reports", data={"meta": "not json"}, files={"pdf": ("r.pdf", PDF, "application/pdf")}, headers=me).status_code == 422
    not_pdf = client.post("/api/reports", data={"meta": '{"walk": "1"}'}, files={"pdf": ("r.pdf", b"hello", "application/pdf")}, headers=me)
    assert not_pdf.json()["detail"] == "That file is not a PDF report."
    assert send_report(client, {}).status_code == 401
    # A picture that is no picture is left out; the report still arrives.
    odd = client.post(
        "/api/reports",
        data={"meta": '{"walk": "7", "issues": [{"id": "x", "title": "Odd", "photo": "x.jpg"}]}'},
        files=[("pdf", ("r.pdf", PDF, "application/pdf")), ("photos", ("x.jpg", b"not a picture", "image/jpeg"))],
        headers=me,
    )
    assert odd.status_code == 200 and the_issue(client, me, "Odd")["has_photo"] is False


def test_an_issue_goes_from_open_to_assigned_to_fixed_to_closed(client, mailbox, company):
    send_report(client, company.inspector.headers)
    manager, technician = company.manager.headers, company.technical.headers
    beam = the_issue(client, manager)

    # The manager gives it to their technician with a day to have it done by.
    people = client.get("/api/technicians", headers=manager).json()
    assert [(row["name"], row["open_work"]) for row in people] == [("Arjun Das", 0)]
    due = (date.today() + timedelta(days=3)).isoformat()
    given = client.post(f"/api/issues/{beam['id']}/assign", json={"to": people[0]["id"], "due_on": due}, headers=manager).json()
    assert (given["status"], given["assigned_to_name"], given["assigned_by"], given["due_on"]) == ("assigned", "Arjun Das", "Ravi Khan", due)
    assert "Cracked beam" in mailbox.last_to(company.technical.email)[1]
    assert client.get("/api/technicians", headers=manager).json()[0]["open_work"] == 1

    # Now the technician sees it, and only it.
    assert titles(client, technician) == ["Cracked beam"]
    assert tiles(client, technician)["my_jobs"] == 1
    # Fixing takes a picture of the repair, and only the one it was given to can do it.
    no_picture = client.post(f"/api/issues/{beam['id']}/fixed", data={"note": "Done"}, headers=technician)
    assert no_picture.status_code == 422
    repair = {"photo": ("fix.jpg", photo(), "image/jpeg")}
    assert client.post(f"/api/issues/{beam['id']}/fixed", data={"note": "Done"}, files=repair, headers=company.inspector.headers).status_code == 403
    done = client.post(f"/api/issues/{beam['id']}/fixed", data={"note": "Epoxy injected and plastered."}, files=repair, headers=technician).json()
    assert (done["status"], done["fix_note"], done["has_fix_photo"]) == ("fixed", "Epoxy injected and plastered.", True)
    assert client.get(f"/api/issues/{beam['id']}/fix-photo", headers=manager).content[:2] == b"\xff\xd8"
    assert client.post(f"/api/issues/{beam['id']}/fixed", data={}, files=repair, headers=technician).status_code == 409

    # The manager is not satisfied and sends it back; then accepts the second repair.
    back = client.post(f"/api/issues/{beam['id']}/reopen", json={"note": "Crack still visible."}, headers=manager).json()
    assert back["status"] == "assigned" and back["assigned_to_name"] == "Arjun Das"
    client.post(f"/api/issues/{beam['id']}/fixed", data={"note": "Redone."}, files=repair, headers=technician)
    closed = client.post(f"/api/issues/{beam['id']}/close", json={}, headers=manager).json()
    assert (closed["status"], closed["closed_by"]) == ("closed", "Ravi Khan")
    assert client.post(f"/api/issues/{beam['id']}/close", headers=manager).status_code == 409

    history = client.get(f"/api/issues/{beam['id']}", headers=company.inspector.headers).json()["history"]
    assert [event["kind"] for event in history] == ["assigned", "fixed", "reopened", "fixed", "closed"]
    assert history[2]["text"] == "Ravi Khan sent it back: Crack still visible."


def test_only_those_who_lead_give_out_work_and_only_to_their_own(client, mailbox, admin, company):
    send_report(client, company.inspector.headers)
    beam = the_issue(client, company.manager.headers)
    for other in (company.inspector.headers, company.technical.headers, admin):
        assert client.get("/api/technicians", headers=other).status_code == 403
        assert client.post(f"/api/issues/{beam['id']}/close", headers=other).status_code in (403, 404)
    # Not to an inspector, and not to a technician of another manager.
    assert client.post(f"/api/issues/{beam['id']}/assign", json={"to": company.inspector.id}, headers=company.manager.headers).status_code == 422
    other = join(client, mailbox, company.md.headers, "manager", "Sara Paul")
    theirs = join(client, mailbox, other.headers, "technical", "Dev Nair")
    assert client.post(f"/api/issues/{beam['id']}/assign", json={"to": theirs.id}, headers=company.manager.headers).status_code == 422
    # The other manager cannot touch an issue from a team that is not theirs.
    assert client.post(f"/api/issues/{beam['id']}/assign", json={"to": theirs.id}, headers=other.headers).status_code == 404
    # The managing director may give it to any technician in the company.
    given = client.post(f"/api/issues/{beam['id']}/assign", json={"to": theirs.id}, headers=company.md.headers)
    assert given.status_code == 200 and given.json()["assigned_to_name"] == "Dev Nair"
    # Now the other manager sees it, because one of theirs has it.
    assert titles(client, other.headers) == ["Cracked beam"]


def test_a_report_built_again_replaces_the_first_and_keeps_the_work_done(client, company):
    send_report(client, company.inspector.headers)
    manager = company.manager.headers
    beam = the_issue(client, manager)
    technician = client.get("/api/technicians", headers=manager).json()[0]["id"]
    client.post(f"/api/issues/{beam['id']}/assign", json={"to": technician}, headers=manager)

    # The inspector reworded one issue, dropped the cable, added a leak, and built the report again.
    again = send_report(client, company.inspector.headers, issues=[issue("a1", title="Cracked beam, level 2"), issue("a3", title="Water leak", severity="MEDIUM")])
    assert again.json() == {"id": again.json()["id"], "issues": 2, "updated": True}
    assert len(client.get("/api/reports", headers=manager).json()) == 1
    assert titles(client, manager) == ["Cracked beam, level 2", "Water leak"]
    kept = the_issue(client, manager, "Cracked beam, level 2")
    assert kept["id"] == beam["id"] and kept["status"] == "assigned"
    # A different walk is a different report.
    send_report(client, company.inspector.headers, walk="1002", place="Tower C", issues=[issue("b1", title="Broken tile", severity="LOW")])
    assert [row["place"] for row in client.get("/api/reports", headers=manager).json()] == ["Tower C", "Tower B"]
    in_report = client.get("/api/issues", params={"report": again.json()["id"]}, headers=manager).json()
    assert [row["title"] for row in in_report] == ["Cracked beam, level 2", "Water leak"]


def test_an_issue_the_inspector_saw_repaired_on_site_arrives_closed(client, company):
    send_report(client, company.inspector.headers, issues=[issue("a1", resolved=True, fix_photo="a1-fix.jpg"), issue("a2", title="Loose cable")])
    beam = the_issue(client, company.manager.headers)
    assert (beam["status"], beam["closed_by"], beam["has_fix_photo"]) == ("closed", "Meena Iyer, on site", True)
    assert tiles(client, company.manager.headers)["closed"] == 1


def test_the_dashboard_counts_what_each_may_see(client, mailbox, admin, company):
    late = (date.today() - timedelta(days=2)).strftime("%d %b %Y")
    send_report(
        client, company.inspector.headers,
        issues=[issue("a1", due=late), issue("a2", title="Loose cable", severity="LOW", category="Electrical"), issue("a3", title="No railing", severity="CRITICAL", category="Safety")],
        review="DOUBTFUL",
    )
    other = join(client, mailbox, company.md.headers, "manager", "Sara Paul")
    nila = join(client, mailbox, other.headers, "inspector", "Nila Bose")
    send_report(client, nila.headers, walk="2001", place="Depot", person="Nila Bose", issues=[issue("d1", title="Oil spill", severity="MEDIUM", category="Housekeeping")])

    md = client.get("/api/dashboard", headers=company.md.headers).json()
    assert md["me"]["role"] == "md" and md["may_direct"] is True
    assert {key: md["tiles"][key] for key in ("reports", "issues", "open", "unsettled", "serious", "overdue", "doubtful_reports", "waiting_people")} == {
        "reports": 2, "issues": 4, "open": 4, "unsettled": 4, "serious": 2, "overdue": 1, "doubtful_reports": 1, "waiting_people": 0,
    }
    assert md["by_severity"] == {"CRITICAL": 1, "HIGH": 1, "MEDIUM": 1, "LOW": 1}
    assert [(row["name"], row["open"]) for row in md["by_site"]] == [("Tower B", 3), ("Depot", 1)]
    assert {row["lead"]: (row["reports"], row["open"], row["overdue"]) for row in md["teams"]} == {"Ravi Khan": (1, 3, 1), "Sara Paul": (1, 1, 0)}
    assert md["trend"][-1] == {"day": md["today"], "reported": 4, "closed": 0} and len(md["trend"]) == 14
    assert any("sent the report for Depot" in event["text"] for event in md["activity"])

    manager = client.get("/api/dashboard", headers=company.manager.headers).json()
    assert manager["tiles"]["issues"] == 3 and manager["tiles"]["reports"] == 1 and manager["teams"] == []
    assert tiles(client, other.headers)["issues"] == 1
    assert tiles(client, nila.headers)["issues"] == 1 and tiles(client, company.technical.headers)["issues"] == 0
    assert client.get("/api/dashboard", headers=company.inspector.headers).json()["may_direct"] is False

    # Another company's managing director sees none of it; the administrator sees all of it but directs none.
    second = signed_in(client, approved(client, mailbox, admin, name="Omar Sheikh", email="omar@example.test", aadhaar=aadhaar(7), organisation="Sheikh Infra"))
    assert tiles(client, second)["issues"] == 0 and client.get("/api/issues", headers=second).json() == []
    beam = the_issue(client, company.md.headers)
    assert client.get(f"/api/issues/{beam['id']}", headers=second).status_code == 404
    everything = client.get("/api/dashboard", headers=admin).json()
    assert everything["me"]["role"] == "admin" and everything["tiles"]["issues"] == 4 and everything["may_direct"] is False
    assert client.get("/api/dashboard").status_code == 401
