"""
The assistant acting by itself. Left alone, an urgent issue can sit untaken, a job can run past its day
and a repair can wait unchecked, because everyone thought someone else had seen it. So on its rounds the
assistant does what a careful coordinator would: it reminds the person the work waits on, and when that
does not help it takes the issue one level up. It never gives out, fixes or closes anything.

It follows fixed rules, each of which speaks once about an issue:

  untaken    an issue ranked "act now" that nobody has taken    -> the manager who gives it out
  escalated  the same issue, still untaken some while later      -> the managing director
  late       a job past its day                                  -> the technician who has it
  overdue    a job three days or more past its day               -> the manager
  unchecked  a repair shown and not checked for a day            -> the manager

What it sends is an ordinary message about the issue, marked as the assistant's own. It waits on the
person like any other and is settled when they do the work. A company's managing director can switch
this off.
"""

import logging
import threading
import time
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import changes, mailer
from .assistant import Desk, _days, _first, _place, _span, _title
from .config import settings
from .db import SessionLocal
from .models import ASSIGNED, CLOSED, FIXED, OPEN, Account, Company, Issue, Message, now
from .service import Actor, aware, note

log = logging.getLogger("deskless.autopilot")

NAME = "DeskLess assistant"
ROLE = "assistant"
#: Days past its day after which a job is brought to the manager as well.
OVERDUE_DAYS = 3


def rules() -> list[str]:
    """The rules in words, with the times set on this server, for the dashboards to show."""
    config = settings()
    return [
        f"An issue to act on now that nobody has taken for {_span(config.untaken_minutes / 60) if config.untaken_minutes >= 60 else str(config.untaken_minutes) + ' min'}: its manager is told.",
        f"Still untaken after {_span(config.escalate_minutes / 60) if config.escalate_minutes >= 60 else str(config.escalate_minutes) + ' min'}: the managing director is told.",
        f"A job past its day: the technician is asked. {OVERDUE_DAYS} days past: the manager is told.",
        f"A repair not checked for {config.unchecked_hours} h: the manager is reminded.",
    ]


def _send(db: Session, post, issue: Issue, to: Account, rule: str, text: str, said: str) -> None:
    db.add(Message(
        issue_id=issue.id, company_id=issue.company_id, author_id=None, author=NAME, author_role=ROLE, rule=rule,
        to_id=to.id, to_admin=False, to_name=to.name, text=text,
    ))
    note(
        db, "auto", NAME, f"The assistant {said}",
        company_id=issue.company_id, report_id=issue.report_id, issue_id=issue.id, author_id=issue.author_id, assignee_id=to.id,
    )
    db.commit()
    # No request brought this about, so whoever waits to hear of a change to their desk is told from here.
    changes.bump()
    if post is not None:
        try:
            post.send(to.email, *mailer.sent_email(to.name, "The DeskLess assistant", _title(issue), issue.site, text))
        except mailer.MailError:
            pass


def run_once(db: Session, post=None) -> int:
    """One round over every company that has not switched it off. Returns how many messages it sent."""
    config = settings()
    off = set(db.scalars(select(Company.id).where(Company.autopilot.is_(False))))
    waiting = [
        issue for issue in db.scalars(select(Issue).where(Issue.status != CLOSED, Issue.company_id.is_not(None)))
        if issue.company_id not in off
    ]
    if not waiting:
        return 0
    # Seen as the administrator sees it: everything, with nobody's own point of view.
    desk = Desk(db, Actor(admin=NAME), waiting)
    spoken: dict[str, set[str]] = {}
    for issue_id, rule in db.execute(select(Message.issue_id, Message.rule).where(Message.author_role == ROLE, Message.issue_id.in_([issue.id for issue in waiting]))):
        spoken.setdefault(issue_id, set()).add(rule or "")
    sent = 0
    for issue in waiting:
        done = spoken.get(issue.id, set())
        where = f"{_title(issue).rstrip('.')}{' at ' + _place(issue) if _place(issue) else ''}"
        lead = desk.lead_for(issue)
        holder = desk.people.get(issue.assigned_to)
        todo: list[tuple[Account, str, str, str]] = []
        if issue.status == OPEN and lead is not None:
            minutes = (desk.moment - aware(issue.captured_at)).total_seconds() / 60
            score = desk.priority(issue)
            if score["band"] == "now" and minutes >= config.untaken_minutes:
                could = desk.who_could(issue)
                best = f" Best placed to take it: {could[0]['name']}." if could else ""
                if "untaken" not in done:
                    todo.append((lead, "untaken", f"{_first(lead.name)}, nobody has taken this yet and it is ranked to act on now: {where}. {desk.summary(issue)}{best}", f"told {lead.name} that nobody has taken it"))
                director = next((person for person in desk.people.values() if person.role == "md" and person.company_id == issue.company_id), None)
                if minutes >= config.escalate_minutes and director is not None and director.id != lead.id and "escalated" not in done:
                    todo.append((director, "escalated", f"{_first(director.name)}, this has waited {_span(minutes / 60)} with {lead.name} and nobody has taken it: {where}. {desk.summary(issue)}{best}", f"took it up to {director.name}: untaken for {_span(minutes / 60)}"))
        elif issue.status == ASSIGNED and issue.due_on is not None and holder is not None:
            late = (desk.day - issue.due_on).days
            if late >= 1 and f"late:{issue.due_on}" not in done:
                todo.append((holder, f"late:{issue.due_on}", f"{_first(holder.name)}, this is {_days(late)} past its day: {where}. What is holding it, and when will it be done?", f"asked {holder.name} about a job {_days(late)} past its day"))
            if late >= OVERDUE_DAYS and lead is not None and lead.id != holder.id and f"overdue:{issue.due_on}" not in done:
                todo.append((lead, f"overdue:{issue.due_on}", f"{_first(lead.name)}, this has been with {holder.name} for {_days(late)} past its day: {where}. It may need another pair of hands.", f"told {lead.name} of a job {_days(late)} past its day"))
        elif issue.status == FIXED and lead is not None and issue.fixed_at is not None:
            hours = (desk.moment - aware(issue.fixed_at)).total_seconds() / 3600
            mark = f"unchecked:{aware(issue.fixed_at).date()}"
            if hours >= config.unchecked_hours and mark not in done:
                todo.append((lead, mark, f"{_first(lead.name)}, the repair for this was shown {_span(hours)} ago and has not been checked: {where}. Compare the two pictures, then close it or send it back.", f"reminded {lead.name} to check a repair shown {_span(hours)} ago"))
        for to, rule, text, said in todo:
            _send(db, post, issue, to, rule, text, said)
            sent += 1
    return sent


def today(db: Session, company_id: str | None) -> int:
    """How many messages the assistant sent by itself in the last day, in one company or in all."""
    since = now() - timedelta(days=1)
    query = select(Message.id).where(Message.author_role == ROLE, Message.at >= since)
    if company_id is not None:
        query = query.where(Message.company_id == company_id)
    return len(list(db.scalars(query)))


_started = False


def _run(post) -> None:
    every = settings().autopilot_seconds
    while True:
        time.sleep(every)
        try:
            with SessionLocal() as db:
                sent = run_once(db, post)
            if sent:
                log.info("The assistant sent %s message(s) by itself", sent)
        except Exception:  # the dashboards must never go down with the assistant's rounds
            log.exception("The assistant's round failed")


def start(post) -> None:
    """Starts the rounds beside the server, once. AUTOPILOT_EVERY_SECONDS=0 leaves them out."""
    global _started
    if _started or settings().autopilot_seconds <= 0:
        return
    _started = True
    threading.Thread(target=_run, args=(post,), name="deskless-autopilot", daemon=True).start()
