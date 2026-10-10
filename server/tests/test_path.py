"""On its real domain the server sits under a path; it must answer there, at the root, and in either case of letters."""

from .conftest import approved, signed_in

BASE = "/competitions/IQOO"


def test_the_server_answers_under_its_path_and_at_the_root(client):
    # Some web servers pass the path on, some cut it off, and people type it in small letters.
    for prefix in ("", BASE, "/competitions/iqoo"):
        assert client.get(prefix + "/api/health").json()["status"] == "ok"
        page = client.get(prefix + "/dashboard")
        assert page.status_code == 200 and "DeskLess" in page.text
        assert client.get(prefix + "/static/app.js").status_code == 200
        assert client.get(prefix + "/static/app.css").status_code == 200
    signed = client.post(BASE + "/api/admin/login", json={"email": "admin@example.test", "password": "admin-test-password"})
    assert signed.status_code == 200
    # A path that only begins alike is somebody else's.
    assert client.get(BASE + "X/api/health").status_code == 404


def test_the_page_asks_for_everything_beside_itself(client):
    page = client.get(BASE + "/dashboard").text
    # No address in the page starts at the root of the domain, where the rest of the site lives.
    assert 'href="static/app.css"' in page and 'src="static/app.js"' in page
    assert 'href="/' not in page and 'src="/' not in page
    script = client.get(BASE + "/static/app.js").text
    assert 'fetch("/' not in script and 'BASE + "api"' in script


def test_the_bare_address_leads_to_the_dashboard(client):
    for address in ("/", BASE, BASE + "/", "/admin", BASE + "/admin"):
        answer = client.get(address, follow_redirects=False)
        assert answer.status_code == 307 and answer.headers["location"] == BASE + "/dashboard"


def test_emails_say_where_the_dashboard_is(client, mailbox, admin):
    unique_id = approved(client, mailbox, admin)
    assert "Dashboard: https://site.example.test/competitions/IQOO/dashboard" in mailbox.last_to("asha@example.test")[1]
    assert signed_in(client, unique_id)
