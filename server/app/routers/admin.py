"""The platform's administrator: signs in here, sees every company, and approves each managing director."""

import shutil
import time
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from .. import mailer, metrics, pulse, security, writer
from ..config import settings
from ..db import get_db
from ..models import APPROVED, CLOSED, PENDING_APPROVAL, REJECTED, Account, Company, Event, Issue, Report
from ..service import Actor, approve as approve_account, current_admin, get_mailer, lead_of, public, reject as reject_account, when

router = APIRouter(prefix="/api/admin", tags=["admin"])

ADMIN_HOURS = 12
_MAX_WRONG = 5
_LOCK_SECONDS = 10 * 60
# Wrong administrator passwords from each address since its last right one, and until when that
# address is turned away. Kept per address: on a public server one visitor's wrong guesses must not
# shut the administrator out.
_wrong: dict[str, dict] = {}


def _address(request: Request) -> str:
    """Where the request really comes from. Behind the site's web server that is the address it passes on."""
    return request.headers.get("x-real-ip") or (request.client.host if request.client else "")


class AdminLogin(BaseModel):
    email: str
    password: str


@router.post("/login")
def login(body: AdminLogin, request: Request) -> dict:
    config = settings()
    moment = time.time()
    # Addresses that have long since been let back in are forgotten.
    for address in [one for one, record in _wrong.items() if record["seen"] < moment - 2 * _LOCK_SECONDS]:
        del _wrong[address]
    record = _wrong.setdefault(_address(request), {"count": 0, "until": 0.0, "seen": moment})
    record["seen"] = moment
    if moment < record["until"]:
        minutes = max(1, int((record["until"] - moment) // 60) + 1)
        raise HTTPException(429, f"Too many wrong passwords. Try again in {minutes} minutes.")
    # A password copied from a terminal often brings a space along; that is not a wrong password.
    right = security.same(body.email.strip().lower(), config.admin_email) & security.same(body.password.strip(), config.admin_password.strip())
    if not right:
        record["count"] += 1
        if record["count"] >= _MAX_WRONG:
            record["count"] = 0
            record["until"] = moment + _LOCK_SECONDS
        raise HTTPException(401, "Wrong email or password.")
    _wrong.pop(_address(request), None)
    pulse.record("signin", pulse.ADMIN, pulse.SERVER, "the administrator signed in", device=pulse.device_of(request.headers.get("user-agent")))
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
    found = dict(db.execute(select(Issue.company_id, func.count()).group_by(Issue.company_id)).all())
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
                "issues": found.get(company.id, 0),
                "open_issues": unsettled.get(company.id, 0),
            }
        )
    return rows


def _folder_bytes(folder) -> int:
    """How much the files in a folder weigh; nothing if it is not there yet."""
    try:
        return sum(entry.stat().st_size for entry in folder.iterdir() if entry.is_file())
    except OSError:
        return 0


@router.get("/system")
def system(_: str = Depends(current_admin), db: Session = Depends(get_db)) -> dict:
    """The health of the server itself, for the live monitor: is it up, how busy, how full."""
    config = settings()
    asked = time.perf_counter()
    try:
        db.execute(text("SELECT 1"))
        database = {"ok": True, "ms": round((time.perf_counter() - asked) * 1000, 1)}
    except Exception:
        database = {"ok": False, "ms": 0}
    try:
        disk = shutil.disk_usage(config.data_dir)
        disk = {"total": disk.total, "free": disk.free}
    except OSError:
        disk = {"total": 0, "free": 0}
    return {
        "now": when(datetime.now(timezone.utc)),
        "started_at": when(datetime.fromtimestamp(metrics.STARTED, timezone.utc)),
        "uptime_seconds": int(time.time() - metrics.STARTED),
        "database": database,
        "mail": bool(config.smtp_host),
        "public_url": config.public_url,
        "ai": writer.status(),
        "storage": {
            "reports": _folder_bytes(config.reports_dir),
            "pictures": _folder_bytes(config.issues_dir),
            "photos": _folder_bytes(config.media_dir),
            "disk_total": disk["total"],
            "disk_free": disk["free"],
        },
        "rows": {
            "accounts": db.scalar(select(func.count()).select_from(Account)) or 0,
            "companies": db.scalar(select(func.count()).select_from(Company)) or 0,
            "reports": db.scalar(select(func.count()).select_from(Report)) or 0,
            "issues": db.scalar(select(func.count()).select_from(Issue)) or 0,
            "events": db.scalar(select(func.count()).select_from(Event)) or 0,
        },
        "last_report_at": when(db.scalar(select(func.max(Report.received_at)))),
        "requests": metrics.last_hour(),
    }
