"""Report types: those that come with the app, a company's own, and the one its walks start with."""

from .conftest import aadhaar, approved, signed_in


def make(client, headers, **about):
    body = {"title": "Bus depot check", "checks": "buses, bays and the fuel point of a bus depot", "place": "Depot",
            "categories": ["Tyres", "Brakes", " tyres ", "Other", "Fuel point"], "serious": "a fuel leak"} | about
    return client.post("/api/report-types", json=body, headers=headers)


def test_a_company_makes_a_report_type_of_its_own_and_its_phones_are_told(client, mailbox, admin, company):
    seen = client.get("/api/report-types", headers=company.inspector.headers).json()
    assert [one["key"] for one in seen["built_in"]][:3] == ["GENERAL", "SITE", "FACTORY"] and len(seen["built_in"]) == 12
    assert (seen["own"], seen["usual"], seen["may_make"], seen["may_set"]) == ([], "", False, False)
    # A phone is told of none yet, and of no usual type.
    me = client.get("/api/me", headers=company.inspector.headers).json()
    assert me["report_types"] == [] and me["report_type"] == "" and me["unique_id"] == company.inspector.unique_id

    # Those who lead make them: the managing director or a manager, not an inspector, a technician or the administrator.
    for nobody in (company.inspector.headers, company.technical.headers, admin):
        assert make(client, nobody).status_code == 403
    made = make(client, company.manager.headers)
    assert made.status_code == 200, made.text
    kind = made.json()
    assert kind["key"].startswith("OWN-") and len(kind["key"]) == 12
    # The kinds of issue are tidied: no blanks, no doubles, and "Other" is the app's to add.
    assert (kind["title"], kind["place"], kind["categories"], kind["made_by"]) == ("Bus depot check", "Depot", ["Tyres", "Brakes", "Fuel point"], "Ravi Khan")
    assert make(client, company.md.headers).status_code == 409                                  # the same name again
    assert make(client, company.md.headers, title="Site inspection").status_code == 409         # the name of one that comes with the app
    assert make(client, company.md.headers, title="ab").status_code == 422

    # Every phone of the company is told of it with the person's details.
    told = client.get("/api/me", headers=company.technical.headers).json()["report_types"]
    assert [(one["key"], one["title"], one["checks"], one["serious"]) for one in told] == [(kind["key"], "Bus depot check", "buses, bays and the fuel point of a bus depot", "a fuel leak")]

    # The managing director says which type the company's walks start with: one of its own, or one that comes with the app.
    assert client.post("/api/company/report-type", json={"key": kind["key"]}, headers=company.manager.headers).status_code == 403
    assert client.post("/api/company/report-type", json={"key": "NO-SUCH"}, headers=company.md.headers).status_code == 422
    assert client.post("/api/company/report-type", json={"key": kind["key"]}, headers=company.md.headers).json() == {"usual": kind["key"]}
    assert client.get("/api/me", headers=company.inspector.headers).json()["report_type"] == kind["key"]
    assert client.post("/api/company/report-type", json={"key": "HOSPITAL"}, headers=company.md.headers).json() == {"usual": "HOSPITAL"}
    assert client.get("/api/report-types", headers=company.md.headers).json()["usual"] == "HOSPITAL"

    # Another company sees and is told nothing of it.
    other_id = approved(client, mailbox, admin, name="Omar Sheikh", email="omar@example.test", aadhaar=aadhaar(990), organisation="Sheikh Transport")
    other = signed_in(client, other_id)
    assert client.get("/api/report-types", headers=other).json()["own"] == []
    assert client.get("/api/me", headers=other).json()["report_types"] == []
    assert client.delete(f"/api/report-types/{kind['key']}", headers=other).status_code == 404

    # Taking it away is the managing director's; had it been the usual type, the company would have none.
    assert client.delete(f"/api/report-types/{kind['key']}", headers=company.manager.headers).status_code == 403
    client.post("/api/company/report-type", json={"key": kind["key"]}, headers=company.md.headers)
    assert client.delete(f"/api/report-types/{kind['key']}", headers=company.md.headers).json() == {"removed": kind["key"]}
    after = client.get("/api/me", headers=company.inspector.headers).json()
    assert after["report_types"] == [] and after["report_type"] == ""
