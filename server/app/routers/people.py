"""The people a signed-in person is above: who they are, who is waiting to join, and the deciding of that."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import mailer
from ..config import settings
from ..db import get_db
from ..models import APPROVED, CLOSED, PENDING_APPROVAL, REJECTED, ROLE_NAMES, SUSPENDED, Account, Issue, Report
from ..service import Actor, approve, current_actor, get_mailer, may_decide, names_of, note, reject, sees_people, waiting_for, when
from .admin import account_row

router = APIRouter(prefix="/api/people", tags=["people"])


@router.get("")
def people(status: str = APPROVED, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> list[dict]:
    """
    The people in view at one stage. For those waiting, only the sign-ups this person is the one to decide,
    oldest first. For those approved, with how much each has on their hands.
    """
    if status not in (PENDING_APPROVAL, APPROVED, REJECTED, SUSPENDED):
        raise HTTPException(422, "Unknown status.")
    if status == PENDING_APPROVAL:
        query = select(Account).where(waiting_for(actor)).order_by(Account.email_verified_at.asc())
    else:
        order = Account.name.asc() if status == APPROVED else Account.decided_at.desc()
        query = select(Account).where(Account.status == status, sees_people(actor)).order_by(order)
    found = list(db.scalars(query))
    leads = names_of(db, [account.reports_to for account in found])
    ids = [account.id for account in found]
    work = dict(
        db.execute(
            select(Issue.assigned_to, func.count()).where(Issue.assigned_to.in_(ids), Issue.status != CLOSED).group_by(Issue.assigned_to)
        ).all()
    ) if ids else {}
    sent = dict(db.execute(select(Report.account_id, func.count()).where(Report.account_id.in_(ids)).group_by(Report.account_id)).all()) if ids else {}
    team = dict(
        db.execute(select(Account.reports_to, func.count()).where(Account.reports_to.in_(ids), Account.status == APPROVED).group_by(Account.reports_to)).all()
    ) if ids else {}
    return [
        account_row(account)
        | {
            "reports_to": leads.get(account.reports_to, ""),
            "open_work": work.get(account.id, 0),
            "reports": sent.get(account.id, 0),
            "team": team.get(account.id, 0),
            "last_seen_at": when(account.last_seen_at),
            "may_decide": may_decide(actor, account),
            "me": (not actor.is_admin) and account.id == actor.account.id,
        }
        for account in found
    ]


def _in_view(db: Session, actor: Actor, account_id: str) -> Account:
    account = db.scalars(select(Account).where(Account.id == account_id, sees_people(actor))).first()
    if account is None:
        raise HTTPException(404, "No such person.")
    return account


@router.get("/{account_id}/photo")
def photo(account_id: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> FileResponse:
    path = settings().media_dir / _in_view(db, actor, account_id).photo
    if not path.is_file():
        raise HTTPException(404, "No photo.")
    return FileResponse(path, media_type="image/jpeg")


@router.post("/{account_id}/approve")
def approve_person(
    account_id: str,
    actor: Actor = Depends(current_actor),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    account = _in_view(db, actor, account_id)
    emailed = approve(db, post, account, actor)
    return account_row(account) | {"emailed": emailed}


class Reject(BaseModel):
    reason: str = ""


@router.post("/{account_id}/reject")
def reject_person(
    account_id: str,
    body: Reject,
    actor: Actor = Depends(current_actor),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    account = _in_view(db, actor, account_id)
    emailed = reject(db, post, account, actor, body.reason)
    return account_row(account) | {"emailed": emailed}


@router.post("/{account_id}/suspend")
def suspend_person(account_id: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """Shuts an approved account out: its sign-ins stop working at once. Nothing of its work is removed."""
    account = _in_view(db, actor, account_id)
    if not may_decide(actor, account):
        raise HTTPException(403, "This account is for someone else to decide.")
    if account.status != APPROVED:
        raise HTTPException(409, "Only an approved account can be suspended.")
    account.status = SUSPENDED
    note(db, "suspended", actor.name, f"{account.name} ({ROLE_NAMES.get(account.role, account.role)}) was suspended by {actor.name}",
         company_id=account.company_id, author_id=account.id)
    db.commit()
    return account_row(account)


@router.post("/{account_id}/reactivate")
def reactivate_person(account_id: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """Lets a suspended account back in, with the ID and password it had."""
    account = _in_view(db, actor, account_id)
    if not may_decide(actor, account):
        raise HTTPException(403, "This account is for someone else to decide.")
    if account.status != SUSPENDED:
        raise HTTPException(409, "This account is not suspended.")
    account.status = APPROVED
    note(db, "joined", actor.name, f"{account.name} was let back in by {actor.name}", company_id=account.company_id, author_id=account.id)
    db.commit()
    return account_row(account)
