"""Signing up: the form, the code sent to the email address, and the wait for the person above to approve."""

import re
import secrets

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import mailer, security
from ..config import settings
from ..db import get_db
from ..models import APPROVED, EMAIL_PENDING, LEADS, PENDING_APPROVAL, REJECTED, ROLE_NAMES, ROLES, Account, now
from ..pictures import save_photo
from ..service import approver_words, get_mailer, lead_of, send_code, use_code

router = APIRouter(prefix="/api/signup", tags=["signup"])

_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]{2,}$")
MAX_PHOTO_BYTES = 6 * 1024 * 1024
PHOTO_EDGE = 900
MIN_PASSWORD = 8


def clean_phone(typed: str) -> str | None:
    """An Indian mobile number as ten digits, however it was typed (+91, spaces, dashes), or None."""
    digits = re.sub(r"[\s\-()]", "", typed)
    digits = digits.removeprefix("+91").removeprefix("0")
    if len(digits) == 12 and digits.startswith("91"):
        digits = digits[2:]
    return digits if re.fullmatch(r"[6-9]\d{9}", digits) else None


def _lead(db: Session, role: str, typed: str) -> Account:
    """
    The person a code belongs to: the one the newcomer will answer to, and who approves the sign-up.
    A manager joins with the managing director's code; an inspector or a technician with their manager's
    (or, in a company with no managers, the managing director's).
    """
    code = security.clean_invite(typed)
    lead = db.scalars(
        select(Account).where(Account.invite_code == code, Account.status == APPROVED, Account.role.in_(LEADS))
    ).first() if code else None
    if lead is None or lead.company_id is None:
        raise HTTPException(422, "That code is not known. Check it with the person who gave it to you.")
    if role == "manager" and lead.role != "md":
        raise HTTPException(422, "A manager signs up with the company code from the managing director.")
    return lead


@router.get("/invite")
def invite(code: str, role: str = "inspector", db: Session = Depends(get_db)) -> dict:
    """Whose code this is, so the form can show which company and person the newcomer is about to join."""
    lead = _lead(db, role.strip().lower(), code)
    return {"company": lead.organisation, "lead": lead.name, "lead_role": ROLE_NAMES[lead.role]}


@router.post("")
async def sign_up(
    role: str = Form(...),
    name: str = Form(...),
    email: str = Form(...),
    phone: str = Form(...),
    aadhaar: str = Form(...),
    password: str = Form(...),
    organisation: str = Form(""),
    invite: str = Form(""),
    photo: UploadFile = File(...),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    """
    Takes the sign-up form and emails a code to the address given. Returns a ticket the phone keeps to ask
    how the sign-up is doing; it is the only thing that lets anyone see that before signing in.
    """
    role = role.strip().lower()
    # What the first version of the app called a managing director.
    role = "md" if role == "employer" else role
    name = " ".join(name.split())
    email = email.strip().lower()
    organisation = " ".join(organisation.split())
    if role not in ROLES:
        raise HTTPException(422, "Choose Managing Director, Manager, Inspector or Technical.")
    if len(name) < 2 or len(name) > 120:
        raise HTTPException(422, "Enter your full name.")
    if not _EMAIL.fullmatch(email) or len(email) > 254:
        raise HTTPException(422, "That email address does not look right.")
    mobile = clean_phone(phone)
    if mobile is None:
        raise HTTPException(422, "Enter a 10-digit mobile number.")
    number = security.clean_aadhaar(aadhaar)
    if number is None:
        raise HTTPException(422, "That Aadhaar number is not valid. Check the 12 digits.")
    if len(password) < MIN_PASSWORD:
        raise HTTPException(422, f"Choose a password of at least {MIN_PASSWORD} characters.")
    lead = None
    if role == "md":
        # A managing director brings a company; everyone else joins one.
        if len(organisation) < 2 or len(organisation) > 160:
            raise HTTPException(422, "Enter your company's name.")
    else:
        if not invite.strip():
            raise HTTPException(422, "Enter the code from the person you report to. They find it on their dashboard.")
        lead = _lead(db, role, invite)
        organisation = lead.organisation
    raw = await photo.read(MAX_PHOTO_BYTES + 1)
    if not raw:
        raise HTTPException(422, "Add a photo of yourself.")
    if len(raw) > MAX_PHOTO_BYTES:
        raise HTTPException(422, "That photo is too large. Use one under 6 MB.")

    fingerprint = security.aadhaar_fingerprint(number)
    live = (PENDING_APPROVAL, APPROVED)
    if db.scalars(select(Account).where(Account.email == email, Account.status.in_(live))).first():
        raise HTTPException(409, "An account with this email address already exists. Sign in instead.")
    if db.scalars(select(Account).where(Account.aadhaar_fingerprint == fingerprint, Account.status.in_(live))).first():
        raise HTTPException(409, "An account with this Aadhaar number already exists.")
    # A sign-up that never got past the code is simply started again.
    for stale in db.scalars(select(Account).where(Account.email == email, Account.status == EMAIL_PENDING)):
        (settings().media_dir / stale.photo).unlink(missing_ok=True)
        db.delete(stale)
    db.flush()

    ticket = secrets.token_urlsafe(24)
    account = Account(
        role=role,
        name=name,
        email=email,
        phone=mobile,
        organisation=organisation,
        company_id=lead.company_id if lead else None,
        reports_to=lead.id if lead else None,
        aadhaar_sealed=security.seal_aadhaar(number),
        aadhaar_last4=number[-4:],
        aadhaar_fingerprint=fingerprint,
        photo="",
        password_hash=security.hash_password(password),
        ticket_hash=security.keyed_hash("ticket", ticket),
    )
    db.add(account)
    db.flush()
    account.photo = save_photo(raw, settings().media_dir, f"{account.id}.jpg", PHOTO_EDGE)
    db.commit()
    send_code(db, post, account, "signup")
    return {"email": email, "ticket": ticket, "code_minutes": settings().otp_minutes}


class Verify(BaseModel):
    email: str
    code: str


class Email(BaseModel):
    email: str


def _pending(db: Session, email: str) -> Account:
    account = db.scalars(
        select(Account).where(Account.email == email.strip().lower(), Account.status == EMAIL_PENDING)
    ).first()
    if account is None:
        raise HTTPException(404, "No sign-up is waiting for a code at this email address.")
    return account


@router.post("/verify")
def verify(body: Verify, db: Session = Depends(get_db), post: mailer.Mailer = Depends(get_mailer)) -> dict:
    """Confirms the email address with the code. The sign-up then waits for the person above."""
    account = _pending(db, body.email)
    use_code(db, account, "signup", body.code)
    account.status = PENDING_APPROVAL
    account.email_verified_at = now()
    db.commit()
    lead = lead_of(db, account)
    # Telling people is a courtesy: a mail server that is down must not undo a confirmed sign-up.
    for to, (subject, text) in (
        (account.email, mailer.waiting_email(account.name, approver_words(db, account))),
        (lead.email if lead else settings().admin_email, mailer.new_signup_email(account.name, ROLE_NAMES[account.role], account.email)),
    ):
        try:
            post.send(to, subject, text)
        except mailer.MailError:
            pass
    return {"status": account.status}


@router.post("/resend")
def resend(body: Email, db: Session = Depends(get_db), post: mailer.Mailer = Depends(get_mailer)) -> dict:
    send_code(db, post, _pending(db, body.email), "signup")
    return {"code_minutes": settings().otp_minutes}


@router.get("/status")
def status(email: str, ticket: str, db: Session = Depends(get_db)) -> dict:
    """How a sign-up is doing, for the phone that made it. The newest sign-up at that address answers."""
    wanted = security.keyed_hash("ticket", ticket)
    account = db.scalars(
        select(Account).where(Account.email == email.strip().lower(), Account.ticket_hash == wanted)
    ).first()
    if account is None:
        raise HTTPException(404, "This sign-up is not known. Sign up again.")
    answer = {
        "status": account.status,
        "name": account.name,
        "role": account.role,
        "role_name": ROLE_NAMES.get(account.role, account.role.title()),
        "company": account.organisation,
        "approver": approver_words(db, account),
    }
    if account.status == APPROVED:
        answer["unique_id"] = account.unique_id
    if account.status == REJECTED:
        answer["reason"] = account.reject_reason
    return answer
