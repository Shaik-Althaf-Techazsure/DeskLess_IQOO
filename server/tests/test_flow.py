"""The account flow from end to end, and each way it must refuse."""

import json

from app import security, service
from app.models import Account
from app.db import SessionLocal

from .conftest import aadhaar, approved, form, join, photo, sign_up


def test_sign_up_code_approval_and_sign_in(client, mailbox, admin):
    # 1. The form goes in and a code is emailed.
    answer = sign_up(client)
    assert answer.status_code == 200
    ticket = answer.json()["ticket"]
    status = lambda: client.get("/api/signup/status", params={"email": "asha@example.test", "ticket": ticket}).json()
    assert status()["status"] == "email_pending"

    # 2. The code confirms the email address; a managing director's sign-up now waits for the administrator.
    code = mailbox.code_for("asha@example.test")
    assert client.post("/api/signup/verify", json={"email": "asha@example.test", "code": code}).json() == {"status": "pending_approval"}
    assert status()["status"] == "pending_approval" and status()["approver"] == "the DeskLess administrator"
    assert "unique_id" not in status()
    assert mailbox.last_to("admin@example.test")[0] == "DeskLess: Asha Verma is waiting for approval"

    # 3. The administrator sees it and approves it. That gives the unique ID, by email and to the phone.
    waiting = client.get("/api/admin/accounts", headers=admin).json()
    assert [row["name"] for row in waiting] == ["Asha Verma"]
    decided = client.post(f"/api/admin/accounts/{waiting[0]['id']}/approve", headers=admin).json()
    assert decided["unique_id"] == "DL-MD-0001" and decided["emailed"] is True
    assert "DL-MD-0001" in mailbox.last_to("asha@example.test")[1]
    assert status() == {
        "status": "approved", "name": "Asha Verma", "role": "md", "role_name": "Managing Director",
        "company": "Green Valley Builders", "approver": "the DeskLess administrator", "unique_id": "DL-MD-0001",
    }

    # 4. Sign in with the ID and the password chosen at sign-up.
    signed_in = client.post("/api/login", json={"unique_id": "dl-md-0001", "password": "correct horse battery"})
    assert signed_in.status_code == 200
    profile = signed_in.json()["profile"]
    # Approval also gave the code that the company's managers will sign up with.
    code = profile.pop("invite_code")
    assert len(code) == 9 and code[4] == "-" and code in mailbox.last_to("asha@example.test")[1]
    assert profile == {
        "unique_id": "DL-MD-0001", "role": "md", "role_name": "Managing Director", "name": "Asha Verma",
        "email": "asha@example.test", "phone": "9876543210", "organisation": "Green Valley Builders",
        "aadhaar": f"XXXX XXXX {aadhaar(1)[-4:]}", "status": "approved", "reports_to": "",
    }
    profile["invite_code"] = code
    me = {"Authorization": f"Bearer {signed_in.json()['token']}"}
    assert client.get("/api/me", headers=me).json() == profile
    assert client.get("/api/me/photo", headers=me).headers["content-type"] == "image/jpeg"


def test_nobody_signs_in_before_approval(client, mailbox, admin):
    sign_up(client)
    client.post("/api/signup/verify", json={"email": "asha@example.test", "code": mailbox.code_for("asha@example.test")})
    # There is no ID yet, and the email address is not one.
    assert client.post("/api/login", json={"unique_id": "asha@example.test", "password": "correct horse battery"}).status_code == 401
    assert client.post("/api/login", json={"unique_id": "DL-MD-0001", "password": "correct horse battery"}).status_code == 401


def test_the_aadhaar_number_is_never_given_back(client, mailbox, admin):
    number = aadhaar(1)
    unique_id = approved(client, mailbox, admin)
    signed_in = client.post("/api/login", json={"unique_id": unique_id, "password": "correct horse battery"}).json()
    everything = json.dumps(
        [
            signed_in,
            client.get("/api/admin/accounts", params={"status": "approved"}, headers=admin).json(),
            mailbox.sent,
        ]
    )
    assert number not in everything and number[:8] not in everything
    assert f"XXXX XXXX {number[-4:]}" in everything
    # In the database it is sealed: the stored bytes do not hold the digits, and only the key opens them.
    with SessionLocal() as db:
        stored = db.query(Account).one()
    assert number.encode() not in stored.aadhaar_sealed
    assert security.open_aadhaar(stored.aadhaar_sealed) == number


def test_what_a_sign_up_form_must_hold(client):
    refused = lambda **changes: sign_up(client, **changes)
    assert refused(role="visitor").json()["detail"] == "Choose Managing Director, Manager, Inspector or Technical."
    assert refused(organisation="").json()["detail"] == "Enter your company's name."
    assert refused(name="A").status_code == 422
    assert refused(email="not-an-email").status_code == 422
    assert refused(phone="12345").json()["detail"] == "Enter a 10-digit mobile number."
    assert refused(password="short").status_code == 422
    # One digit wrong, and two neighbouring digits swapped: the check digit catches both.
    good = aadhaar(7)
    wrong_digit = good[:5] + str((int(good[5]) + 1) % 10) + good[6:]
    swapped = good[:9] + good[10] + good[9] + good[11]
    for bad in (wrong_digit, swapped, "1234", "0999" + good[4:]):
        if bad != good:
            assert refused(aadhaar=bad).json()["detail"] == "That Aadhaar number is not valid. Check the 12 digits."
    not_a_photo = client.post("/api/signup", data=form(), files={"photo": ("me.jpg", b"this is not a picture", "image/jpeg")})
    assert not_a_photo.json()["detail"] == "That file is not a photo. Use a JPEG or PNG picture."
    assert client.post("/api/signup", data=form()).status_code == 422  # no photo at all


def test_a_wrong_code_and_too_many_wrong_codes(client, mailbox):
    sign_up(client)
    code = mailbox.code_for("asha@example.test")
    wrong = "000000" if code != "000000" else "111111"
    verify = lambda typed: client.post("/api/signup/verify", json={"email": "asha@example.test", "code": typed})
    assert verify(wrong).json()["detail"] == "That is not the code. 4 tries left."
    for _ in range(4):
        verify(wrong)
    # After five wrong tries even the right code is refused; a fresh one has to be asked for.
    assert verify(code).status_code == 429
    assert client.post("/api/signup/resend", json={"email": "asha@example.test"}).status_code == 200
    fresh = mailbox.code_for("asha@example.test")
    assert verify(code).status_code == 400 or code == fresh  # the old code is out of use
    assert verify(fresh).json() == {"status": "pending_approval"}


def test_a_code_cannot_be_asked_for_again_at_once(client, mailbox, monkeypatch):
    sign_up(client)
    monkeypatch.setattr(service, "RESEND_SECONDS", 60)
    assert client.post("/api/signup/resend", json={"email": "asha@example.test"}).status_code == 429


def test_one_account_for_an_email_address_and_for_an_aadhaar_number(client, mailbox, admin):
    approved(client, mailbox, admin)
    again = sign_up(client, aadhaar=aadhaar(2))
    assert again.status_code == 409 and "already exists" in again.json()["detail"]
    same_number = sign_up(client, email="someone.else@example.test")
    assert same_number.json()["detail"] == "An account with this Aadhaar number already exists."


def test_a_sign_up_left_at_the_code_can_be_started_again(client, mailbox):
    first = sign_up(client).json()["ticket"]
    second = sign_up(client, name="Asha V. Verma").json()["ticket"]
    assert client.get("/api/signup/status", params={"email": "asha@example.test", "ticket": first}).status_code == 404
    assert client.get("/api/signup/status", params={"email": "asha@example.test", "ticket": second}).json()["name"] == "Asha V. Verma"


def test_rejection_gives_the_reason_and_allows_a_fresh_sign_up(client, mailbox, admin):
    ticket = sign_up(client).json()["ticket"]
    client.post("/api/signup/verify", json={"email": "asha@example.test", "code": mailbox.code_for("asha@example.test")})
    waiting = client.get("/api/admin/accounts", headers=admin).json()[0]
    client.post(f"/api/admin/accounts/{waiting['id']}/reject", json={"reason": "The photo does not show a face."}, headers=admin)
    status = client.get("/api/signup/status", params={"email": "asha@example.test", "ticket": ticket}).json()
    assert status["status"] == "rejected" and status["reason"] == "The photo does not show a face."
    assert "The photo does not show a face." in mailbox.last_to("asha@example.test")[1]
    # A decision is made once.
    assert client.post(f"/api/admin/accounts/{waiting['id']}/approve", headers=admin).status_code == 409
    # The person may try again with the same email address and Aadhaar number.
    assert sign_up(client).status_code == 200


def test_each_kind_of_account_counts_its_own_ids(client, mailbox, admin, company):
    assert [company.md.unique_id, company.manager.unique_id, company.inspector.unique_id, company.technical.unique_id] == [
        "DL-MD-0001", "DL-MGR-0001", "DL-INS-0001", "DL-TEC-0001",
    ]
    assert join(client, mailbox, company.manager.headers, "inspector", "Kiran Rao").unique_id == "DL-INS-0002"
    # What the first version of the app called an employer is a managing director.
    assert approved(client, mailbox, admin, email="second@example.test", aadhaar=aadhaar(2), role="employer", organisation="Second Co") == "DL-MD-0002"
    summary = client.get("/api/admin/summary", headers=admin).json()
    assert summary == {"pending_approval": 0, "approved": 6, "rejected": 0}


def test_wrong_passwords_lock_the_account_for_a_while(client, mailbox, admin):
    unique_id = approved(client, mailbox, admin)
    attempt = lambda password: client.post("/api/login", json={"unique_id": unique_id, "password": password})
    for _ in range(5):
        assert attempt("not the password").status_code == 401
    locked = attempt("correct horse battery")
    assert locked.status_code == 429 and "Too many wrong passwords" in locked.json()["detail"]


def test_a_forgotten_password_is_reset_with_a_code(client, mailbox, admin):
    unique_id = approved(client, mailbox, admin)
    for _ in range(5):
        client.post("/api/login", json={"unique_id": unique_id, "password": "not the password"})
    assert client.post("/api/password/forgot", json={"unique_id": unique_id}).json()["sent"] is True
    # An ID that does not exist gets the same answer, and no email.
    sent = len(mailbox.sent)
    assert client.post("/api/password/forgot", json={"unique_id": "DL-MD-9999"}).json()["sent"] is True
    assert len(mailbox.sent) == sent
    code = mailbox.code_for("asha@example.test")
    changed = client.post("/api/password/reset", json={"unique_id": unique_id, "code": code, "password": "a brand new password"})
    assert changed.json() == {"changed": True}
    # The new password works at once, lock and all; the old one no longer does.
    assert client.post("/api/login", json={"unique_id": unique_id, "password": "a brand new password"}).status_code == 200
    assert client.post("/api/login", json={"unique_id": unique_id, "password": "correct horse battery"}).status_code == 401


def test_the_admin_pages_need_the_administrator(client, mailbox, admin):
    unique_id = approved(client, mailbox, admin)
    assert client.get("/api/admin/accounts").status_code == 401
    assert client.post("/api/admin/login", json={"email": "admin@example.test", "password": "guess"}).status_code == 401
    # A signed-in person is not an administrator, and an administrator's token is not a person's.
    person = client.post("/api/login", json={"unique_id": unique_id, "password": "correct horse battery"}).json()["token"]
    assert client.get("/api/admin/accounts", headers={"Authorization": f"Bearer {person}"}).status_code == 401
    assert client.get("/api/me", headers=admin).status_code == 401
    assert client.get("/api/me", headers={"Authorization": "Bearer made.up.token"}).status_code == 401


def test_the_photo_is_kept_small_and_plain(client, mailbox, admin):
    approved(client, mailbox, admin)
    row = client.get("/api/admin/accounts", params={"status": "approved"}, headers=admin).json()[0]
    picture = client.get(f"/api/admin/accounts/{row['id']}/photo", headers=admin)
    assert picture.status_code == 200 and picture.content[:2] == b"\xff\xd8"
    assert client.get(f"/api/admin/accounts/{row['id']}/photo").status_code == 401
    assert len(picture.content) < len(photo()) * 4


def test_health_and_the_dashboard_page(client):
    assert client.get("/api/health").json()["status"] == "ok"
    for address in ("/", "/admin"):
        page = client.get(address)
        assert page.status_code == 200 and "DeskLess" in page.text
    assert client.get("/static/app.js").headers["content-type"].startswith("text/javascript")
    assert client.get("/static/app.css").status_code == 200
    assert client.get("/static/main.py").status_code == 404
