"""
Signing in with the unique ID and password, the signed-in person's own details, a forgotten password, and
the one-time link with which the app opens the person's dashboard in a browser.
"""

import secrets
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import mailer, security
from ..config import settings
from ..db import get_db
from ..models import APPROVED, Account, Otp, now
from ..service import aware, current_account, get_mailer, profile, send_code, use_code
from .signup import MIN_PASSWORD

router = APIRouter(prefix="/api", tags=["auth"])

#: Wrong passwords in a row before sign-in is refused for a while.
MAX_FAILED_LOGINS = 5
LOCK_MINUTES = 15


class Login(BaseModel):
    unique_id: str
    password: str


def _by_unique_id(db: Session, unique_id: str) -> Account | None:
    return db.scalars(
        select(Account).where(func.upper(Account.unique_id) == unique_id.strip().upper(), Account.status == APPROVED)
    ).first()


@router.post("/login")
def login(body: Login, db: Session = Depends(get_db)) -> dict:
    """
    Signs in. The answer is a token the phone keeps and the person's details, so that the app can go on
    working with no network afterwards.
    """
    account = _by_unique_id(db, body.unique_id)
    wrong = HTTPException(401, "Wrong ID or password.")
    if account is None:
        # The same work is done as for a real account, so the time taken gives nothing away.
        security.check_password(body.password, security.hash_password("no such account"))
        raise wrong
    locked = aware(account.locked_until)
    if locked is not None and locked > now():
        minutes = max(1, int((locked - now()).total_seconds() // 60) + 1)
        raise HTTPException(429, f"Too many wrong passwords. Try again in {minutes} minutes, or reset the password.")
    if not security.check_password(body.password, account.password_hash):
        account.failed_logins += 1
        if account.failed_logins >= MAX_FAILED_LOGINS:
            account.failed_logins = 0
            account.locked_until = now() + timedelta(minutes=LOCK_MINUTES)
        db.commit()
        raise wrong
    account.failed_logins = 0
    account.locked_until = None
    db.commit()
    days = settings().token_days
    return {
        "token": security.make_token(account.id, "user", timedelta(days=days)),
        "token_days": days,
        "profile": profile(db, account),
    }


@router.get("/me")
def me(account: Account = Depends(current_account), db: Session = Depends(get_db)) -> dict:
    return profile(db, account)


@router.get("/me/photo")
def my_photo(account: Account = Depends(current_account)) -> FileResponse:
    path = settings().media_dir / account.photo
    if not path.is_file():
        raise HTTPException(404, "No photo.")
    return FileResponse(path, media_type="image/jpeg")


class Forgot(BaseModel):
    unique_id: str


class Reset(BaseModel):
    unique_id: str
    code: str
    password: str


@router.post("/password/forgot")
def forgot(body: Forgot, db: Session = Depends(get_db), post: mailer.Mailer = Depends(get_mailer)) -> dict:
    """Emails a code to the address the account was made with. The answer is the same whether or not the ID exists."""
    account = _by_unique_id(db, body.unique_id)
    if account is not None:
        try:
            send_code(db, post, account, "reset")
        except HTTPException as error:
            # Asked again too soon, or the mail server is down: those the person should hear about.
            if error.status_code in (429, 503):
                raise
    return {"sent": True, "code_minutes": settings().otp_minutes}


@router.post("/password/reset")
def reset(body: Reset, db: Session = Depends(get_db)) -> dict:
    if len(body.password) < MIN_PASSWORD:
        raise HTTPException(422, f"Choose a password of at least {MIN_PASSWORD} characters.")
    account = _by_unique_id(db, body.unique_id)
    if account is None:
        raise HTTPException(400, "Ask for a new code.")
    use_code(db, account, "reset", body.code)
    account.password_hash = security.hash_password(body.password)
    account.failed_logins = 0
    account.locked_until = None
    db.commit()
    return {"changed": True}


# ---------------------------------------------------------------- opening the dashboard from the app
#
# The app is already signed in. So that the person need not type their ID and password again in the
# browser, the app asks for a link that works once and for two minutes, and opens the dashboard with it.

WEB_LINK_SECONDS = 120
WEB_HOURS = 12


class WebLink(BaseModel):
    code: str


@router.post("/web-link")
def web_link(account: Account = Depends(current_account), db: Session = Depends(get_db)) -> dict:
    code = secrets.token_urlsafe(24)
    db.add(
        Otp(
            account_id=account.id,
            purpose="web",
            code_hash=security.keyed_hash("web", code),
            expires_at=now() + timedelta(seconds=WEB_LINK_SECONDS),
        )
    )
    db.commit()
    return {"code": code, "seconds": WEB_LINK_SECONDS}


@router.post("/web-link/open")
def open_web_link(body: WebLink, db: Session = Depends(get_db)) -> dict:
    """Trades the one-time link for a sign-in on the dashboard."""
    otp = db.scalars(
        select(Otp).where(Otp.purpose == "web", Otp.code_hash == security.keyed_hash("web", body.code), Otp.used_at.is_(None))
    ).first()
    account = db.get(Account, otp.account_id) if otp else None
    if otp is None or aware(otp.expires_at) < now() or account is None or account.status != APPROVED:
        raise HTTPException(401, "That link has run out. Sign in with your ID and password.")
    otp.used_at = now()
    db.commit()
    return {"token": security.make_token(account.id, "user", timedelta(hours=WEB_HOURS)), "profile": profile(db, account)}
