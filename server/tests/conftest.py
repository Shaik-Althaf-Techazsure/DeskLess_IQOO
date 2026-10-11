"""Each test gets an empty database in a temporary folder and a mailbox that keeps what would have been sent."""

import base64
import io
import json
import os
import re
import tempfile
import time
from types import SimpleNamespace

import pytest

_folder = tempfile.mkdtemp(prefix="deskless-accounts-")
os.environ.update(
    DATA_DIR=_folder,
    DATABASE_URL=f"sqlite:///{_folder}/test.db",
    SECRET_KEY="test-secret-key-for-signing-only",
    AADHAAR_KEY=base64.b64encode(bytes(range(32))).decode(),
    ADMIN_EMAIL="admin@example.test",
    ADMIN_PASSWORD="admin-test-password",
    SMTP_HOST="",
    # As on a real server: the site's own web server puts this one under a path on its domain.
    PUBLIC_URL="https://site.example.test/competitions/IQOO",
    # The assistant's own rounds are made by hand in the tests, not by a clock beside them.
    AUTOPILOT_EVERY_SECONDS="0",
)

from fastapi.testclient import TestClient  # noqa: E402
from PIL import Image  # noqa: E402

from app import security, service  # noqa: E402
from app.db import Base, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.routers import admin as admin_router  # noqa: E402

PASSWORD = "correct horse battery"
PDF = b"%PDF-1.4\n% a stand-in for a report\n%%EOF\n"


class Mailbox:
    """Stands in for the mail server. What was sent can be read back, as a person reads their inbox."""

    def __init__(self):
        self.sent: list[tuple[str, str, str]] = []
        #: The laid-out version and the reply address of the newest email to each address, where it had them.
        self.laid_out: dict[str, str] = {}
        self.reply_to: dict[str, str] = {}
        #: The files that came with it: each a name and its bytes.
        self.files: dict[str, tuple] = {}

    def send(self, to: str, subject: str, text: str, html: str = "", reply_to: str = "", files: tuple = ()) -> None:
        self.sent.append((to, subject, text))
        self.laid_out[to], self.reply_to[to], self.files[to] = html, reply_to, files

    def last_to(self, to: str) -> tuple[str, str]:
        return next((subject, text) for address, subject, text in reversed(self.sent) if address == to)

    def code_for(self, to: str) -> str:
        """The six-digit code in the newest email to this address."""
        return re.search(r"\b(\d{6})\b", self.last_to(to)[1]).group(1)


@pytest.fixture
def mailbox():
    return Mailbox()


@pytest.fixture
def client(mailbox, monkeypatch):
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    app.state.mailer = mailbox
    admin_router._wrong.clear()
    # Codes may be asked for again at once in tests; the wait has a test of its own.
    monkeypatch.setattr(service, "RESEND_SECONDS", 0)
    with TestClient(app) as test_client:
        yield test_client


def aadhaar(n: int = 1) -> str:
    """A made-up number in the 9999 range kept for testing, with a correct check digit."""
    base = f"9999{n:07d}"
    return base + security.verhoeff_digit(base)


def photo() -> bytes:
    picture = io.BytesIO()
    Image.new("RGB", (640, 480), (200, 160, 40)).save(picture, "JPEG")
    return picture.getvalue()


def form(**changes) -> dict:
    """A sign-up form that is in order: a managing director bringing their company. A test changes what it is about."""
    return {
        "role": "md",
        "name": "Asha Verma",
        "email": "asha@example.test",
        "phone": "+91 98765 43210",
        "aadhaar": aadhaar(1),
        "password": PASSWORD,
        "organisation": "Green Valley Builders",
    } | changes


def sign_up(client, **changes):
    return client.post("/api/signup", data=form(**changes), files={"photo": ("me.jpg", photo(), "image/jpeg")})


@pytest.fixture
def admin(client) -> dict:
    """The header an administrator's requests carry."""
    token = client.post("/api/admin/login", json={"email": "admin@example.test", "password": "admin-test-password"}).json()["token"]
    return {"Authorization": f"Bearer {token}"}


def approved(client, mailbox, admin, **changes) -> str:
    """Takes a person all the way through sign-up and approval by the administrator. Returns their unique ID."""
    details = form(**changes)
    assert sign_up(client, **changes).status_code == 200
    assert client.post("/api/signup/verify", json={"email": details["email"], "code": mailbox.code_for(details["email"])}).status_code == 200
    waiting = client.get("/api/admin/accounts", headers=admin).json()
    account = next(row for row in waiting if row["email"] == details["email"])
    return client.post(f"/api/admin/accounts/{account['id']}/approve", headers=admin).json()["unique_id"]


def signed_in(client, unique_id: str, password: str = PASSWORD) -> dict:
    """The header a signed-in person's requests carry."""
    answer = client.post("/api/login", json={"unique_id": unique_id, "password": password})
    assert answer.status_code == 200, answer.text
    return {"Authorization": f"Bearer {answer.json()['token']}"}


_joined = iter(range(100, 10_000))


def join(client, mailbox, lead: dict, role: str, name: str) -> SimpleNamespace:
    """
    Someone signs up with their lead's code, confirms their email address, and the lead approves them.
    Returns who they are and the header their requests carry.
    """
    email = name.lower().replace(" ", ".") + "@example.test"
    code = client.get("/api/me", headers=lead).json()["invite_code"]
    answer = sign_up(client, role=role, name=name, email=email, aadhaar=aadhaar(next(_joined)), organisation="", invite=code)
    assert answer.status_code == 200, answer.text
    assert client.post("/api/signup/verify", json={"email": email, "code": mailbox.code_for(email)}).status_code == 200
    waiting = next(row for row in client.get("/api/people", params={"status": "pending_approval"}, headers=lead).json() if row["email"] == email)
    decided = client.post(f"/api/people/{waiting['id']}/approve", headers=lead)
    assert decided.status_code == 200, decided.text
    unique_id = decided.json()["unique_id"]
    return SimpleNamespace(id=waiting["id"], unique_id=unique_id, name=name, email=email, headers=signed_in(client, unique_id))


@pytest.fixture
def company(client, mailbox, admin) -> SimpleNamespace:
    """A company with one of each: a managing director, a manager under them, an inspector and a technician under the manager."""
    md_id = approved(client, mailbox, admin)
    md = SimpleNamespace(unique_id=md_id, name="Asha Verma", email="asha@example.test", headers=signed_in(client, md_id))
    manager = join(client, mailbox, md.headers, "manager", "Ravi Khan")
    inspector = join(client, mailbox, manager.headers, "inspector", "Meena Iyer")
    technical = join(client, mailbox, manager.headers, "technical", "Arjun Das")
    return SimpleNamespace(md=md, manager=manager, inspector=inspector, technical=technical)


def issue(client_id: str, **changes) -> dict:
    """One issue as the phone describes it in a report."""
    return {
        "id": client_id, "title": "Cracked beam", "note": "There is a crack in the beam on the second floor.",
        "location": "Second floor", "category": "Structural", "severity": "HIGH", "owner": "", "due": "",
        "check": "MATCH", "seen": "A concrete beam with a crack.", "measured": "", "lat": 12.97, "lon": 77.59,
        "captured_at": int(time.time() * 1000), "resolved": False, "photo": f"{client_id}.jpg", "fix_photo": "",
    } | changes


def send_report(client, headers: dict, issues: list[dict] | None = None, built: bool = True, **about):
    """
    A phone sends a walk: its details and a picture for each issue, with the PDF when the report has been
    built, and without it while the walk is still going on.
    """
    issues = [issue("a1"), issue("a2", title="Loose cable", severity="LOW", category="Electrical")] if issues is None else issues
    meta = {
        "walk": "1001", "file": "DeskLess-tower-b-20261010-1130.pdf", "template": "SITE", "title": "Site inspection report",
        "place": "Tower B", "person": "Meena Iyer", "built_at": int(time.time() * 1000), "review": "GENUINE",
        "review_reason": "The notes and pictures agree.", "signed": True, "issues": issues,
    } | about
    files = [("pdf", ("report.pdf", PDF, "application/pdf"))] if built else []
    for item in issues:
        for key in ("photo", "fix_photo"):
            if item.get(key):
                files.append(("photos", (item[key], photo(), "image/jpeg")))
    # With no file at all the form still has to go as a form with parts, as the app sends it.
    return client.post("/api/reports", data={"meta": json.dumps(meta)}, files=files or {"photos": ("", b"", "image/jpeg")}, headers=headers)
