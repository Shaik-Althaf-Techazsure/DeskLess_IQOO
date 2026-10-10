"""What the live dashboard shows: who is online, the server's own health, and shutting an account out."""

from .conftest import PASSWORD, join, send_report, signed_in


def names(rows) -> list[str]:
    return sorted(row["name"] for row in rows)


def test_who_is_online_is_who_has_just_used_it_among_those_in_view(client, mailbox, admin, company):
    # The inspector's phone talks to the server; the technician's does not.
    client.get("/api/me", headers=company.inspector.headers)
    manager = client.get("/api/dashboard", headers=company.manager.headers).json()
    assert names(manager["online"]) == ["Meena Iyer", "Ravi Khan"] and manager["tiles"]["online"] == 2
    assert manager["tiles"]["people"] == 3 and manager["people_by_role"]["Technical"] == 1
    # The inspector sees only themselves; the administrator sees everyone who has been there.
    assert names(client.get("/api/dashboard", headers=company.inspector.headers).json()["online"]) == ["Meena Iyer"]
    assert "Meena Iyer" in names(client.get("/api/dashboard", headers=admin).json()["online"])
    row = next(row for row in client.get("/api/people", headers=company.manager.headers).json() if row["name"] == "Meena Iyer")
    assert row["last_seen_at"] and row["may_decide"] is True


def test_the_servers_own_health_is_for_the_administrator(client, mailbox, admin, company):
    send_report(client, company.inspector.headers)
    health = client.get("/api/admin/system", headers=admin).json()
    assert health["database"]["ok"] is True and health["mail"] is False and health["uptime_seconds"] >= 0
    assert health["rows"]["reports"] == 1 and health["rows"]["issues"] == 2 and health["rows"]["companies"] == 1
    assert health["storage"]["reports"] > 0 and health["storage"]["pictures"] > 0 and health["last_report_at"]
    asked = health["requests"]
    assert len(asked["per_minute"]) == 60 and asked["total"] >= 1 and asked["per_minute"][-1] >= 1
    # Asking for the health itself is not counted, or watching the monitor would look like traffic.
    again = client.get("/api/admin/system", headers=admin).json()["requests"]["total"]
    assert client.get("/api/admin/system", headers=admin).json()["requests"]["total"] == again
    assert client.get("/api/admin/system", headers=company.md.headers).status_code == 401


def test_a_suspended_account_is_shut_out_until_it_is_let_back_in(client, mailbox, admin, company):
    inspector, manager = company.inspector, company.manager.headers
    # Those beside or below cannot do it; the manager the inspector reports to can.
    assert client.post(f"/api/people/{inspector.id}/suspend", headers=company.technical.headers).status_code == 404
    done = client.post(f"/api/people/{inspector.id}/suspend", headers=manager).json()
    assert done["status"] == "suspended"
    assert client.post(f"/api/people/{inspector.id}/suspend", headers=manager).status_code == 409
    # The sign-in the phone holds stops working at once, and a fresh sign-in says why.
    assert client.get("/api/me", headers=inspector.headers).status_code == 401
    refused = client.post("/api/login", json={"unique_id": inspector.unique_id, "password": PASSWORD})
    assert refused.status_code == 403 and "suspended" in refused.json()["detail"]
    # A stranger guessing the password learns nothing about the account.
    assert client.post("/api/login", json={"unique_id": inspector.unique_id, "password": "a guess"}).json()["detail"] == "Wrong ID or password."
    assert names(client.get("/api/people", params={"status": "suspended"}, headers=manager).json()) == ["Meena Iyer"]
    assert "Meena Iyer" not in names(client.get("/api/people", headers=manager).json())

    back = client.post(f"/api/people/{inspector.id}/reactivate", headers=manager).json()
    assert back["status"] == "approved" and back["unique_id"] == inspector.unique_id
    assert signed_in(client, inspector.unique_id)
    assert client.post(f"/api/people/{inspector.id}/reactivate", headers=manager).status_code == 409


def test_only_the_level_above_suspends(client, mailbox, admin, company):
    other = join(client, mailbox, company.md.headers, "manager", "Sara Paul")
    # One manager cannot touch another's people, nor their own managing director.
    assert client.post(f"/api/people/{company.inspector.id}/suspend", headers=other.headers).status_code == 404
    md_row = next(row for row in client.get("/api/people", headers=company.md.headers).json() if row["me"])
    assert client.post(f"/api/people/{md_row['id']}/suspend", headers=company.md.headers).status_code == 403
    # The administrator may suspend anyone, a managing director included.
    assert client.post(f"/api/people/{md_row['id']}/suspend", headers=admin).json()["status"] == "suspended"
    assert client.get("/api/dashboard", headers=company.md.headers).status_code == 401
