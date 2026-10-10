"""
A phone's desk: what needs the person holding it, and nothing more. A dashboard's first screen also
carries trends, teams and findings, which is a great deal to fetch every few seconds for someone who only
wants to know what to do next. Here the phone gets the same queue, the same lines and the same inbox, each
issue with enough to act on it without asking again, and a mark of the whole. Holding that mark it can
wait: the server answers the moment the desk has changed, so the phone hears of it within a second or two
and has not asked over and over meanwhile.
"""

import asyncio
import hashlib
import json
import time

from fastapi import APIRouter, Depends, Header
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool

from .. import changes, pulse
from ..db import SessionLocal, get_db
from ..service import Actor, current_actor, may_direct
from .work import Tray, asker, due_words, technicians, tray_for

router = APIRouter(prefix="/api", tags=["desk"])

#: How long a waiting phone is kept when it does not say, the longest it may ask for, and how often the
#: count of what has been written is looked at meanwhile.
USUAL_WAIT_SECONDS = 25
MAX_WAIT_SECONDS = 30
LOOK_EVERY_SECONDS = 0.5


def _steady(tray: Tray, told: dict) -> dict:
    """
    What of a desk stays as it is until somebody does something. The score, the reasons and the wording
    of a step move with the clock alone ("nobody has taken it for 5 h", "due 12 Oct"), so they are left
    out. What is kept: which issues are there and in what order, how each is told, where it stands, who
    has it, which messages about it still wait, the step proposed and for whom, what waits in the inbox,
    and the technicians with what each has in hand.
    """

    def standing(issue_id: str) -> list:
        issue = tray.by_id[issue_id]
        waiting = [message.id for message in tray.desk.messages[issue.id] if message.done_at is None]
        return [issue.id, issue.status, issue.severity, issue.assigned_to or "", waiting]

    return {
        "me": told["me"],
        "may_direct": told["may_direct"],
        "queue": [
            [
                *standing(row["id"]), row["title"], row["site"], row["location"], row["note"], row["due"],
                row["next"]["kind"], row["next"].get("to", ""), [one["id"] for one in row["send_to"]],
            ]
            for row in told["queue"]
        ],
        "triage": [[*standing(line["issue_id"]), line["title"], line["site"]] for line in told["triage"]],
        "inbox": [[row["id"], row["status"]] for row in told["inbox"]],
        "technicians": told["technicians"],
    }


def laid_out(db: Session, actor: Actor) -> dict:
    """The desk of whoever asks, with its mark: the same desk gives the same mark, a changed one another."""
    tray = tray_for(db, actor)
    queue = []
    for row in tray.needs_me:
        issue = tray.by_id[row["id"]]
        queue.append(row | {"location": issue.location, "note": issue.note, "due": due_words(issue), "send_to": tray.desk.may_send_to(issue)})
    told = {
        "me": asker(db, actor),
        "may_direct": may_direct(actor),
        "queue": queue,
        "triage": tray.triage,
        "inbox": tray.inbox(),
        "technicians": technicians(actor, db) if may_direct(actor) else [],
    }
    steady = json.dumps(_steady(tray, told), sort_keys=True, separators=(",", ":"))
    return {"mark": hashlib.sha256(steady.encode()).hexdigest()[:16]} | told


@router.get("/desk")
def desk(actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """
    What a phone shows as the person's desk: the issues that need them, most pressing first, each with
    its next step, where it is, the inspector's note, its deadline in words and whom it may be sent to;
    a line for each of the most pressing issues in view; what has been sent to them and still waits; and,
    for those who give out work, their technicians with what each has in hand. The queue, the lines and
    the inbox are the dashboard's own. `mark` is what to hold while waiting on /api/desk/wait.
    """
    return laid_out(db, actor)


def _mark_now(authorization: str | None) -> tuple[str, Actor]:
    """
    The mark of the desk of whoever holds this sign-in, as it stands this moment, and who they are. It is
    worked out on a session of its own, opened and closed here, and the person is looked up afresh each
    time. So a phone that waits holds no connection to the database in between, and one whose account
    has been suspended meanwhile is turned away at the next look.
    """
    with SessionLocal() as db:
        actor = current_actor(authorization, db)
        return laid_out(db, actor)["mark"], actor


def _node(actor: Actor) -> tuple[str, list, str | None]:
    """Where a waiting phone stands in the live linkage: its node, who is concerned, and the company."""
    if actor.is_admin:
        return pulse.ADMIN, [], None
    return pulse.person(actor.account.id), [actor.account.id], actor.account.company_id


@router.get("/desk/wait")
async def wait(mark: str = "", seconds: int = USUAL_WAIT_SECONDS, authorization: str | None = Header(None), user_agent: str | None = Header(None)) -> dict:
    """
    Waits until the person's desk is no longer the one `mark` was given for, and says so at once:
    `changed` true with the new mark, straight away when it already differs. When `seconds` have passed
    (1 to 30) with nothing new, `changed` is false and the phone asks again.

    While it waits it holds neither a connection to the database nor a thread: it sleeps, looks at the
    count of what has been written, and reads the desk again only when that count has moved. What moves
    with the clock alone, such as a job running past its day, is seen when the phone next asks.
    """
    held = max(1, min(MAX_WAIT_SECONDS, seconds))
    until = time.monotonic() + held
    # The count is read before the desk is, so something written between the two is looked at again.
    written = changes.count()
    current, actor = await run_in_threadpool(_mark_now, authorization)
    node, people, company = _node(actor)
    device = pulse.device_of(user_agent)
    pulse.record("listen", node, pulse.SERVER, f"listening for the desk to change, up to {held} s", people=people, company_id=company, device=device)
    while current == mark and time.monotonic() < until:
        await asyncio.sleep(max(0.0, min(LOOK_EVERY_SECONDS, until - time.monotonic())))
        seen = changes.count()
        if seen != written:
            written = seen
            current, actor = await run_in_threadpool(_mark_now, authorization)
    if current != mark:
        pulse.record("push", pulse.SERVER, node, "the desk changed: the phone is told and reads it again", people=people, company_id=company, device=device)
    return {"mark": current, "changed": current != mark}
