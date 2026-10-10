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
    assert 'href="static/app.css?v=' in page and 'src="static/app.js?v=' in page
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


def test_words_travel_compressed_and_the_page_files_are_kept(client, mailbox, admin):
    approved(client, mailbox, admin)
    page = client.get(BASE + "/dashboard")
    assert page.headers["cache-control"] == "no-cache"
    # The page names its script by a mark; asked for by that mark, the browser may keep it for good.
    marked = page.text.split('src="')[1].split('"')[0]
    script = client.get(f"{BASE}/{marked}")
    assert script.status_code == 200 and "immutable" in script.headers["cache-control"]
    assert script.headers["content-encoding"] == "gzip" and "BASE" in script.text
    assert client.get(BASE + "/static/app.js").headers["cache-control"] == "no-cache"
    assert client.get(BASE + "/static/app.js?v=an-old-mark").headers["cache-control"] == "no-cache"
    # A long answer of the API is compressed for whoever takes it, and plain for whoever does not.
    people = client.get("/api/admin/accounts", params={"status": "approved"}, headers=admin)
    assert people.headers.get("content-encoding") == "gzip" or len(people.content) < 600
    plain = client.get(BASE + "/static/app.js", headers={"Accept-Encoding": "identity"})
    assert "content-encoding" not in plain.headers and int(plain.headers["content-length"]) > 10 * int(script.headers["content-length"]) // 4
    # The administrator's overview brings every company and the server's health in the one answer.
    overview = client.get("/api/dashboard", headers=admin).json()
    assert overview["companies"][0]["name"] == "Green Valley Builders" and overview["system"]["database"]["ok"] is True
