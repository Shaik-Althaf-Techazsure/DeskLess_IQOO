"""
What the routers share: who is asking and what they may see, what an account looks like from outside,
one-time codes, and the deciding of a sign-up.
"""

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

from fastapi import Depends, Header, HTTPException, Request
from sqlalchemy import ColumnElement, false, or_, select, true
from sqlalchemy.orm import Session

from . import mailer, security
from .config import settings
from .db import get_db
from .models import APPROVED, LEADS, PENDING_APPROVAL, REJECTED, ROLE_NAMES, ROLES, Account, Company, Counter, Event, Issue, Message, Otp, Report, now

#: Wrong codes allowed before a new one has to be asked for, and the wait between two emails.
MAX_CODE_TRIES = 5
RESEND_SECONDS = 60


def get_mailer(request: Request) -> mailer.Mailer:
    return request.app.state.mailer


def aware(moment: datetime | None) -> datetime | None:
    """SQLite hands times back without their zone; they were stored in UTC."""
    if moment is not None and moment.tzinfo is None:
        return moment.replace(tzinfo=timezone.utc)
    return moment


def when(moment: datetime | None) -> str | None:
    """A time with its zone, so a page can show it in the reader's own time."""
    return aware(moment).isoformat() if moment else None


def today() -> date:
    """The date where the company works, which is what a deadline is measured against."""
    return (now() + timedelta(minutes=settings().utc_offset_minutes)).date()


# ---------------------------------------------------------------- who is asking


@dataclass(frozen=True)
class Actor:
    """Who is asking: a signed-in person, or the platform's administrator."""

    account: Account | None = None
    admin: str = ""

    @property
    def is_admin(self) -> bool:
        return self.account is None

    @property
    def role(self) -> str:
        return "admin" if self.account is None else self.account.role

    @property
    def name(self) -> str:
        return "Administrator" if self.account is None else self.account.name

    @property
    def label(self) -> str:
        """How this person is written down beside something they decided."""
        return self.admin if self.account is None else f"{self.account.name} ({self.account.unique_id})"


def _bearer(authorization: str | None) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(401, "Sign in first.")
    return authorization[7:].strip()


#: How often, at most, it is written down that somebody is using DeskLess.
SEEN_EVERY_SECONDS = 60


def _seen(db: Session, account: Account) -> None:
    """Notes that this person is here now, so the dashboards can show who is online."""
    last = aware(account.last_seen_at)
    if last is None or (now() - last).total_seconds() >= SEEN_EVERY_SECONDS:
        account.last_seen_at = now()
        db.commit()


def current_account(authorization: str | None = Header(None), db: Session = Depends(get_db)) -> Account:
    """The signed-in person. An account that has since been rejected or suspended is turned away."""
    account_id = security.read_token(_bearer(authorization), "user")
    account = db.get(Account, account_id) if account_id else None
    if account is None or account.status != APPROVED:
        raise HTTPException(401, "Sign in again.")
    _seen(db, account)
    return account


def current_admin(authorization: str | None = Header(None)) -> str:
    admin = security.read_token(_bearer(authorization), "admin")
    if admin is None:
        raise HTTPException(401, "Sign in as the administrator.")
    return admin


def current_actor(authorization: str | None = Header(None), db: Session = Depends(get_db)) -> Actor:
    """Whoever is signed in, person or administrator. The dashboards serve both from the same calls."""
    token = _bearer(authorization)
    admin = security.read_token(token, "admin")
    if admin is not None:
        return Actor(admin=admin)
    account_id = security.read_token(token, "user")
    account = db.get(Account, account_id) if account_id else None
    if account is None or account.status != APPROVED:
        raise HTTPException(401, "Sign in again.")
    _seen(db, account)
    return Actor(account=account)


# ---------------------------------------------------------------- what each may see
#
# The administrator sees everything. A managing director sees their company. A manager sees the people who
# answer to them and those people's work. Everyone else sees their own: an inspector the reports they made,
# a technician the issues they were given. Whoever is sent an issue with a note sees that issue too.


def team_of(db: Session, account: Account) -> list[str]:
    """The manager and the people who answer to them."""
    return [account.id, *db.scalars(select(Account.id).where(Account.reports_to == account.id))]


def _whole_company(actor: Actor) -> bool:
    return actor.role == "md" and actor.account.company_id is not None


def sees_people(actor: Actor) -> ColumnElement[bool]:
    if actor.is_admin:
        return true()
    me = actor.account
    if _whole_company(actor):
        return Account.company_id == me.company_id
    if me.role == "manager":
        return or_(Account.reports_to == me.id, Account.id == me.id)
    return Account.id == me.id


def sees_reports(db: Session, actor: Actor) -> ColumnElement[bool]:
    if actor.is_admin:
        return true()
    me = actor.account
    if _whole_company(actor):
        return Report.company_id == me.company_id
    if me.role == "manager":
        return Report.account_id.in_(team_of(db, me))
    return Report.account_id == me.id


def sees_issues(db: Session, actor: Actor) -> ColumnElement[bool]:
    if actor.is_admin:
        return true()
    me = actor.account
    if _whole_company(actor):
        return Issue.company_id == me.company_id
    mine = team_of(db, me) if me.role == "manager" else [me.id]
    # Also an issue somebody sent to this person by name, whoever raised it.
    sent_to_me = select(Message.issue_id).where(Message.to_id == me.id)
    return or_(Issue.author_id.in_(mine), Issue.assigned_to.in_(mine), Issue.id.in_(sent_to_me))


def sees_events(db: Session, actor: Actor) -> ColumnElement[bool]:
    if actor.is_admin:
        return true()
    me = actor.account
    if _whole_company(actor):
        return Event.company_id == me.company_id
    mine = team_of(db, me) if me.role == "manager" else [me.id]
    return or_(Event.author_id.in_(mine), Event.assignee_id.in_(mine))


def may_decide(actor: Actor, account: Account) -> bool:
    """
    Whether this person may approve or reject that sign-up. The level above decides: the administrator a
    managing director, a managing director anyone in their company, a manager those who answer to them.
    The administrator may decide any.
    """
    if actor.is_admin:
        return True
    me = actor.account
    if account.id == me.id:
        return False
    if _whole_company(actor):
        return account.company_id == me.company_id
    return me.role == "manager" and account.reports_to == me.id


def waiting_for(actor: Actor) -> ColumnElement[bool]:
    """The sign-ups this person is the one to decide."""
    if actor.is_admin:
        return Account.status == PENDING_APPROVAL
    me = actor.account
    if _whole_company(actor):
        return (Account.status == PENDING_APPROVAL) & (Account.company_id == me.company_id) & (Account.id != me.id)
    if me.role == "manager":
        return (Account.status == PENDING_APPROVAL) & (Account.reports_to == me.id)
    return false()


def may_direct(actor: Actor) -> bool:
    """Whether this person hands out work and checks it: a managing director or a manager in a company."""
    return actor.role in LEADS and actor.account.company_id is not None


# ---------------------------------------------------------------- an account from outside


def lead_of(db: Session, account: Account) -> Account | None:
    return db.get(Account, account.reports_to) if account.reports_to else None


def approver_words(db: Session, account: Account) -> str:
    """Who is to decide this sign-up, as the waiting person is told."""
    lead = lead_of(db, account)
    return f"{lead.name}, your {ROLE_NAMES.get(lead.role, lead.role).lower()}" if lead else "the DeskLess administrator"


def public(account: Account, lead: Account | None = None) -> dict:
    """An account as it may be shown. The Aadhaar number appears only with its first eight digits hidden."""
    return {
        "unique_id": account.unique_id,
        "role": account.role,
        "role_name": ROLE_NAMES.get(account.role, account.role.title()),
        "name": account.name,
        "email": account.email,
        "phone": account.phone,
        "organisation": account.organisation,
        "aadhaar": security.masked(account.aadhaar_last4) if account.aadhaar_last4 else "",
        "status": account.status,
        "reports_to": lead.name if lead else "",
        "invite_code": security.shown_invite(account.invite_code),
    }


def profile(db: Session, account: Account) -> dict:
    return public(account, lead_of(db, account))


# ---------------------------------------------------------------- one-time codes


def send_code(db: Session, post: mailer.Mailer, account: Account, purpose: str) -> None:
    """Makes a new one-time code, puts any earlier one out of use, and emails it."""
    last = db.scalars(
        select(Otp).where(Otp.account_id == account.id, Otp.purpose == purpose).order_by(Otp.id.desc()).limit(1)
    ).first()
    if last is not None and (now() - aware(last.sent_at)).total_seconds() < RESEND_SECONDS:
        raise HTTPException(429, "A code was sent a moment ago. Wait a minute before asking for another.")
    for old in db.scalars(select(Otp).where(Otp.account_id == account.id, Otp.purpose == purpose, Otp.used_at.is_(None))):
        old.used_at = now()
    code = security.new_code()
    minutes = settings().otp_minutes
    db.add(
        Otp(
            account_id=account.id,
            purpose=purpose,
            code_hash=security.keyed_hash(account.id, purpose, code),
            expires_at=now() + timedelta(minutes=minutes),
        )
    )
    db.commit()
    subject, text = (mailer.reset_email if purpose == "reset" else mailer.code_email)(account.name, code, minutes)
    try:
        post.send(account.email, subject, text)
    except mailer.MailError:
        raise HTTPException(503, "The code could not be emailed just now. Try again in a minute.")


def use_code(db: Session, account: Account, purpose: str, code: str) -> None:
    """Checks a typed code against the latest one sent. Raises with what to tell the person if it fails."""
    otp = db.scalars(
        select(Otp)
        .where(Otp.account_id == account.id, Otp.purpose == purpose, Otp.used_at.is_(None))
        .order_by(Otp.id.desc())
        .limit(1)
    ).first()
    if otp is None:
        raise HTTPException(400, "Ask for a new code.")
    if aware(otp.expires_at) < now():
        raise HTTPException(400, "That code has run out. Ask for a new one.")
    if otp.attempts >= MAX_CODE_TRIES:
        raise HTTPException(429, "Too many wrong tries. Ask for a new code.")
    if not security.same(otp.code_hash, security.keyed_hash(account.id, purpose, code.strip())):
        otp.attempts += 1
        db.commit()
        left = MAX_CODE_TRIES - otp.attempts
        raise HTTPException(400, f"That is not the code. {left} {'try' if left == 1 else 'tries'} left." if left else "Too many wrong tries. Ask for a new code.")
    otp.used_at = now()
    db.commit()


# ---------------------------------------------------------------- deciding a sign-up


def next_unique_id(db: Session, role: str) -> str:
    """The next ID for this kind of account: DL-INS-0001, DL-INS-0002 and so on. Each number is given once."""
    counter = db.scalars(select(Counter).where(Counter.role == role).with_for_update()).first()
    if counter is None:
        counter = Counter(role=role, last=0)
        db.add(counter)
    counter.last += 1
    return f"DL-{ROLES[role]}-{counter.last:04d}"


def fresh_invite(db: Session) -> str:
    while True:
        code = security.new_invite()
        if db.scalars(select(Account.id).where(Account.invite_code == code)).first() is None:
            return code


def note(db: Session, kind: str, actor: str, text: str, **about) -> None:
    """Writes down something that happened, for the history of an issue and the dashboards."""
    db.add(Event(kind=kind, actor=actor, text=text, **about))


def approve(db: Session, post: mailer.Mailer, account: Account, by: Actor) -> bool:
    """
    Approves a sign-up. That gives the unique ID; a managing director also gets their company, and anyone
    who will have people answering to them gets the code to hand out. Returns whether the email went.
    """
    if account.status != PENDING_APPROVAL:
        raise HTTPException(409, "This sign-up is not waiting for approval.")
    if not may_decide(by, account):
        raise HTTPException(403, "This sign-up is for someone else to decide.")
    if account.role == "md" and account.company_id is None:
        company = Company(name=account.organisation or f"{account.name}'s company")
        db.add(company)
        db.flush()
        account.company_id = company.id
    if account.role in LEADS and not account.invite_code:
        account.invite_code = fresh_invite(db)
    account.unique_id = next_unique_id(db, account.role)
    account.status = APPROVED
    account.decided_at = now()
    account.decided_by = by.label
    note(
        db, "joined", by.name, f"{account.name} joined as {ROLE_NAMES[account.role]} ({account.unique_id})",
        company_id=account.company_id, author_id=account.id,
    )
    db.commit()
    try:
        subject, text = mailer.approved_email(
            account.name, account.unique_id, ROLE_NAMES[account.role], security.shown_invite(account.invite_code)
        )
        return post.send(account.email, subject, text) is not False
    except mailer.MailError:
        # The approval stands; the app shows the ID to the person as well.
        return False


def reject(db: Session, post: mailer.Mailer, account: Account, by: Actor, reason: str) -> bool:
    if account.status != PENDING_APPROVAL:
        raise HTTPException(409, "This sign-up is not waiting for approval.")
    if not may_decide(by, account):
        raise HTTPException(403, "This sign-up is for someone else to decide.")
    account.status = REJECTED
    account.reject_reason = " ".join(reason.split())[:500]
    account.decided_at = now()
    account.decided_by = by.label
    db.commit()
    try:
        return post.send(account.email, *mailer.rejected_email(account.name, account.reject_reason)) is not False
    except mailer.MailError:
        return False


def names_of(db: Session, ids) -> dict[str, str]:
    """The names of these accounts, for showing who reported or was given something."""
    wanted = {one for one in ids if one}
    if not wanted:
        return {}
    return dict(db.execute(select(Account.id, Account.name).where(Account.id.in_(wanted))).all())
