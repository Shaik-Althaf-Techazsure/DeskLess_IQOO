"""
What a user wrote while their phone had no network, brought in by a phone nearby that has one: taken
only when the user's own phone signed it, taken once however often and by whichever way it arrives, and
nothing of what the place answers given to the phone that carried it.
"""

import hashlib
import hmac
import io
import json
import re

from PIL import Image

from app import pulse, security

from .conftest import PASSWORD, photo, signed_in
from .test_door import opened, send_in, user

NOTE = "Water is leaking on the stairs of block B since morning. पानी टपक रहा है।"


def key_of(client, who) -> str:
    """The key a user's phone is given when it signs in and each time it checks in."""
    return client.get("/api/me", headers=who.headers).json()["carry_key"]


def written(place: str, **said) -> str:
    """An entry as the user's phone puts it down, and signs it: one string of JSON."""
    entry = {"place": place, "kind": "issue", "title": "Water leaking on the stairs", "note": NOTE, "location": "Block B stairs",
             "category": "Plumbing", "severity": "HIGH", "client_id": "r1", "made_at": 1791666000000} | said
    return json.dumps(entry, ensure_ascii=False, separators=(",", ":"))


def signed(key: str, entry: str) -> str:
    return hmac.new(key.encode("utf-8"), entry.encode("utf-8"), hashlib.sha256).hexdigest()


def carry(client, carrier, sender: str, entry: str, sig: str, picture: bytes | None = None):
    """A phone that has a network posts what it was handed, as the app does: a form with parts, the picture one of them."""
    parts = {"sender": (None, sender), "entry": (None, entry), "sig": (None, sig)}
    if picture is not None:
        parts["photo"] = ("photo.jpg", picture, "image/jpeg")
    return client.post("/api/requests/carried", files=parts, headers=carrier.headers)


def id_of(client, admin, name: str) -> str:
    return next(row["id"] for row in client.get("/api/people", headers=admin).json() if row["name"] == name)


def sent_by(client, who) -> list[dict]:
    return client.get("/api/requests", headers=who.headers).json()["requests"]


def test_what_a_nearby_phone_carries_in_reaches_the_place_as_if_the_user_had_sent_it(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    picture = photo()
    entry = written(place, photo_sha256=hashlib.sha256(picture).hexdigest())
    # The technician's phone has a network; the user's had none and handed it over.
    taken = carry(client, company.technical, nisha.unique_id, entry, signed(key_of(client, nisha), entry), picture)
    assert taken.status_code == 200, taken.text
    told = taken.json()
    assert told == {
        "taken": True, "already": False, "id": told["id"], "client_id": "r1", "place": "Green Valley Builders",
        "title": "Water leaking on the stairs", "sent_to": "Meena Iyer",
    }

    # It is the user's own: on their list, with where it stands and with its picture.
    assert [(row["id"], row["kind"], row["severity"], row["standing"], row["has_photo"]) for row in sent_by(client, nisha)] == [
        (told["id"], "issue", "HIGH", "With Meena Iyer. Nobody has taken it up yet.", True),
    ]
    # And it waits on the place's inspector exactly as if the user's phone had sent it: their words in
    # the inbox, the issue at the top of the desk with the same step proposed, and the email.
    inbox = client.get("/api/inbox", headers=company.inspector.headers).json()["for_me"]
    assert [(row["from"], row["from_role"], row["text"]) for row in inbox] == [("Nisha Rao", "User", NOTE)]
    desk = client.get("/api/desk", headers=company.inspector.headers).json()
    assert [(job["id"], job["next"]["label"]) for job in desk["queue"]] == [(told["id"], "Send it to Ravi Khan to give out")]
    assert "Nisha Rao" in mailbox.last_to(company.inspector.email)[0]
    assert client.get(f"/api/issues/{told['id']}/photo", headers=company.inspector.headers).status_code == 200
    # Its history says how it came.
    history = client.get(f"/api/issues/{told['id']}", headers=company.inspector.headers).json()["history"]
    assert [event["text"] for event in history] == [
        "Nisha Rao sent in an issue as a user of Green Valley Builders; Meena Iyer was told; carried by Arjun Das's phone, with no network on their own",
    ]
    # What the user's own phone sends next goes to the same desk, and is not said to have been carried.
    direct = send_in(client, nisha, place, client_id="r2", title="Lift is stuck").json()
    assert direct["sent_to"] == told["sent_to"]
    assert {job["id"] for job in client.get("/api/desk", headers=company.inspector.headers).json()["queue"]} == {told["id"], direct["id"]}
    assert "carried" not in client.get(f"/api/issues/{direct['id']}", headers=company.inspector.headers).json()["history"][0]["text"]

    # The phone that carried it has nothing of it afterwards: not on its own list, and not to open.
    assert sent_by(client, company.technical) == []
    assert client.get(f"/api/issues/{told['id']}", headers=company.technical.headers).status_code == 404
    assert all(row["id"] != told["id"] for row in client.get("/api/issues", headers=company.technical.headers).json())


def test_arriving_twice_or_by_both_ways_it_is_one_issue(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    omar = user(client, mailbox, "Omar Sheikh")
    key = key_of(client, nisha)
    entry = written(place)
    first = carry(client, company.technical, nisha.unique_id, entry, signed(key, entry)).json()
    # Handed to two phones, or posted again by one that never heard the answer: it is taken once. The
    # second phone here is another user's, which may carry as well as any.
    again = carry(client, omar, nisha.unique_id, entry, signed(key, entry))
    assert again.status_code == 200 and again.json() == first | {"already": True}
    assert first["already"] is False
    # Then the user's own phone finds a network and sends it as it always would: the same issue answers.
    own = send_in(client, nisha, place)
    assert own.status_code == 200 and own.json()["id"] == first["id"]
    assert [row["id"] for row in sent_by(client, nisha)] == [first["id"]]
    assert sent_by(client, omar) == []
    assert len(client.get("/api/inbox", headers=company.inspector.headers).json()["for_me"]) == 1
    assert len([subject for to, subject, _ in mailbox.sent if to == company.inspector.email and "Nisha Rao" in subject]) == 1

    # And the other way about: sent by their own phone first, the carried copy arriving late adds nothing.
    direct = send_in(client, nisha, place, client_id="r2", title="Lift is stuck").json()
    late = written(place, client_id="r2", title="Lift is stuck")
    assert carry(client, omar, nisha.unique_id, late, signed(key, late)).json() == {
        "taken": True, "already": True, "id": direct["id"], "client_id": "r2", "place": "Green Valley Builders", "title": "Lift is stuck", "sent_to": "Meena Iyer",
    }
    assert len(sent_by(client, nisha)) == 2


def test_what_the_user_did_not_sign_is_refused_and_nothing_is_made(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    omar = user(client, mailbox, "Omar Sheikh")
    entry = written(place)
    sig = signed(key_of(client, nisha), entry)
    # A word changed on the way; another user's key under this user's name; and no signature worth the name.
    forged = [
        (written(place, severity="CRITICAL"), sig),
        (entry + " ", sig),
        (entry, signed(key_of(client, omar), entry)),
        (entry, signed(nisha.unique_id, entry)),
        (entry, "0" * 64),
        (entry, "not a signature"),
        (entry, "हस्ताक्षर"),
    ]
    for given, mark in forged:
        refused = carry(client, company.technical, nisha.unique_id, given, mark)
        assert (refused.status_code, refused.json()["detail"]) == (403, "This was not written by the person it names."), mark
    assert sent_by(client, nisha) == []
    assert client.get("/api/desk", headers=company.inspector.headers).json()["queue"] == []
    assert client.get("/api/inbox", headers=company.inspector.headers).json()["for_me"] == []
    assert client.get("/api/issues", headers=company.md.headers).json() == []
    # A phone nobody is signed in on carries nothing, however well it is signed.
    assert client.post("/api/requests/carried", data={"sender": nisha.unique_id, "entry": entry, "sig": sig}).status_code == 401
    # The user is known by their ID however its letters come, and the signature by its digits.
    assert carry(client, company.technical, " dl-usr-0001 ", entry, sig.upper()).status_code == 200


def test_only_a_user_of_a_place_can_be_named_as_the_sender(client, mailbox, admin, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    entry = written(place)
    sig = signed(key_of(client, nisha), entry)
    # Nobody by that ID; one of a company's own people, who has no such key; a name in place of the ID.
    for named in ("DL-USR-0999", company.inspector.unique_id, company.md.unique_id, "Nisha Rao"):
        refused = carry(client, company.technical, named, entry, sig)
        assert (refused.status_code, refused.json()["detail"]) == (404, "The person this came from is not known here."), named
    # Nor a user who has since been shut out, though the signature is theirs.
    assert client.post(f"/api/people/{id_of(client, admin, 'Nisha Rao')}/suspend", headers=admin).status_code == 200
    assert carry(client, company.technical, nisha.unique_id, entry, sig).status_code == 404
    assert client.get("/api/desk", headers=company.inspector.headers).json()["queue"] == []


def test_a_signed_entry_is_still_checked_like_any_other(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    key = key_of(client, nisha)
    tried = lambda entry: carry(client, company.technical, nisha.unique_id, entry, signed(key, entry))  # noqa: E731
    # Signed, and yet no entry: not JSON, or JSON that is not a set of named things.
    for unreadable in ("water on the stairs", "[1, 2]", '"r1"', "{"):
        refused = tried(unreadable)
        assert (refused.status_code, refused.json()["detail"]) == (422, "That entry cannot be read."), unreadable
    # With no name of its own it could not be told from itself arriving a second time.
    for nameless in (written(place, client_id=""), written(place, client_id="   "), written(place, client_id=7), json.dumps({"place": place, "note": "A light is out."})):
        assert tried(nameless).status_code == 422, nameless
    # Nothing said, a place nobody knows, and a place whose door is shut: as for the user's own phone.
    assert tried(written(place, title="", note=" ")).status_code == 422
    assert tried(written("nowhere")).status_code == 404
    assert client.post("/api/company/door", json={"open": False}, headers=company.md.headers).status_code == 200
    shut = tried(written(place))
    assert (shut.status_code, shut.json()["detail"]) == (404, "That place does not take what its users send in.")
    assert sent_by(client, nisha) == []
    # What a question is and how grave it may be are settled as they are for the user's own phone.
    assert client.post("/api/company/door", json={"open": True}, headers=company.md.headers).status_code == 200
    asked = tried(written(place, kind="enquiry", title="Is the reading room open on Sunday?", note="", severity="CRITICAL", client_id="q1"))
    assert asked.status_code == 200 and asked.json()["title"] == "Is the reading room open on Sunday?"
    assert [(row["kind"], row["severity"]) for row in sent_by(client, nisha)] == [("enquiry", "LOW")]


def test_a_picture_goes_with_it_only_when_it_is_the_one_the_user_signed_for(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    key = key_of(client, nisha)
    picture = photo()
    swapped = io.BytesIO()
    Image.new("RGB", (640, 480), (20, 40, 200)).save(swapped, "JPEG")
    named = hashlib.sha256(picture).hexdigest()

    # Another picture put in its place on the way: the picture is left out, and what was said still goes.
    entry = written(place, client_id="p1", photo_sha256=named)
    taken = carry(client, company.technical, nisha.unique_id, entry, signed(key, entry), swapped.getvalue())
    assert taken.status_code == 200 and taken.json()["already"] is False
    # The one the user's phone took: kept, and the inspector may look at it.
    entry = written(place, client_id="p2", title="Lift is stuck", photo_sha256=named)
    kept = carry(client, company.technical, nisha.unique_id, entry, signed(key, entry), picture)
    assert kept.status_code == 200, kept.text
    # A picture added on the way to an entry the user signed with none: left out as well.
    entry = written(place, client_id="p3", title="Gate does not close")
    added = carry(client, company.technical, nisha.unique_id, entry, signed(key, entry), picture)
    assert added.status_code == 200, added.text
    assert {row["client_id"]: row["has_photo"] for row in sent_by(client, nisha)} == {"p1": False, "p2": True, "p3": False}
    assert client.get(f"/api/issues/{kept.json()['id']}/photo", headers=company.inspector.headers).status_code == 200
    assert client.get(f"/api/issues/{taken.json()['id']}/photo", headers=company.inspector.headers).status_code == 404


def test_the_key_is_given_to_its_owner_and_to_nobody_else(client, mailbox, admin, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    omar = user(client, mailbox, "Omar Sheikh")
    key = key_of(client, nisha)
    assert re.fullmatch(r"[0-9a-f]{64}", key)
    # The phone has it from signing in as well as from checking in, the same each time; each user their own.
    assert client.post("/api/login", json={"unique_id": nisha.unique_id, "password": PASSWORD}).json()["profile"]["carry_key"] == key
    assert key_of(client, nisha) == key != key_of(client, omar)
    # The people of a company carry for others and have nothing carried for them: they are given no key.
    for staff in (company.md, company.manager, company.inspector, company.technical):
        assert "carry_key" not in client.get("/api/me", headers=staff.headers).json()
        assert "carry_key" not in client.post("/api/login", json={"unique_id": staff.unique_id, "password": PASSWORD}).json()["profile"]
    # And it is in nothing that is said about the user to anybody else, wherever they are named.
    told = send_in(client, nisha, place).json()
    elsewhere = [
        ("/api/people", admin), ("/api/linking", admin), ("/api/linking", company.md.headers), ("/api/issues", company.md.headers),
        ("/api/dashboard", company.md.headers), (f"/api/issues/{told['id']}", company.inspector.headers),
        ("/api/desk", company.inspector.headers), ("/api/inbox", company.inspector.headers),
    ]
    for address, headers in elsewhere:
        answer = client.get(address, headers=headers)
        assert answer.status_code == 200 and "Nisha Rao" in answer.text, address
        assert key not in answer.text and "carry_key" not in answer.text, address


def test_a_new_password_makes_a_new_key_and_the_old_one_signs_nothing(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    old = key_of(client, nisha)
    assert client.post("/api/password/forgot", json={"unique_id": nisha.unique_id}).json()["sent"] is True
    changed = client.post("/api/password/reset", json={"unique_id": nisha.unique_id, "code": mailbox.code_for(nisha.email), "password": "a brand new password"})
    assert changed.json() == {"changed": True}
    nisha.headers = signed_in(client, nisha.unique_id, "a brand new password")
    new = key_of(client, nisha)
    assert new != old and re.fullmatch(r"[0-9a-f]{64}", new)
    entry = written(place)
    assert carry(client, company.technical, nisha.unique_id, entry, signed(old, entry)).status_code == 403
    assert carry(client, company.technical, nisha.unique_id, entry, signed(new, entry)).status_code == 200


def test_the_key_is_worked_out_and_never_kept():
    class Account:
        id = "account-1"
        password_hash = security.hash_password(PASSWORD)

    first = security.carry_key(Account)
    assert first == security.carry_key(Account) and re.fullmatch(r"[0-9a-f]{64}", first)
    # Another account with the same password hash, and the same account with another hash, have other keys.
    assert security.carry_key(type("Other", (), {"id": "account-2", "password_hash": Account.password_hash})) != first
    assert security.carry_key(type("Changed", (), {"id": "account-1", "password_hash": security.hash_password(PASSWORD)})) != first


def test_the_linkage_shows_the_phone_that_carried_it_first(client, mailbox, admin, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    nisha_id = id_of(client, admin, "Nisha Rao")
    entry = written(place)
    sig = signed(key_of(client, nisha), entry)
    pulse.clear()
    assert carry(client, company.technical, nisha.unique_id, entry, sig).status_code == 200
    told = client.get("/api/linking", headers=admin).json()
    moved = [(one["kind"], one["from"], one["to"], one["text"]) for one in told["pulses"]]
    assert moved[:2] == [
        ("carried", f"p:{company.technical.id}", "server", "carried in “Water leaking on the stairs” for Nisha Rao, whose phone had no network"),
        ("sent_in", f"p:{nisha_id}", "server", "sent in an issue to Green Valley Builders: “Water leaking on the stairs”"),
    ]
    assert moved[2][:3] == ("route", "assistant", f"p:{company.inspector.id}") and moved[2][3].startswith("chose Meena Iyer (inspector, least waiting) and ranked it ")
    # That is all of it: the posting is not drawn a second time as a plain question, and the legend names the kind.
    assert len(moved) == 3 and "carried" in [kind["key"] for kind in told["kinds"]]
    # The phone that carried it sees that it did, in its own view; a copy arriving again moves nothing.
    own = client.get("/api/linking", headers=company.technical.headers).json()
    assert moved[0][3] in [one["text"] for one in own["pulses"]]
    pulse.clear()
    assert carry(client, company.technical, nisha.unique_id, entry, sig).json()["already"] is True
    assert client.get("/api/linking", headers=admin).json()["pulses"] == []
    pulse.clear()
