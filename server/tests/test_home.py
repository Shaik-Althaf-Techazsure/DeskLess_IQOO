"""The home page, signing up in a browser, and the pictures and film the home page shows."""

import re

from .conftest import form, photo

BASE = "/competitions/IQOO"


def test_the_home_page_leads_to_signing_in_and_signing_up(client):
    page = client.get(BASE + "/").text
    assert 'href="dashboard"' in page and 'href="signup"' in page
    # Like the dashboard, it asks for everything beside itself, so it works under whatever path the server is given.
    assert 'href="/' not in page and 'src="/' not in page
    assert re.search(r'href="static/home\.css\?v=\w+"', page) and re.search(r'src="static/home\.js\?v=\w+"', page)
    # A shared link shows a picture, and whoever fetches that needs the whole address.
    assert 'content="https://site.example.test/competitions/IQOO/static/site/share.jpg' in page
    assert "{{" not in page


def test_everything_the_home_page_names_is_there(client):
    page = client.get(BASE + "/").text
    named = set(re.findall(r'"(static/[\w./-]+\?v=\w+)"', page))
    assert len(named) > 40
    for address in named:
        answer = client.get(f"{BASE}/{address}")
        assert answer.status_code == 200, address
        # Named by its mark, a file may be kept by the browser for good.
        assert "immutable" in answer.headers["cache-control"], address
    kinds = {client.get(f"{BASE}/{address}").headers["content-type"].split(";")[0] for address in named}
    assert {"image/webp", "image/jpeg", "video/mp4", "text/css", "text/javascript"} <= kinds


def test_the_film_is_sent_in_pieces(client):
    whole = client.get(BASE + "/static/site/film.mp4", headers={"Range": "bytes=0-0"})
    size = int(whole.headers["content-range"].split("/")[1])
    piece = client.get("/static/site/film.mp4", headers={"Range": f"bytes={size - 500}-"})
    assert piece.status_code == 206 and len(piece.content) == 500 and piece.headers["content-type"] == "video/mp4"
    # Asked for without its mark, a file is kept for an hour and no longer.
    assert piece.headers["cache-control"] == "public, max-age=3600"


def test_only_what_belongs_to_the_page_is_handed_out(client):
    for address in ("/static/site/nothing.webp", "/static/site/..", "/static/site/home.html", "/static/home.html", "/static/site/%2e%2e%2fapp.js"):
        assert client.get(address).status_code == 404, address


def test_signing_up_in_a_browser(client, mailbox, admin):
    page = client.get(BASE + "/signup")
    assert page.status_code == 200 and "Create your account" in page.text
    assert 'href="/' not in page.text and 'src="/' not in page.text
    script = client.get(BASE + "/" + re.search(r'src="(static/signup\.js\?v=\w+)"', page.text).group(1)).text
    # The page talks to the same doors as the app, beside itself.
    for door in ('"api/signup"', '"api/signup/verify"', '"api/signup/resend"', "`api/signup/status?", "`api/signup/invite?"):
        assert door in script, door
    assert 'fetch("/' not in script
    # And the form it sends is the one the server takes: every field of the test form has an input on the page.
    for field in form():
        assert f'id="{field}"' in page.text or f'name="{field}"' in page.text, field
    made = client.post(BASE + "/api/signup", data=form(), files={"photo": ("me.jpg", photo(), "image/jpeg")}).json()
    assert client.post(BASE + "/api/signup/verify", json={"email": made["email"], "code": mailbox.code_for(made["email"])}).status_code == 200
    waiting = client.get(BASE + "/api/signup/status", params={"email": made["email"], "ticket": made["ticket"]}).json()
    assert waiting["status"] == "pending_approval" and waiting["approver"] == "the DeskLess administrator"


def test_the_sign_in_card_leads_back(client):
    page = client.get(BASE + "/dashboard").text
    assert 'href="./"' in page and 'href="signup"' in page


def test_the_page_says_where_it_helps_and_shows_the_product(client):
    page = client.get(BASE + "/").text
    for part in ('id="why"', 'id="how"', 'id="places"', 'id="product"', 'id="film"', 'id="trust"'):
        assert part in page, part
    for place in ("Apartments", "Colleges", "Hospitals", "offices", "Malls", "Factories", "Warehouses", "Hotels", "Construction", "Pharmacies", "Neighbourhoods"):
        assert place in page, place
    # Whichever deck is in the folder is shown, and introduced as what it is; a canvas has its place
    # and its link in the menu only once its file is there. (A new deck or a finished canvas is put in
    # by adding files, so this test goes by the folder as it is.)
    from app import front
    from app.main import _site

    has = front.held(_site()[0])
    assert ('id="deck"' in page) == has["deck"] and ("concept deck" in page) == has["concept"]
    assert ('id="canvas"' in page) == has["canvas"] and ('href="#canvas"' in page) == has["canvas"]
    assert "<!--if" not in page and "<!--end" not in page


def test_the_parts_that_come_later_show_when_their_files_are_there():
    from app import front
    from app.main import STATIC

    page = (STATIC / "home.html").read_text(encoding="utf-8")
    # Nothing but the film: no deck, no canvas, and nothing left over of their places.
    bare = front.fill(page, {"film.mp4", "film-poster.jpg"})
    assert 'id="film"' in bare and 'id="deck"' not in bare and 'id="canvas"' not in bare
    assert "{{deck" not in bare and "{{canvas" not in bare and "<!--if" not in bare
    assert 'id="film"' not in front.fill(page, {"film.mp4"})
    # A new deck, as pictures in any of the three kinds, with its PDF: the slides in the order of their numbers.
    names = {"deck-10.png", "deck-2.jpg", "deck-01.webp", "deck-01-s.webp", "DeskLess-Pitch-Deck.pdf", "notes.pdf"}
    deck = front.fill(page, names)
    assert 'src="static/site/deck-01.webp" alt="Slide 1 of 3"' in deck and "1 / 3" in deck
    order = [deck.index(f'data-full="static/site/{name}"') for name in ("deck-01.webp", "deck-2.jpg", "deck-10.png")]
    assert order == sorted(order)
    # The small copy is used where there is one, the slide itself where there is none.
    assert 'src="static/site/deck-01-s.webp" alt="Slide 1"' in deck and 'src="static/site/deck-2.jpg" alt="Slide 2"' in deck
    assert 'href="static/site/DeskLess-Pitch-Deck.pdf" download' in deck
    assert "concept deck" not in deck and "The deck we take to the jury" in deck
    assert "Download the PDF" not in front.fill(page, {"deck-01.webp"})
    # The business model canvas, when it is finished: its place on the page, its link in the menu, its PDF.
    canvas = front.fill(page, {"canvas.png", "canvas-2.png", "DeskLess-Business-Model-Canvas.pdf"})
    assert 'id="canvas"' in canvas and 'href="#canvas"' in canvas
    assert 'data-zoom="static/site/canvas.png"' in canvas and 'data-zoom="static/site/canvas-2.png"' in canvas
    assert 'href="static/site/DeskLess-Business-Model-Canvas.pdf" download' in canvas
    assert 'id="deck"' not in canvas and "{{" not in canvas.replace("{{public}}", "")
