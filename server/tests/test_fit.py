"""The form on the home page: is DeskLess what this person needs, and the brochure sent to them if they ask."""

import pytest

from app import fit, front, writer
from app.routers import enquiry

BASE = "/competitions/IQOO"
HOME = "https://site.example.test/competitions/IQOO/"
SOCIETY = "We run a 300 flat housing society and complaints get lost in WhatsApp groups. Nobody knows who is fixing the lift."


@pytest.fixture(autouse=True)
def nothing_counted_yet():
    enquiry._counted.clear()


@pytest.fixture
def brochure(tmp_path, monkeypatch):
    """A folder of the home page's files that holds a brochure and a deck, whatever the real one holds today."""
    (tmp_path / "DeskLess-Brochure.pdf").write_bytes(b"%PDF-1.4\n% the brochure\n%%EOF\n")
    (tmp_path / "DeskLess-Pitch-Deck.pdf").write_bytes(b"%PDF-1.4\n% the deck\n%%EOF\n")
    monkeypatch.setattr(front, "SITE", tmp_path)
    return tmp_path


def test_a_need_it_meets_is_told_yes_with_the_reasons():
    told = fit.answer(SOCIETY)
    assert told["verdict"] == "yes" and told["headline"].startswith("Yes.")
    assert told["place"] == {"key": "residential", "name": "an apartment block or housing society", "type": "Apartment inspection"}
    assert {"Reporting a problem by voice and photo", "Giving each issue to a named person and following it"} <= {point["title"] for point in told["does"]}
    assert not told["limits"] and not told["not"] and len(told["start"]) == 3
    # Their complaint about WhatsApp groups is not a wish for WhatsApp messages.
    assert told["engine"] == ""
    factory = fit.answer("Daily safety rounds in our factory. There is no network on the shop floor and supervisors type reports at night.")
    assert factory["verdict"] == "yes" and factory["place"]["type"] == "Factory round"
    assert "Working with no network" in {point["title"] for point in factory["does"]}


def test_what_it_does_not_do_is_said_plainly():
    told = fit.answer("We need payroll and attendance for our housekeeping staff")
    assert told["verdict"] == "no" and told["headline"].startswith("No.") and not told["does"] and not told["start"]
    assert told["not"][0]["title"] == "Attendance, payroll and rosters"
    # A word in passing does not turn a no into a part-yes, and "example" is not an exam.
    assert fit.answer("We want CCTV based automatic detection of garbage on the road for citizens")["verdict"] == "no"
    assert fit.answer("For example, students should report hostel problems")["verdict"] == "yes"
    mixed = fit.answer("inventory and billing for our medical store, and expiry date checking")
    assert mixed["verdict"] == "partly" and mixed["place"]["type"] == "Medication audit"
    assert [point["title"] for point in mixed["does"]] == ["Medication audits that read expiry dates"]
    assert {point["title"] for point in mixed["not"]} == {"Billing and payments", "Stock and purchasing"}


def test_a_limit_is_told_before_anybody_decides():
    told = fit.answer("Hospital nurses should report broken equipment by voice in Hindi, and we want proof it was repaired. We use iPhones.")
    assert told["verdict"] == "yes_but" and told["place"]["type"] == "Hospital round"
    assert [point["title"] for point in told["limits"]] == ["Android only"]
    kannada = fit.answer("Our municipal ward office gets pothole and streetlight complaints from citizens in Kannada")
    assert kannada["verdict"] == "yes_but" and kannada["place"]["key"] == "community"
    assert "still being tested" in kannada["limits"][0]["says"]
    assert fit.answer("Can it send SMS alerts and integrate with our SAP when an issue is overdue?")["verdict"] == "yes_but"


def test_too_little_to_go_on_is_asked_about():
    assert fit.answer("our college")["verdict"] == "likely" and fit.answer("our college")["place"]["key"] == "campus"
    assert fit.answer("hello, what is this exactly?")["verdict"] == "unclear"
    # The kind of place chosen on the form counts where the words do not say it.
    assert fit.answer("Things keep breaking and nobody follows up", "hotel")["place"]["type"] == "Hotel inspection"


def test_the_model_only_words_and_is_checked(monkeypatch):
    monkeypatch.setattr(writer, "reachable", lambda: True)
    monkeypatch.setattr(writer, "status", lambda: {"model": "gemma-3-1b-it"})
    said = {"reply": "Yes, DeskLess fits a housing society: residents report by voice and every issue is given to a named person."}
    monkeypatch.setattr(writer, "ask", lambda prompt, seconds=0: __import__("json").dumps(said))
    told = fit.answer(SOCIETY)
    assert told["summary"] == said["reply"] and told["engine"] == "gemma-3-1b-it" and told["verdict"] == "yes"
    # A promise of something DeskLess does not do, a price or an address is thrown away for the built-in words.
    for wrong in ("Yes, and it also handles payroll and attendance for your whole society staff.",
                  "Yes, it fits and it costs Rs. 500 a month for a society of your size, see below.",
                  "Yes, it fits. Read more at http://example.test/deskless about how it does that.",
                  "Yes."):
        said["reply"] = wrong
        again = fit.answer(SOCIETY)
        assert again["engine"] == "" and again["summary"].startswith("For an apartment block")
    # It is not asked at all when the answer is anything but a plain yes.
    monkeypatch.setattr(writer, "ask", lambda prompt, seconds=0: pytest.fail("the model was asked about a no"))
    assert fit.answer("We need payroll and attendance for our staff")["verdict"] == "no"


def test_asking_and_then_having_the_brochure_sent(client, mailbox, brochure):
    assert client.post(BASE + "/api/fit", json={"text": "hi"}).status_code == 422
    assert client.post(BASE + "/api/fit", json={"text": "x" * 1600}).status_code == 422
    told = client.post(BASE + "/api/fit", json={"text": SOCIETY}).json()
    assert told["verdict"] == "yes" and told["ticket"] and "keys" not in told
    assert told["whatsapp"].startswith("https://wa.me/918978716587?text=") and "pricing" in told["whatsapp"]

    assert client.post(BASE + "/api/fit/brochure", json={"ticket": told["ticket"], "email": "not an address"}).status_code == 422
    assert client.post(BASE + "/api/fit/brochure", json={"ticket": "made-up", "email": "dev@example.test"}).status_code == 400
    sent = client.post(BASE + "/api/fit/brochure", json={"ticket": told["ticket"], "email": " Dev@Example.test ", "name": "Dev <b>Rao</b>"}).json()
    assert sent["sent"] is True and sent["attached"] is True and sent["email"] == "dev@example.test"
    assert sent["site"] == HOME and sent["whatsapp"] == told["whatsapp"]
    assert sent["brochure"] == HOME + "static/site/DeskLess-Brochure.pdf" and sent["deck"] == HOME + "static/site/DeskLess-Pitch-Deck.pdf"

    subject, text = mailbox.last_to("dev@example.test")
    assert subject == "DeskLess for an apartment block or housing society: the brochure you asked for"
    assert "Hello Dev b Rao b," in text and "Yes. DeskLess does this today." in text
    assert "The brochure is attached to this email as a PDF." in text and sent["brochure"] in text
    assert f"See it live: {HOME}" in text and told["whatsapp"] in text
    # The brochure itself travels with the email, under a plain name.
    assert mailbox.files["dev@example.test"] == (("DeskLess-Brochure.pdf", b"%PDF-1.4\n% the brochure\n%%EOF\n"),)
    # Nothing the asker typed is repeated to that address: the form cannot carry somebody's message.
    assert "300 flat" not in text and "lift" not in text
    laid_out = mailbox.laid_out["dev@example.test"]
    assert "Ask for pricing on WhatsApp" in laid_out and "Download the brochure (PDF)" in laid_out and "<b>Rao" not in laid_out
    assert f'href="{sent["brochure"]}"' in laid_out and "attached to this email" in laid_out
    assert told["whatsapp"].replace("&", "&amp;") in laid_out and "300 flat" not in laid_out
    assert mailbox.reply_to["dev@example.test"] == "admin@example.test"
    # The team is told, with the words and the address, and an answer to that email goes to the asker.
    subject, text = mailbox.last_to("admin@example.test")
    assert "dev@example.test" in subject and "300 flat housing society" in text and "Dev b Rao b" in text
    assert mailbox.reply_to["admin@example.test"] == "dev@example.test" and not mailbox.files["admin@example.test"]


def test_without_a_brochure_in_the_folder_the_email_leads_to_the_page(client, mailbox, tmp_path, monkeypatch):
    monkeypatch.setattr(front, "SITE", tmp_path)   # an empty folder: no brochure has been made yet
    told = client.post(BASE + "/api/fit", json={"text": SOCIETY}).json()
    sent = client.post(BASE + "/api/fit/brochure", json={"ticket": told["ticket"], "email": "dev@example.test"}).json()
    assert sent["sent"] is True and sent["attached"] is False and sent["brochure"] == "" and sent["deck"] == "" and sent["site"] == HOME
    text = mailbox.last_to("dev@example.test")[1]
    assert "attached" not in text and "The brochure (PDF" not in text and f"See it live: {HOME}" in text
    assert "See DeskLess live" in mailbox.laid_out["dev@example.test"] and not mailbox.files["dev@example.test"]


def test_a_brochure_too_large_to_be_welcome_is_linked_not_attached(client, mailbox, brochure, monkeypatch):
    monkeypatch.setattr(enquiry, "MAX_ATTACHED", 10)
    told = client.post(BASE + "/api/fit", json={"text": SOCIETY}).json()
    sent = client.post(BASE + "/api/fit/brochure", json={"ticket": told["ticket"], "email": "dev@example.test"}).json()
    assert sent["attached"] is False and sent["brochure"].endswith("DeskLess-Brochure.pdf")
    assert not mailbox.files["dev@example.test"] and "The brochure is one tap away" in mailbox.last_to("dev@example.test")[1]


def test_the_form_cannot_be_used_to_flood_a_mailbox(client, mailbox, monkeypatch):
    told = client.post("/api/fit", json={"text": SOCIETY}).json()
    wanted = {"ticket": told["ticket"], "email": "dev@example.test"}
    assert client.post("/api/fit/brochure", json=wanted).status_code == 200
    # The same mailbox is not written to again within the hour, from anywhere.
    again = client.post("/api/fit/brochure", json=wanted, headers={"X-Real-IP": "203.0.113.9"})
    assert again.status_code == 429 and "a little while ago" in again.json()["detail"]
    assert len([one for one in mailbox.sent if one[0] == "dev@example.test"]) == 1
    # One address may have only so many brochures sent in an hour, to whomever.
    for n in range(enquiry.SENDS - 1):
        assert client.post("/api/fit/brochure", json=wanted | {"email": f"other{n}@example.test"}).status_code == 200
    assert client.post("/api/fit/brochure", json=wanted | {"email": "last@example.test"}).status_code == 429
    # And only so many questions in ten minutes.
    monkeypatch.setattr(enquiry, "ASKS", 3)
    enquiry._counted.clear()
    codes = [client.post("/api/fit", json={"text": SOCIETY}, headers={"X-Real-IP": "203.0.113.20"}).status_code for _ in range(4)]
    assert codes == [200, 200, 200, 429]
    assert client.post("/api/fit", json={"text": SOCIETY}, headers={"X-Real-IP": "203.0.113.21"}).status_code == 200


def test_with_no_mail_server_the_page_is_handed_the_links(client):
    from app.mailer import Mailer

    client.app.state.mailer = Mailer()   # the real one: the tests set no mail server
    told = client.post("/api/fit", json={"text": SOCIETY}).json()
    handed = client.post("/api/fit/brochure", json={"ticket": told["ticket"], "email": "dev@example.test"}).json()
    assert handed["sent"] is False and handed["attached"] is False and handed["site"] == HOME and handed["whatsapp"] == told["whatsapp"]
    # The page then offers the brochure itself, where there is one in the folder.
    assert handed["brochure"] == (HOME + "static/site/" + front.papers()["brochure"] if front.papers()["brochure"] else "")


def test_the_home_page_has_the_form(client):
    page = client.get(BASE + "/").text
    assert 'id="ask"' in page and 'href="#ask"' in page and 'id="fit-text"' in page and 'id="fit-brochure"' in page
    # The brochure is offered at the foot of the page when it is in the folder, and not named when it is not.
    assert ("Brochure (PDF)" in page) == bool(front.papers()["brochure"]) and "{{brochure}}" not in page
    script = client.get(BASE + "/static/home.js").text
    assert '"api/fit"' in script and '"api/fit/brochure"' in script and 'fetch("/' not in script
