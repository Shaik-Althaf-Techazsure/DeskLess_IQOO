"""The five roles: who joins under whom, who approves whom, and who may see whom."""

from .conftest import aadhaar, approved, join, sign_up, signed_in


def waiting(client, headers) -> list[str]:
    return [row["name"] for row in client.get("/api/people", params={"status": "pending_approval"}, headers=headers).json()]


def people(client, headers) -> set[str]:
    return {row["name"] for row in client.get("/api/people", headers=headers).json()}


def test_everyone_below_the_managing_director_joins_with_a_code(client, mailbox, admin):
    md = signed_in(client, approved(client, mailbox, admin))
    code = client.get("/api/me", headers=md).json()["invite_code"]
    newcomer = dict(role="manager", name="Ravi Khan", email="ravi@example.test", aadhaar=aadhaar(2), organisation="")
    assert sign_up(client, **newcomer).json()["detail"].startswith("Enter the code from the person you report to.")
    assert sign_up(client, **newcomer, invite="ZZZZ-9999").json()["detail"] == "That code is not known. Check it with the person who gave it to you."
    # The form can show whose code it is before anything is sent. However the code is typed, it is the same code.
    asked = client.get("/api/signup/invite", params={"code": code.lower().replace("-", " "), "role": "manager"}).json()
    assert asked == {"company": "Green Valley Builders", "lead": "Asha Verma", "lead_role": "Managing Director"}
    assert sign_up(client, **newcomer, invite=code).status_code == 200


def test_a_manager_needs_the_managing_directors_code(client, mailbox, admin, company):
    managers_code = client.get("/api/me", headers=company.manager.headers).json()["invite_code"]
    refused = sign_up(client, role="manager", name="Second Manager", email="second@example.test", aadhaar=aadhaar(9), invite=managers_code)
    assert refused.json()["detail"] == "A manager signs up with the company code from the managing director."
    # Inspectors and technicians have no code to hand out: nobody answers to them.
    assert client.get("/api/me", headers=company.inspector.headers).json()["invite_code"] == ""


def test_the_level_above_approves(client, mailbox, admin, company):
    # A new inspector signs up with the manager's code. It is the manager's to decide.
    code = client.get("/api/me", headers=company.manager.headers).json()["invite_code"]
    ticket = sign_up(client, role="inspector", name="Kiran Rao", email="kiran@example.test", aadhaar=aadhaar(5), invite=code).json()["ticket"]
    client.post("/api/signup/verify", json={"email": "kiran@example.test", "code": mailbox.code_for("kiran@example.test")})
    status = client.get("/api/signup/status", params={"email": "kiran@example.test", "ticket": ticket}).json()
    assert status["approver"] == "Ravi Khan, your manager" and status["company"] == "Green Valley Builders"
    assert mailbox.last_to(company.manager.email)[0] == "DeskLess: Kiran Rao is waiting for approval"
    assert "waits for Ravi Khan, your manager" in mailbox.last_to("kiran@example.test")[1]

    assert waiting(client, company.manager.headers) == ["Kiran Rao"]
    # The managing director and the administrator may decide it as well; those beside or below may not even see it.
    assert waiting(client, company.md.headers) == ["Kiran Rao"]
    assert waiting(client, admin) == ["Kiran Rao"]
    assert waiting(client, company.inspector.headers) == [] and waiting(client, company.technical.headers) == []
    kiran = client.get("/api/people", params={"status": "pending_approval"}, headers=company.manager.headers).json()[0]
    assert client.post(f"/api/people/{kiran['id']}/approve", headers=company.inspector.headers).status_code == 404

    decided = client.post(f"/api/people/{kiran['id']}/approve", headers=company.manager.headers).json()
    assert decided["unique_id"] == "DL-INS-0002" and decided["reports_to"] == ""
    assert signed_in(client, "DL-INS-0002")
    assert client.get("/api/me", headers=signed_in(client, "DL-INS-0002")).json()["reports_to"] == "Ravi Khan"
    # A decision is made once.
    assert client.post(f"/api/people/{kiran['id']}/reject", json={"reason": "late"}, headers=company.manager.headers).status_code == 409


def test_a_manager_cannot_decide_another_managers_people(client, mailbox, admin, company):
    other = join(client, mailbox, company.md.headers, "manager", "Sara Paul")
    code = client.get("/api/me", headers=other.headers).json()["invite_code"]
    sign_up(client, role="technical", name="Dev Nair", email="dev@example.test", aadhaar=aadhaar(6), invite=code)
    client.post("/api/signup/verify", json={"email": "dev@example.test", "code": mailbox.code_for("dev@example.test")})
    dev = client.get("/api/people", params={"status": "pending_approval"}, headers=other.headers).json()[0]
    assert waiting(client, company.manager.headers) == []
    assert client.post(f"/api/people/{dev['id']}/approve", headers=company.manager.headers).status_code == 404
    assert client.get(f"/api/people/{dev['id']}/photo", headers=company.manager.headers).status_code == 404
    rejected = client.post(f"/api/people/{dev['id']}/reject", json={"reason": "Not on my team."}, headers=other.headers).json()
    assert rejected["status"] == "rejected" and rejected["reject_reason"] == "Not on my team."


def test_each_sees_the_people_below_them(client, mailbox, admin, company):
    other = join(client, mailbox, company.md.headers, "manager", "Sara Paul")
    join(client, mailbox, other.headers, "inspector", "Nila Bose")
    everyone = {"Asha Verma", "Ravi Khan", "Meena Iyer", "Arjun Das", "Sara Paul", "Nila Bose"}
    assert people(client, company.md.headers) == everyone
    assert people(client, company.manager.headers) == {"Ravi Khan", "Meena Iyer", "Arjun Das"}
    assert people(client, other.headers) == {"Sara Paul", "Nila Bose"}
    assert people(client, company.inspector.headers) == {"Meena Iyer"}
    # A second company is another world: its managing director sees none of the first, the administrator sees both.
    second = signed_in(client, approved(client, mailbox, admin, name="Omar Sheikh", email="omar@example.test", aadhaar=aadhaar(7), organisation="Sheikh Infra"))
    assert people(client, second) == {"Omar Sheikh"}
    assert people(client, admin) == everyone | {"Omar Sheikh"}
    rows = client.get("/api/people", headers=company.md.headers).json()
    assert {row["name"]: row["team"] for row in rows}["Ravi Khan"] == 2
    assert all(row["aadhaar"].startswith("XXXX XXXX ") for row in rows)

    listed = client.get("/api/admin/companies", headers=admin).json()
    assert {row["name"]: (row["managing_director"], row["people"]) for row in listed} == {
        "Green Valley Builders": ("Asha Verma", 6), "Sheikh Infra": ("Omar Sheikh", 1),
    }
    assert client.get("/api/admin/companies", headers=company.md.headers).status_code == 401


def test_the_app_opens_the_dashboard_with_a_link_that_works_once(client, mailbox, admin, company):
    code = client.post("/api/web-link", headers=company.manager.headers).json()["code"]
    opened = client.post("/api/web-link/open", json={"code": code})
    assert opened.status_code == 200 and opened.json()["profile"]["unique_id"] == company.manager.unique_id
    assert client.get("/api/dashboard", headers={"Authorization": f"Bearer {opened.json()['token']}"}).json()["me"]["name"] == "Ravi Khan"
    assert client.post("/api/web-link/open", json={"code": code}).status_code == 401
    assert client.post("/api/web-link/open", json={"code": "made-up"}).status_code == 401
    assert client.post("/api/web-link").status_code == 401
