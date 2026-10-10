"""
The door a place opens to the people who use it: signing up as a user, finding the place by its kind,
sending something in, its way to the place's inspector and nobody else's, the answer coming back, and a
question being settled by its answer.
"""

from types import SimpleNamespace

from .conftest import PASSWORD, aadhaar, approved, issue, join, photo, send_report, sign_up, signed_in


def user(client, mailbox, name: str = "Nisha Rao") -> SimpleNamespace:
    """Someone who uses a place signs up with the short form and confirms their email address."""
    email = name.lower().replace(" ", ".") + "@example.test"
    answer = client.post("/api/signup/user", json={"name": name, "email": email, "password": PASSWORD})
    assert answer.status_code == 200, answer.text
    done = client.post("/api/signup/verify", json={"email": email, "code": mailbox.code_for(email)})
    assert done.status_code == 200, done.text
    return SimpleNamespace(name=name, email=email, unique_id=done.json()["unique_id"], ticket=answer.json()["ticket"], headers=signed_in(client, done.json()["unique_id"]))


def opened(client, company, kind: str = "apartment") -> str:
    """The managing director opens the door; returns the place as a user finds it."""
    assert client.post("/api/company/door", json={"open": True, "kind": kind}, headers=company.md.headers).status_code == 200
    return next(place["id"] for place in client.get("/api/places", headers=company.md.headers).json()["places"] if place["name"] == "Green Valley Builders")


def send_in(client, who, place: str, **said):
    form = {"place": place, "kind": "issue", "title": "Water leaking on the stairs", "note": "Water is leaking on the stairs of block B since morning.",
            "location": "Block B stairs", "category": "Plumbing", "severity": "HIGH", "client_id": "r1"} | said
    return client.post("/api/requests", data=form, files={"photo": ("leak.jpg", photo(), "image/jpeg")}, headers=who.headers)


def test_a_user_signs_up_with_no_aadhaar_and_nobody_to_approve(client, mailbox, admin):
    nisha = user(client, mailbox)
    assert nisha.unique_id == "DL-USR-0001"
    me = client.get("/api/me", headers=nisha.headers).json()
    assert (me["role"], me["role_name"], me["aadhaar"], me["organisation"]) == ("member", "User", "", "")
    # The phone that signed up hears it is approved at once, and nothing waits for the administrator.
    status = client.get("/api/signup/status", params={"email": nisha.email, "ticket": nisha.ticket}).json()
    assert (status["status"], status["unique_id"]) == ("approved", "DL-USR-0001")
    assert all(row["email"] != nisha.email for row in client.get("/api/admin/accounts", headers=admin).json())
    assert "DL-USR-0001" in mailbox.last_to(nisha.email)[1]


def test_the_short_form_checks_what_it_is_given(client, mailbox):
    post = lambda **changes: client.post("/api/signup/user", json={"name": "Nisha Rao", "email": "nisha@example.test", "password": PASSWORD} | changes)  # noqa: E731
    assert post(name="N").status_code == 422
    assert post(email="nisha").status_code == 422
    assert post(password="short").status_code == 422
    assert post(phone="12345").status_code == 422
    assert post(phone="98765 43210").status_code == 200
    # Started again before the code was typed: the first try is simply dropped.
    assert post().status_code == 200
    # The form for the people of a company does not make users.
    assert sign_up(client, role="member", email="other@example.test", aadhaar=aadhaar(7)).status_code == 422
    # And a sign-up that stopped at the code does not get in the way of the long form at that address.
    assert sign_up(client, email="nisha@example.test", aadhaar=aadhaar(8)).status_code == 200


def test_a_place_is_named_only_once_its_managing_director_opens_the_door(client, mailbox, company):
    nisha = user(client, mailbox)
    assert client.get("/api/places", headers=nisha.headers).json()["places"] == []
    assert client.post("/api/requests", data={"place": "nowhere", "note": "A light is out."}, headers=nisha.headers).status_code == 404

    door = client.get("/api/company/door", headers=company.md.headers).json()
    assert (door["open"], door["may_set"]) == (False, True)
    assert client.get("/api/company/door", headers=company.manager.headers).json()["may_set"] is False
    assert client.post("/api/company/door", json={"open": True, "kind": "apartment"}, headers=company.manager.headers).status_code == 403
    # Opening it means saying what kind of place it is.
    assert client.post("/api/company/door", json={"open": True}, headers=company.md.headers).status_code == 422
    assert client.post("/api/company/door", json={"open": True, "kind": "apartment"}, headers=company.md.headers).status_code == 200

    found = client.get("/api/places", headers=nisha.headers).json()
    assert [place["name"] for place in found["places"]] == ["Green Valley Builders"]
    assert found["places"][0]["kind_name"] == "Apartments and housing"
    assert next(kind for kind in found["kinds"] if kind["key"] == "apartment")["places"] == 1
    assert client.get("/api/places", params={"kind": "college"}, headers=nisha.headers).json()["places"] == []
    assert len(client.get("/api/places", params={"kind": "apartment"}, headers=nisha.headers).json()["places"]) == 1

    # Shut again, it takes nothing and is named to nobody.
    place = found["places"][0]["id"]
    assert client.post("/api/company/door", json={"open": False}, headers=company.md.headers).status_code == 200
    assert client.get("/api/places", headers=nisha.headers).json()["places"] == []
    assert send_in(client, nisha, place).status_code == 404


def test_what_a_user_sends_in_reaches_the_inspector_of_that_place_and_nobody_elses(client, mailbox, admin, company):
    place = opened(client, company)
    # A second company, open as a college, with an inspector of its own.
    other_md = signed_in(client, approved(client, mailbox, admin, name="Farah Khan", email="farah@example.test", aadhaar=aadhaar(2), organisation="Hilltop College"))
    other_inspector = join(client, mailbox, other_md, "inspector", "Dev Nair")
    assert client.post("/api/company/door", json={"open": True, "kind": "college"}, headers=other_md).status_code == 200

    nisha = user(client, mailbox)
    sent = send_in(client, nisha, place)
    assert sent.status_code == 200, sent.text
    told = sent.json()
    assert (told["place"], told["kind"], told["status"], told["sent_to"], told["has_photo"]) == ("Green Valley Builders", "issue", "open", "Meena Iyer", True)
    assert told["standing"] == "With Meena Iyer. Nobody has taken it up yet."
    assert "Nisha Rao" in mailbox.last_to(company.inspector.email)[0]

    # It waits on the place's inspector, in their inbox and at the top of their desk, with the sender's words.
    inbox = client.get("/api/inbox", headers=company.inspector.headers).json()["for_me"]
    assert [(row["from"], row["from_role"], row["text"]) for row in inbox] == [("Nisha Rao", "User", "Water is leaking on the stairs of block B since morning.")]
    desk = client.get("/api/desk", headers=company.inspector.headers).json()
    assert [job["title"] for job in desk["queue"]] == ["Water leaking on the stairs"]
    step = desk["queue"][0]["next"]
    assert (step["kind"], step["to_name"], step["label"]) == ("send", "Ravi Khan", "Send it to Ravi Khan to give out")
    # The inspector sees the picture; the managing director sees the issue as one of the company's, marked as sent in.
    assert client.get(f"/api/issues/{told['id']}/photo", headers=company.inspector.headers).status_code == 200
    seen = next(row for row in client.get("/api/issues", headers=company.md.headers).json() if row["id"] == told["id"])
    assert (seen["raised_as"], seen["reported_by"], seen["site"], seen["severity"]) == ("issue", "Nisha Rao", "Green Valley Builders", "HIGH")

    # Nobody at the other place sees it or hears of it; nor does the manager here, until it is sent up.
    for stranger in (other_md, other_inspector.headers, company.manager.headers, company.technical.headers):
        assert all(row["id"] != told["id"] for row in client.get("/api/issues", headers=stranger).json())
        assert client.get(f"/api/issues/{told['id']}", headers=stranger).status_code == 404
    assert client.get("/api/desk", headers=other_inspector.headers).json()["queue"] == []

    # Sent again by a phone that never heard the answer, it is taken once.
    assert send_in(client, nisha, place).json()["id"] == told["id"]
    assert len(client.get("/api/requests", headers=nisha.headers).json()["requests"]) == 1


def test_it_is_worked_like_any_issue_and_the_sender_hears_each_step(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    told = send_in(client, nisha, place).json()
    mark = client.get("/api/requests", headers=nisha.headers).json()["mark"]

    # The inspector answers the sender and sends it up; the manager gives it out; it is repaired and closed.
    sendable = client.get(f"/api/issues/{told['id']}", headers=company.inspector.headers).json()["send_to"]
    nisha_id = next(one["id"] for one in sendable if one["name"] == "Nisha Rao")
    manager_id = next(one["id"] for one in sendable if one["name"] == "Ravi Khan")
    assert client.post(f"/api/issues/{told['id']}/send", json={"to": nisha_id, "text": "Thank you. We are sending a plumber today."}, headers=company.inspector.headers).status_code == 200
    mine = client.get("/api/requests", headers=nisha.headers).json()
    assert mine["mark"] != mark
    row = mine["requests"][0]
    assert row["unheard"] == 1 and row["thread"][-1]["text"] == "Thank you. We are sending a plumber today." and row["thread"][-1]["from_role"] == "Inspector"
    assert row["standing"] == "Meena Iyer (Inspector) has answered. Nobody has been given it to put right yet."
    assert "plumber" in mailbox.last_to(nisha.email)[1]
    # Once the phone has read it out, it no longer waits on the sender; the same mark means nothing new.
    assert client.post(f"/api/requests/{told['id']}/heard", headers=nisha.headers).json()["unheard"] == 0
    steady = client.get("/api/requests", headers=nisha.headers).json()["mark"]
    assert client.get("/api/requests", headers=nisha.headers).json()["mark"] == steady

    # Thanking the sender does not take it off the inspector's hands: it is still theirs to pass up.
    still = client.get("/api/desk", headers=company.inspector.headers).json()["queue"]
    assert [(job["id"], job["next"]["label"]) for job in still] == [(told["id"], "Send it to Ravi Khan to give out")]
    assert client.post(f"/api/issues/{told['id']}/send", json={"to": manager_id, "text": "Please give this to a plumber."}, headers=company.inspector.headers).status_code == 200
    # Passed up, it waits on the manager and no longer on the inspector.
    assert client.get("/api/desk", headers=company.inspector.headers).json()["queue"] == []
    step = next(job for job in client.get("/api/desk", headers=company.manager.headers).json()["queue"] if job["id"] == told["id"])["next"]
    assert (step["kind"], step["to_name"]) == ("assign", "Arjun Das")
    assert client.post(f"/api/issues/{told['id']}/assign", json={"to": step["to"], "due_on": step["due_on"]}, headers=company.manager.headers).status_code == 200
    assert client.get("/api/requests", headers=nisha.headers).json()["requests"][0]["standing"].startswith("Given to Arjun Das to put right, by ")

    fixed = client.post(f"/api/issues/{told['id']}/fixed", data={"note": "Joint replaced."}, files={"photo": ("fix.jpg", photo(), "image/jpeg")}, headers=company.technical.headers)
    assert fixed.status_code == 200, fixed.text
    row = client.get("/api/requests", headers=nisha.headers).json()["requests"][0]
    assert row["has_fix_photo"] and row["standing"] == "Arjun Das says it is repaired and has shown a picture. It waits to be checked."
    # The sender may look at the repair, as at their own picture.
    assert client.get(f"/api/issues/{told['id']}/fix-photo", headers=nisha.headers).status_code == 200
    assert client.post(f"/api/issues/{told['id']}/close", json={}, headers=company.manager.headers).status_code == 200
    assert client.get("/api/requests", headers=nisha.headers).json()["requests"][0]["standing"] == "Repaired, checked and closed by Ravi Khan."


def test_the_sender_can_add_a_word_and_it_goes_to_whoever_wrote_last(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    told = send_in(client, nisha, place).json()
    assert client.post(f"/api/requests/{told['id']}/say", json={"text": " "}, headers=nisha.headers).status_code == 422
    # Before anyone has answered, more about it goes to whoever was told first.
    more = client.post(f"/api/requests/{told['id']}/say", json={"text": "It is getting worse."}, headers=nisha.headers)
    assert more.status_code == 200 and [said["text"] for said in more.json()["thread"]][-1] == "It is getting worse."
    assert client.get("/api/inbox", headers=company.inspector.headers).json()["for_me"][0]["text"] == "It is getting worse."
    # Another user's things are not theirs to see or to add to.
    omar = user(client, mailbox, "Omar Sheikh")
    assert client.get("/api/requests", headers=omar.headers).json()["requests"] == []
    assert client.post(f"/api/requests/{told['id']}/say", json={"text": "Mine too."}, headers=omar.headers).status_code == 404
    assert client.get(f"/api/issues/{told['id']}", headers=omar.headers).status_code == 404


def test_a_question_is_answered_not_repaired_and_the_answer_settles_it(client, mailbox, company):
    place = opened(client, company)
    nisha = user(client, mailbox)
    asked = send_in(client, nisha, place, kind="enquiry", title="Is the swimming pool open on Sunday?", note="Is the swimming pool open on Sunday?", severity="CRITICAL", category="", client_id="q1").json()
    assert (asked["kind"], asked["severity"]) == ("enquiry", "LOW")
    step = client.get("/api/desk", headers=company.inspector.headers).json()["queue"][0]["next"]
    assert (step["kind"], step["label"], step["text"]) == ("send", "Answer Nisha Rao", "")
    # The managing director, who sees the whole company, is offered the same and not a technician to give it to.
    md_step = client.get(f"/api/issues/{asked['id']}", headers=company.md.headers).json()["brief"]["next"]
    assert (md_step["kind"], md_step["label"]) == ("send", "Answer Nisha Rao")

    answer = client.post(f"/api/issues/{asked['id']}/send", json={"to": step["to"], "text": "Yes, from 6 am to 9 pm."}, headers=company.inspector.headers)
    assert answer.status_code == 200 and answer.json()["status"] == "closed"
    row = client.get("/api/requests", headers=nisha.headers).json()["requests"][0]
    assert (row["status"], row["standing"], row["thread"][-1]["text"]) == ("closed", "Answered and closed.", "Yes, from 6 am to 9 pm.")
    assert client.get("/api/desk", headers=company.inspector.headers).json()["queue"] == []
    # Asked again, it is open again and waits on whoever answered.
    again = client.post(f"/api/requests/{asked['id']}/say", json={"text": "And on public holidays?"}, headers=nisha.headers).json()
    assert again["status"] == "open"
    assert client.get("/api/desk", headers=company.inspector.headers).json()["queue"][0]["next"]["label"] == "Answer Nisha Rao"


def test_it_goes_to_the_inspector_with_least_waiting_and_up_the_line_when_there_is_none(client, mailbox, admin, company):
    place = opened(client, company)
    second = join(client, mailbox, company.manager.headers, "inspector", "Sana Pillai")
    nisha = user(client, mailbox)
    first = send_in(client, nisha, place, client_id="a").json()
    then = send_in(client, nisha, place, client_id="b", title="Lift is stuck").json()
    assert (first["sent_to"], then["sent_to"]) == ("Meena Iyer", "Sana Pillai")
    assert [job["title"] for job in client.get("/api/desk", headers=second.headers).json()["queue"]] == ["Lift is stuck"]

    # A place with no inspector yet: its managing director is told, and is offered what a lead is offered.
    lone = signed_in(client, approved(client, mailbox, admin, name="Farah Khan", email="farah@example.test", aadhaar=aadhaar(2), organisation="Hilltop College"))
    assert client.post("/api/company/door", json={"open": True, "kind": "college"}, headers=lone).status_code == 200
    college = next(one["id"] for one in client.get("/api/places", params={"kind": "college"}, headers=nisha.headers).json()["places"])
    assert send_in(client, nisha, college, client_id="c").json()["sent_to"] == "Farah Khan"
    assert client.get("/api/inbox", headers=lone).json()["for_me"][0]["from"] == "Nisha Rao"


def test_what_a_walk_found_is_untouched_by_all_this(client, mailbox, company):
    opened(client, company)
    assert send_report(client, company.inspector.headers, issues=[issue("a1")], built=False).status_code == 200
    row = client.get("/api/issues", headers=company.manager.headers).json()[0]
    assert (row["raised_as"], row["reported_by"]) == ("", "Meena Iyer")
    # A walk's issue is not among what anyone "sent in", and the door's count says so.
    assert client.get("/api/company/door", headers=company.md.headers).json()["sent_in"] == 0
    assert client.get("/api/requests", headers=company.inspector.headers).json()["requests"] == []
