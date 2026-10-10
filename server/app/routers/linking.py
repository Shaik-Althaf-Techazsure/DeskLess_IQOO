"""
The live linkage: what this server is joined to, and what moves between those parts as it moves.

The parts are nodes: the server; the assistant's rules, the writing model, the assistant's own rounds
and the mail server beside it; the administrator's console; each company, which is a place; and each
person, who is a phone or a browser. What moves is a pulse (see pulse.py): a walk arriving, something
sent in by a user of the place, an issue ranked and routed, passed on, given out, fixed, closed, the
model asked and answering, a reminder on the rounds, an email, a phone listening and being told.

Each viewer gets the graph as they may see it, by the same rules as the dashboards: the administrator
everything, a managing director their company, a manager their team, everyone else themselves. The page
asks once for the whole, then waits on /api/linking/wait, which answers the moment a pulse they may see
has happened, so the drawing moves within a second of the thing itself.
"""

import asyncio
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool

from .. import autopilot, metrics, pulse, writer
from ..config import settings
from ..db import SessionLocal, get_db
from ..models import APPROVED, ASSIGNED, CLOSED, MEMBER, ROLE_NAMES, Account, Company, Issue, Message, now
from ..service import Actor, aware, current_actor, sees_people, when
from .door import ASKS, KINDS as PLACE_KINDS

router = APIRouter(prefix="/api/linking", tags=["linking"])

#: Somebody who asked the server anything this recently is shown as online.
ONLINE_MINUTES = 15
#: How many pulses a page gets on its first look, to catch up on what it missed.
CAUGHT_UP = 150
#: How many people the administrator's graph shows at most, the most recently seen first.
MAX_PEOPLE = 400
#: How long a waiting page is held when it does not say, the longest it may ask for, and how often the
#: pulses are looked at meanwhile.
USUAL_WAIT_SECONDS = 25
MAX_WAIT_SECONDS = 30
LOOK_EVERY_SECONDS = 0.25


# ---------------------------------------------------------------- who may see what


@dataclass(frozen=True)
class Scope:
    """What one viewer may see, worked out once and holding nothing of the database afterwards."""

    me: str
    everything: bool
    #: A managing director's company: everything in it is theirs to see.
    company_id: str | None
    #: The accounts in view.
    visible: frozenset = field(default_factory=frozenset)
    #: Their email addresses, so an email going out can be drawn to the person it is for.
    emails: dict = field(default_factory=dict)
    companies: dict = field(default_factory=dict)

    def resolve(self, one: dict) -> dict | None:
        """A pulse as this viewer may be told it; nothing when it is not theirs to see."""
        to, people, company = one["to"], list(one["people"]), one["company_id"]
        if to.startswith("e:"):
            email = to[2:]
            if email == settings().admin_email:
                to = pulse.ADMIN
            elif email in self.emails:
                account_id = self.emails[email]
                to = pulse.person(account_id)
                people.append(account_id)
                company = company or self.companies.get(account_id)
            elif not self.everything:
                return None
            else:
                to = pulse.MAIL
        allowed = self.everything or (self.company_id is not None and company == self.company_id) or any(one in self.visible for one in people)
        if not allowed:
            return None
        return {
            "n": one["n"], "at": when_at(one["at"]), "kind": one["kind"], "from": one["from"], "to": to, "text": one["text"],
            "ai": one["ai"], "device": one["device"], "ms": one["ms"], "issue_id": one["issue_id"],
        }


def when_at(moment: float) -> str:
    """A pulse's moment with its zone, so the page shows it in the viewer's own time."""
    return datetime.fromtimestamp(moment, timezone.utc).isoformat(timespec="milliseconds")


def _people_in_view(db: Session, actor: Actor) -> list[Account]:
    """The accounts this viewer's graph shows, and the users of the place who wrote to them."""
    if actor.is_admin:
        return list(db.scalars(
            select(Account).where(Account.status == APPROVED).order_by(Account.last_seen_at.desc().nulls_last(), Account.created_at.asc()).limit(MAX_PEOPLE)
        ))
    me = actor.account
    found = list(db.scalars(select(Account).where(sees_people(actor), Account.status == APPROVED)))
    ids = {person.id for person in found}
    if me.role == "md" and me.company_id:
        # The users of the place who sent something to the company.
        wrote = select(Issue.author_id).where(Issue.company_id == me.company_id, Issue.raised_as.in_(ASKS))
    else:
        wrote = select(Message.author_id).where(Message.author_role == MEMBER, Message.to_id.in_(ids))
    for person in db.scalars(select(Account).where(Account.role == MEMBER, Account.status == APPROVED, Account.id.in_(wrote), Account.id.not_in(ids))):
        found.append(person)
    if me.role == MEMBER:
        # A user of a place sees the place, and whoever at it wrote to them.
        answered = select(Message.author_id).where(Message.to_id == me.id, Message.author_id.is_not(None))
        for person in db.scalars(select(Account).where(Account.status == APPROVED, Account.id.in_(answered), Account.id.not_in(ids))):
            found.append(person)
    return found


def scope_of(db: Session, actor: Actor) -> Scope:
    people = _people_in_view(db, actor)
    me = pulse.ADMIN if actor.is_admin else pulse.person(actor.account.id)
    return Scope(
        me=me,
        everything=actor.is_admin,
        company_id=actor.account.company_id if not actor.is_admin and actor.role == "md" else None,
        visible=frozenset(person.id for person in people),
        emails={person.email: person.id for person in people},
        companies={person.id: person.company_id for person in people},
    )


# ---------------------------------------------------------------- the graph


def _near(db: Session, members: list[str]) -> dict[str, str]:
    """For each user of a place, the place they last sent something to."""
    if not members:
        return {}
    near: dict[str, str] = {}
    rows = db.execute(
        select(Issue.author_id, Issue.company_id).where(Issue.author_id.in_(members), Issue.raised_as.in_(ASKS), Issue.company_id.is_not(None)).order_by(Issue.captured_at.desc())
    )
    for author_id, company_id in rows:
        near.setdefault(author_id, company_id)
    return near


def graph(db: Session, actor: Actor, scope: Scope) -> dict:
    """Every node this viewer may see, with where each stands now."""
    people = _people_in_view(db, actor)
    ids = [person.id for person in people]
    since = now().timestamp() - ONLINE_MINUTES * 60
    in_hand = dict(db.execute(select(Issue.assigned_to, func.count()).where(Issue.assigned_to.in_(ids), Issue.status == ASSIGNED).group_by(Issue.assigned_to)).all()) if ids else {}
    waiting = dict(db.execute(select(Message.to_id, func.count()).where(Message.to_id.in_(ids), Message.done_at.is_(None)).group_by(Message.to_id)).all()) if ids else {}
    near = _near(db, [person.id for person in people if person.role == MEMBER])
    rows = []
    for person in people:
        seen = aware(person.last_seen_at)
        rows.append({
            "id": person.id, "name": person.name, "role": person.role, "role_name": ROLE_NAMES.get(person.role, person.role.title()),
            "unique_id": person.unique_id, "company_id": person.company_id, "reports_to": person.reports_to,
            "online": bool(seen and seen.timestamp() >= since), "seen": when(person.last_seen_at), "device": pulse.last_device(person.id),
            "open_work": in_hand.get(person.id, 0), "waiting": waiting.get(person.id, 0), "near": near.get(person.id),
        })
    wanted = {person.company_id for person in people if person.company_id} | set(near.values())
    if actor.is_admin:
        firms = list(db.scalars(select(Company).order_by(Company.created_at.asc())))
    else:
        firms = list(db.scalars(select(Company).where(Company.id.in_(wanted)))) if wanted else []
    firm_ids = [firm.id for firm in firms]
    open_issues = dict(db.execute(select(Issue.company_id, func.count()).where(Issue.company_id.in_(firm_ids), Issue.status != CLOSED).group_by(Issue.company_id)).all()) if firm_ids else {}
    headcount = dict(db.execute(select(Account.company_id, func.count()).where(Account.company_id.in_(firm_ids), Account.status == APPROVED).group_by(Account.company_id)).all()) if firm_ids else {}
    companies = [
        {
            "id": firm.id, "name": firm.name, "kind": firm.kind or "", "kind_name": PLACE_KINDS.get(firm.kind or "", ""), "open_door": bool(firm.open_door),
            "people": headcount.get(firm.id, 0), "open_issues": open_issues.get(firm.id, 0), "autopilot": firm.autopilot is not False,
        }
        for firm in firms
    ]
    return {"people": rows, "companies": companies}


def services(db: Session, actor: Actor) -> dict:
    """The parts beside the server, and whether each is running."""
    config = settings()
    model = writer.status()
    asked = metrics.last_hour()
    company = None if actor.is_admin else actor.account.company_id
    return {
        "server": {"uptime_seconds": int(time.time() - metrics.STARTED), "requests_last_hour": asked["total"], "typical_ms": asked["typical_ms"], "under": config.base_path or "/"},
        "assistant": {"on": True, "bands": ["Act now", "Soon", "Routine"]},
        "writer": {"on": model["on"], "set": model["set"], "model": model["model"], "busy": model["busy"], "written": model["written"], "last_ms": model["last_ms"]},
        "rounds": {"on": config.autopilot_seconds > 0, "every_seconds": config.autopilot_seconds, "today": autopilot.today(db, company)},
        "mail": {"set": bool(config.smtp_host)},
    }


# ---------------------------------------------------------------- the doors


@router.get("")
def linkage(since: int = 0, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """
    The whole of it for this viewer: who they are, the server and the services beside it, the places
    and the people, and the pulses they may see. With `since` 0 the last of those, to catch up; with the
    number of the last pulse the page holds, only those after it. `mark` is what to hold while waiting on
    /api/linking/wait.
    """
    scope = scope_of(db, actor)
    kept = [told for told in (scope.resolve(one) for one in pulse.since(since)) if told is not None]
    if since <= 0:
        kept = kept[-CAUGHT_UP:]
    me = {"node": scope.me, "name": actor.name, "role": actor.role, "role_name": "Administrator" if actor.is_admin else ROLE_NAMES.get(actor.role, actor.role)}
    return {
        "mark": pulse.count(), "now": when(now()), "me": me, "services": services(db, actor),
        "kinds": [{"key": key, "name": name} for key, name in pulse.KINDS.items()],
        **graph(db, actor, scope), "pulses": kept,
    }


def _scope_now(authorization: str | None) -> Scope:
    """The viewer's scope on a session of its own, so a page that waits holds no connection to the database."""
    with SessionLocal() as db:
        return scope_of(db, current_actor(authorization, db))


@router.get("/wait")
async def wait(since: int = 0, seconds: int = USUAL_WAIT_SECONDS, authorization: str | None = Header(None)) -> dict:
    """
    Waits until there is a pulse after the one numbered `since` that this viewer may see, and hands over
    every such pulse at once. When `seconds` have passed (1 to 30) with none, `pulses` is empty and
    `mark` is how far was looked, so the page asks on from there. While it waits it holds neither a
    connection to the database nor a thread.
    """
    until = time.monotonic() + max(1, min(MAX_WAIT_SECONDS, seconds))
    scope = await run_in_threadpool(_scope_now, authorization)
    looked = since
    while True:
        fresh = pulse.since(looked)
        kept = [told for told in (scope.resolve(one) for one in fresh) if told is not None]
        if kept:
            return {"mark": kept[-1]["n"], "pulses": kept}
        if fresh:
            # Nothing for this viewer among them: they need not be read again.
            looked = fresh[-1]["n"]
        left = until - time.monotonic()
        if left <= 0:
            return {"mark": looked, "pulses": []}
        await asyncio.sleep(min(LOOK_EVERY_SECONDS, left))
