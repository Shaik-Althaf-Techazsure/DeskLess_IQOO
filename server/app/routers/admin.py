"""The platform's administrator: signs in here, sees every company, and approves each managing director."""

import time
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import mailer, security
from ..config import settings
from ..db import get_db
from ..models import APPROVED, CLOSED, PENDING_APPROVAL, REJECTED, Account, Company, Issue, Report
from ..service import Actor, approve as approve_account, current_admin, get_mailer, lead_of, public, reject as reject_account, when

router = APIRouter(prefix="/api/admin", tags=["admin"])

ADMIN_HOURS = 12
_MAX_WRONG = 5
_LOCK_SECONDS = 10 * 60
# Wrong administrator passwords since the last right one, and until when the door stays shut.
_wrong = {"count": 0, "until": 0.0}


class AdminLogin(BaseModel):
    email: str
    password: str


@router.post("/login")
def login(body: AdminLogin) -> dict:
    config = settings()
    if time.time() < _wrong["until"]:
        raise HTTPException(429, "Too many wrong passwords. Try again in a few minutes.")
    right = security.same(body.email.strip().lower(), config.admin_email) & security.same(body.password, config.admin_password)
    if not right:
        _wrong["count"] += 1
        if _wrong["count"] >= _MAX_WRONG:
            _wrong["count"] = 0
            _wrong["until"] = time.time() + _LOCK_SECONDS
        raise HTTPException(401, "Wrong email or password.")
    _wrong["count"] = 0
    return {"token": security.make_token(config.admin_email, "admin", timedelta(hours=ADMIN_HOURS)), "email": config.admin_email}


def account_row(account: Account, lead: Account | None = None) -> dict:
    """An account as the people above it see it in a list."""
    return public(account, lead) | {
        "id": account.id,
        "created_at": when(account.created_at),
        "email_verified_at": when(account.email_verified_at),
        "decided_at": when(account.decided_at),
        "reject_reason": account.reject_reason,
    }


@router.get("/summary")
def summary(_: str = Depends(current_admin), db: Session = Depends(get_db)) -> dict:
    """How many accounts stand at each stage."""
    counts = dict(db.execute(select(Account.status, func.count()).group_by(Account.status)).all())
    return {status: counts.get(status, 0) for status in (PENDING_APPROVAL, APPROVED, REJECTED)}


@router.get("/accounts")
def accounts(status: str = PENDING_APPROVAL, _: str = Depends(current_admin), db: Session = Depends(get_db)) -> list[dict]:
    """Accounts at one stage. Those waiting come oldest first, so nobody is left at the back of the queue."""
    if status not in (PENDING_APPROVAL, APPROVED, REJECTED):
        raise HTTPException(422, "Unknown status.")
    order = Account.email_verified_at.asc() if status == PENDING_APPROVAL else Account.decided_at.desc()
    return [account_row(account, lead_of(db, account)) for account in db.scalars(select(Account).where(Account.status == status).order_by(order))]


def _account(db: Session, account_id: str) -> Account:
    account = db.get(Account, account_id)
    if account is None:
        raise HTTPException(404, "No such account.")
    return account


@router.get("/accounts/{account_id}/photo")
def photo(account_id: str, _: str = Depends(current_admin), db: Session = Depends(get_db)) -> FileResponse:
    path = settings().media_dir / _account(db, account_id).photo
    if not path.is_file():
        raise HTTPException(404, "No photo.")
    return FileResponse(path, media_type="image/jpeg")


@router.post("/accounts/{account_id}/approve")
def approve(
    account_id: str,
    admin: str = Depends(current_admin),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    account = _account(db, account_id)
    emailed = approve_account(db, post, account, Actor(admin=admin))
    return account_row(account) | {"emailed": emailed}


class Reject(BaseModel):
    reason: str = ""


@router.post("/accounts/{account_id}/reject")
def reject(
    account_id: str,
    body: Reject,
    admin: str = Depends(current_admin),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    account = _account(db, account_id)
    emailed = reject_account(db, post, account, Actor(admin=admin), body.reason)
    return account_row(account) | {"emailed": emailed}


@router.get("/companies")
def companies(_: str = Depends(current_admin), db: Session = Depends(get_db)) -> list[dict]:
    """Every company on the platform, with who leads it and how much is going on in it."""
    people = dict(db.execute(select(Account.company_id, func.count()).where(Account.status == APPROVED).group_by(Account.company_id)).all())
    reports = dict(db.execute(select(Report.company_id, func.count()).group_by(Report.company_id)).all())
    unsettled = dict(db.execute(select(Issue.company_id, func.count()).where(Issue.status != CLOSED).group_by(Issue.company_id)).all())
    leaders = {
        account.company_id: account
        for account in db.scalars(select(Account).where(Account.role == "md", Account.status == APPROVED))
    }
    rows = []
    for company in db.scalars(select(Company).order_by(Company.created_at.desc())):
        leader = leaders.get(company.id)
        rows.append(
            {
                "id": company.id,
                "name": company.name,
                "created_at": when(company.created_at),
                "managing_director": leader.name if leader else "",
                "managing_director_id": leader.unique_id if leader else "",
                "people": people.get(company.id, 0),
                "reports": reports.get(company.id, 0),
                "open_issues": unsettled.get(company.id, 0),
            }
        )
    return rows
