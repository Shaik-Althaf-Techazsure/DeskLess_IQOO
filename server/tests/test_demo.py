"""The three places made for showing: their people can sign in, each place takes only its own, and they can be shut again."""

from app import demo
from app.db import SessionLocal

from .conftest import signed_in
from .test_door import send_in, user


def _id(rows: list[dict], place: str, role: str) -> str:
    return next(row["unique_id"] for row in rows if row["place"] == place and row["role"] == role)


def test_three_places_of_three_kinds_each_with_its_own_people(client, mailbox):
    with SessionLocal() as db:
        rows = demo.seed(db, "first-password-1")
    assert len(rows) == 12 and len({row["unique_id"] for row in rows}) == 12
    assert {(row["place"], row["kind"]) for row in rows} == {("Orchid Residency", "apartment"), ("Northgate College", "college"), ("Lakeview Business Park", "office")}

    # Everyone signs in at once, with no code and nobody to approve them; an inspector answers to the place's manager.
    inspector = signed_in(client, _id(rows, "Northgate College", "Inspector"), "first-password-1")
    me = client.get("/api/me", headers=inspector).json()
    assert (me["organisation"], me["reports_to"], me["report_type"]) == ("Northgate College", "Priya Kulkarni", "CAMPUS")
    assert client.get("/api/me/photo", headers=inspector).status_code == 200

    # A user of the app finds each under its kind, and what they send to the college reaches the college's inspector alone.
    nisha = user(client, mailbox)
    found = client.get("/api/places", headers=nisha.headers).json()
    assert sorted((place["name"], place["kind"]) for place in found["places"]) == [
        ("Lakeview Business Park", "office"), ("Northgate College", "college"), ("Orchid Residency", "apartment"),
    ]
    college = next(place["id"] for place in found["places"] if place["kind"] == "college")
    assert send_in(client, nisha, college, title="Projector not working", note="The projector in room 204 does not switch on.").json()["sent_to"] == "Vikram Joshi"
    assert [job["title"] for job in client.get("/api/desk", headers=inspector).json()["queue"]] == ["Projector not working"]
    for place in ("Orchid Residency", "Lakeview Business Park"):
        other = signed_in(client, _id(rows, place, "Inspector"), "first-password-1")
        assert client.get("/api/desk", headers=other).json()["queue"] == []
        assert client.get("/api/issues", headers=signed_in(client, _id(rows, place, "Managing Director"), "first-password-1")).json() == []

    # Run again: nobody is made twice, the IDs stay, and the new password takes the old one's place.
    with SessionLocal() as db:
        again = demo.seed(db, "second-password-2")
    assert [row["unique_id"] for row in again] == [row["unique_id"] for row in rows]
    assert client.post("/api/login", json={"unique_id": rows[0]["unique_id"], "password": "first-password-1"}).status_code == 401
    signed_in(client, rows[0]["unique_id"], "second-password-2")

    # Shut: the places are named to nobody and the stand-ins are turned away; the user's own account is untouched.
    with SessionLocal() as db:
        assert demo.off(db) == 12
    assert client.get("/api/places", headers=nisha.headers).json()["places"] == []
    assert client.post("/api/login", json={"unique_id": rows[0]["unique_id"], "password": "second-password-2"}).status_code == 403
    assert client.get("/api/desk", headers=inspector).status_code == 401
    # And made again for the next showing, they are let back in.
    with SessionLocal() as db:
        demo.seed(db, "third-password-3")
    signed_in(client, rows[0]["unique_id"], "third-password-3")
    assert len(client.get("/api/places", headers=nisha.headers).json()["places"]) == 3
