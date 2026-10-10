"""
The door a place keeps open to the people who use it. A resident, a student, a visitor or a member of
staff works for nobody at the place and still notices what is wrong there. They sign up with a name, an
email address and a password, pick the kind of place and the place itself, and send what they noticed:
an issue, a report of something that happened, or a question. It becomes an issue of that place and
goes to one of its inspectors with the sender's own words, so it is ranked, passed on, repaired and
closed like anything found on a walk, and nobody at another place sees it. The sender is told where it
stands, and hears whatever the place answers.

A place takes nothing in, and is named to nobody, until its managing director opens the door.
"""

import hashlib
import json
import secrets
from uuid import uuid4

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import mailer, security, writer
from ..assistant import dealt_with
from ..config import settings
from ..db import get_db
from ..models import (
    APPROVED, ASSIGNED, CLOSED, FIXED, MEMBER, OPEN, PENDING_APPROVAL, ROLE_NAMES, SEVERITIES, Account, Company, Issue, Message, Report, now,
)
from ..pictures import save_photo
from ..service import Actor, current_account, current_actor, get_mailer, names_of, note, send_code, when
from .signup import _EMAIL, MIN_PASSWORD, clean_phone, forget_unfinished

router = APIRouter(prefix="/api", tags=["door"])

#: The kinds of place, by the name kept and the name people look for.
KINDS = {
    "apartment": "Apartments and housing",
    "college": "College or school",
    "office": "Office",
    "government": "Government office",
    "hospital": "Hospital or clinic",
    "mall": "Mall or shop",
    "hotel": "Hotel",
    "factory": "Factory or warehouse",
    "site": "Construction site",
    "other": "Another kind of place",
}
#: What a user may send: something to put right, something that happened, something they want to know.
ASKS = ("issue", "report", "enquiry")
#: The mark on the message that carries what was sent in to the first person at the place.
RULE = "public"
MAX_PICTURE_BYTES = 12 * 1024 * 1024
PICTURE_EDGE = 1600
#: The report type under which what users send in is kept, and how many one person may have open at a place.
TEMPLATE = "PUBLIC"
MAX_OPEN = 20


def _line(value, limit: int) -> str:
    return " ".join(str(value or "").split())[:limit]


def _kind_of(firm: Company) -> str:
    return firm.kind if firm.kind in KINDS else "other"


# ---------------------------------------------------------------- signing up as a user of a place


class NewUser(BaseModel):
    name: str
    email: str
    password: str
    #: A mobile number the place may ring back on; may be left empty.
    phone: str = ""


@router.post("/signup/user")
def sign_up(body: NewUser, db: Session = Depends(get_db), post: mailer.Mailer = Depends(get_mailer)) -> dict:
    """
    The short form for someone who uses a place: no Aadhaar number, no photo, nobody to approve them.
    A code goes to the email address; once it is typed in (/api/signup/verify) the account has its ID.
    """
    name = _line(body.name, 200)
    email = body.email.strip().lower()
    if len(name) < 2 or len(name) > 120:
        raise HTTPException(422, "Enter your name.")
    if not _EMAIL.fullmatch(email) or len(email) > 254:
        raise HTTPException(422, "That email address does not look right.")
    mobile = ""
    if body.phone.strip():
        mobile = clean_phone(body.phone)
        if mobile is None:
            raise HTTPException(422, "Enter a 10-digit mobile number, or leave it empty.")
    if len(body.password) < MIN_PASSWORD:
        raise HTTPException(422, f"Choose a password of at least {MIN_PASSWORD} characters.")
    if db.scalars(select(Account).where(Account.email == email, Account.status.in_((PENDING_APPROVAL, APPROVED)))).first():
        raise HTTPException(409, "An account with this email address already exists. Sign in instead.")
    forget_unfinished(db, email)
    ticket = secrets.token_urlsafe(24)
    account = Account(
        role=MEMBER, name=name, email=email, phone=mobile, organisation="",
        aadhaar_sealed=b"", aadhaar_last4="", aadhaar_fingerprint="", photo="",
        password_hash=security.hash_password(body.password), ticket_hash=security.keyed_hash("ticket", ticket),
    )
    db.add(account)
    db.commit()
    send_code(db, post, account, "signup")
    return {"email": email, "ticket": ticket, "code_minutes": settings().otp_minutes}


# ---------------------------------------------------------------- the places that have opened their door


def _kinds() -> list[dict]:
    return [{"key": key, "name": name} for key, name in KINDS.items()]


@router.get("/places")
def places(kind: str = "", account: Account = Depends(current_account), db: Session = Depends(get_db)) -> dict:
    """The places that take what their users send in, with the kinds they are; of one kind when asked."""
    found = list(db.scalars(select(Company).where(Company.open_door.is_(True)).order_by(Company.name.asc())))
    count: dict[str, int] = {}
    for firm in found:
        count[_kind_of(firm)] = count.get(_kind_of(firm), 0) + 1
    wanted = kind.strip().lower()
    return {
        "kinds": [one | {"places": count.get(one["key"], 0)} for one in _kinds()],
        "places": [
            {"id": firm.id, "name": firm.name, "kind": _kind_of(firm), "kind_name": KINDS[_kind_of(firm)]}
            for firm in found if not wanted or _kind_of(firm) == wanted
        ],
    }


def _own_firm(db: Session, actor: Actor) -> Company | None:
    return db.get(Company, actor.account.company_id) if not actor.is_admin and actor.account.company_id else None


@router.get("/company/door")
def door(actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """Whether the asker's company takes what its users send in, what kind of place it is, and how much has come."""
    firm = _own_firm(db, actor)
    sent_in = db.scalar(select(func.count()).select_from(Issue).where(Issue.company_id == firm.id, Issue.raised_as.in_(ASKS))) if firm else 0
    return {
        "open": bool(firm and firm.open_door), "kind": (firm.kind or "") if firm else "", "kinds": _kinds(),
        "may_set": actor.role == "md" and firm is not None, "sent_in": sent_in or 0, "name": firm.name if firm else "",
    }


class Door(BaseModel):
    open: bool
    kind: str = ""


@router.post("/company/door")
def set_door(body: Door, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """The managing director opens the place to the people who use it, saying what kind of place it is, or shuts it."""
    firm = _own_firm(db, actor)
    if firm is None or actor.role != "md":
        raise HTTPException(403, "Only the company's managing director sets this.")
    kind = body.kind.strip().lower() or (firm.kind or "")
    if body.open and kind not in KINDS:
        raise HTTPException(422, "Say what kind of place it is.")
    was = bool(firm.open_door)
    firm.kind = kind if kind in KINDS else ""
    firm.open_door = body.open
    if was != body.open:
        words = f"opened {firm.name} to the people who use it" if body.open else f"closed {firm.name} to what its users send in"
        note(db, "joined", actor.name, f"{actor.name} {words}", company_id=firm.id, author_id=actor.account.id)
    db.commit()
    return {"open": body.open, "kind": firm.kind}


# ---------------------------------------------------------------- what a user sends in


def receiver_for(db: Session, firm: Company) -> Account | None:
    """
    Who at the place is told first: the inspector with the least already waiting on them, the one who
    joined earliest among equals. A place with no inspector yet has a manager told, and failing that
    its managing director.
    """
    for role in ("inspector", "manager", "md"):
        people = list(db.scalars(
            select(Account).where(Account.company_id == firm.id, Account.status == APPROVED, Account.role == role).order_by(Account.created_at.asc(), Account.unique_id.asc())
        ))
        if people:
            waiting = dict(db.execute(
                select(Message.to_id, func.count()).where(Message.to_id.in_([person.id for person in people]), Message.done_at.is_(None)).group_by(Message.to_id)
            ).all())
            return min(people, key=lambda person: waiting.get(person.id, 0))
    return None


def _mine(db: Session, account: Account, issue_id: str) -> Issue:
    issue = db.scalars(select(Issue).where(Issue.id == issue_id, Issue.author_id == account.id, Issue.raised_as.in_(ASKS))).first()
    if issue is None:
        raise HTTPException(404, "Nothing of yours is known by that name.")
    return issue


def _standing(issue: Issue, names: dict[str, str], thread: list[Message], me: str) -> str:
    """Where it stands, as the person who sent it in is told and as their phone says it aloud."""
    holder = names.get(issue.assigned_to) or "a technician"
    if issue.status == CLOSED:
        if issue.raised_as == "enquiry":
            return "Answered and closed."
        by = f" by {issue.closed_by}" if issue.closed_by else ""
        return f"Repaired, checked and closed{by}." if issue.fix_photo else f"Closed{by}."
    if issue.status == FIXED:
        return f"{holder} says it is repaired and has shown a picture. It waits to be checked."
    if issue.status == ASSIGNED:
        return f"Given to {holder} to put right" + (f", by {issue.due_on.strftime('%d %b').lstrip('0')}" if issue.due_on else "") + "."
    answered = next((message for message in reversed(thread) if message.to_id == me), None)
    if answered is not None:
        return f"{answered.author} ({ROLE_NAMES.get(answered.author_role, 'the place')}) has answered. Nobody has been given it to put right yet."
    first = next((message for message in thread if message.rule == RULE), None)
    return f"With {first.to_name}. Nobody has taken it up yet." if first is not None else "Sent. Nobody has taken it up yet."


def _told(issue: Issue, firms: dict[str, Company], names: dict[str, str], thread: list[Message], me: str) -> dict:
    firm = firms.get(issue.company_id)
    first = next((message for message in thread if message.rule == RULE), None)
    return {
        "id": issue.id, "client_id": issue.client_id, "place_id": issue.company_id, "place": firm.name if firm else issue.site,
        "kind": issue.raised_as, "title": issue.title or "Untitled", "note": issue.note, "location": issue.location, "category": issue.category,
        "severity": issue.severity, "status": issue.status, "standing": _standing(issue, names, thread, me),
        "sent_to": first.to_name if first is not None else "", "has_photo": bool(issue.photo), "has_fix_photo": bool(issue.fix_photo),
        "at": when(issue.captured_at),
        "thread": [
            {
                "id": message.id, "at": when(message.at), "from": message.author, "from_role": ROLE_NAMES.get(message.author_role, ""),
                "text": message.text, "mine": message.author_id == me, "unheard": message.to_id == me and message.done_at is None,
            }
            for message in thread
        ],
        "unheard": sum(1 for message in thread if message.to_id == me and message.done_at is None),
    }


def _rows(db: Session, account: Account, found: list[Issue]) -> list[dict]:
    ids = [issue.id for issue in found]
    threads: dict[str, list[Message]] = {issue_id: [] for issue_id in ids}
    if ids:
        for message in db.scalars(select(Message).where(Message.issue_id.in_(ids)).order_by(Message.id.asc())):
            # The sender sees what was said to them and what they said, not what the place says within itself.
            if account.id in (message.author_id, message.to_id):
                threads[message.issue_id].append(message)
    firms = {firm.id: firm for firm in db.scalars(select(Company).where(Company.id.in_({issue.company_id for issue in found})))} if found else {}
    names = names_of(db, [issue.assigned_to for issue in found])
    return [_told(issue, firms, names, threads[issue.id], account.id) for issue in found]


@router.post("/requests")
async def send_in(
    place: str = Form(...),
    kind: str = Form("issue"),
    title: str = Form(""),
    note_text: str = Form("", alias="note"),
    location: str = Form(""),
    category: str = Form(""),
    severity: str = Form("MEDIUM"),
    client_id: str = Form(""),
    seen: str = Form(""),
    check: str = Form(""),
    photo: UploadFile | None = File(None),
    account: Account = Depends(current_account),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    """
    Takes what a user of a place noticed or wants to know, and brings it to the place: it becomes an
    issue there, and one of the place's inspectors is told with the sender's own words. Sent twice under
    the same `client_id` (a phone that never heard the answer), it is taken once.
    """
    firm = db.get(Company, place.strip())
    if firm is None or not firm.open_door:
        raise HTTPException(404, "That place does not take what its users send in.")
    words = note_text.strip()[:4000]
    heading = _line(title, 200)
    if not words and not heading:
        raise HTTPException(422, "Say what it is about.")
    asked_as = kind.strip().lower()
    asked_as = asked_as if asked_as in ASKS else "issue"
    how_bad = severity.strip().upper()
    how_bad = "LOW" if asked_as == "enquiry" else how_bad if how_bad in SEVERITIES else "MEDIUM"
    raw = await photo.read(MAX_PICTURE_BYTES + 1) if photo is not None else b""
    if len(raw) > MAX_PICTURE_BYTES:
        raise HTTPException(413, "That photo is too large.")

    # What one person sends to one place is kept together, as a walk's issues are.
    walk = "user-" + firm.id
    name = _line(client_id, 40) or uuid4().hex
    report = db.scalars(select(Report).where(Report.account_id == account.id, Report.walk == walk)).first()
    if report is not None:
        already = db.scalars(select(Issue).where(Issue.report_id == report.id, Issue.client_id == name)).first()
        if already is not None:
            return _rows(db, account, [already])[0]
        waiting = db.scalar(select(func.count()).select_from(Issue).where(Issue.report_id == report.id, Issue.status != CLOSED)) or 0
        if waiting >= MAX_OPEN:
            raise HTTPException(429, f"You already have {MAX_OPEN} things waiting at {firm.name}. Wait for some of them to be dealt with.")
    receiver = receiver_for(db, firm)
    if receiver is None:
        raise HTTPException(409, f"Nobody at {firm.name} can take it just now.")
    if report is None:
        report = Report(
            id=uuid4().hex, account_id=account.id, walk=walk, company_id=firm.id, template=TEMPLATE,
            title="Sent in by a user of the place", pdf="", in_progress=False, issue_count=0,
        )
        db.add(report)
    report.place, report.person = firm.name, account.name
    report.built_at = report.received_at = now()
    db.flush()

    issue = Issue(
        id=uuid4().hex, report_id=report.id, client_id=name, company_id=firm.id, author_id=account.id,
        number=(db.scalar(select(func.max(Issue.number)).where(Issue.report_id == report.id)) or 0) + 1,
        site=firm.name, title=heading or _line(words, 80), note=words, location=_line(location, 200), category=_line(category, 60),
        severity=how_bad, ai_check=_line(check, 12).upper(), seen=seen.strip()[:1000], status=OPEN, raised_as=asked_as,
    )
    db.add(issue)
    if raw:
        try:
            issue.photo = save_photo(raw, settings().issues_dir, f"{issue.id}.jpg", PICTURE_EDGE)
        except HTTPException:
            # A file that is no picture is left out; what was said still goes.
            issue.photo = ""
    report.issue_count = (report.issue_count or 0) + 1
    what = {"issue": "an issue", "report": "a report", "enquiry": "a question"}[asked_as]
    note(
        db, "report", account.name, f"{account.name} sent in {what} as a user of {firm.name}; {receiver.name} was told",
        company_id=firm.id, report_id=report.id, issue_id=issue.id, author_id=account.id, assignee_id=receiver.id,
    )
    said = (words or heading)[:2000]
    db.add(Message(
        issue_id=issue.id, company_id=firm.id, author_id=account.id, author=account.name, author_role=MEMBER, rule=RULE,
        to_id=receiver.id, to_name=receiver.name, text=said,
    ))
    db.commit()
    writer.nudge()
    try:
        post.send(receiver.email, *mailer.sent_email(receiver.name, f"{account.name}, a user of {firm.name},", issue.title, "", said))
    except mailer.MailError:
        pass
    return _rows(db, account, [issue])[0]


def _all_mine(db: Session, account: Account) -> list[Issue]:
    return list(db.scalars(
        select(Issue).where(Issue.author_id == account.id, Issue.raised_as.in_(ASKS)).order_by(Issue.captured_at.desc(), Issue.number.desc()).limit(100)
    ))


@router.get("/requests")
def sent_in(account: Account = Depends(current_account), db: Session = Depends(get_db)) -> dict:
    """
    Everything this person has sent in, newest first: where each stands and what was said about it.
    `mark` stays the same until something about one of them changes, so a phone can tell at a glance.
    """
    rows = _rows(db, account, _all_mine(db, account))
    steady = [[row["id"], row["status"], row["standing"], [said["id"] for said in row["thread"]], row["unheard"], row["has_fix_photo"]] for row in rows]
    return {"mark": hashlib.sha256(json.dumps(steady, separators=(",", ":")).encode()).hexdigest()[:16], "requests": rows}


class Words(BaseModel):
    text: str


@router.post("/requests/{issue_id}/say")
def say(
    issue_id: str,
    body: Words,
    account: Account = Depends(current_account),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    """
    The sender adds a word: an answer to what the place asked, or more about it. It goes to whoever at
    the place last wrote to them about it, or else to whoever was told first.
    """
    issue = _mine(db, account, issue_id)
    words = body.text.strip()[:2000]
    if not words:
        raise HTTPException(422, "Say what you want to add.")
    thread = list(db.scalars(select(Message).where(Message.issue_id == issue.id).order_by(Message.id.asc())))
    last = next((message for message in reversed(thread) if message.to_id == account.id and message.author_id), None)
    first = next((message for message in thread if message.rule == RULE), None)
    known = last.author_id if last is not None else first.to_id if first is not None else None
    to = db.get(Account, known) if known else None
    if to is None or to.status != APPROVED:
        firm = db.get(Company, issue.company_id)
        to = receiver_for(db, firm) if firm is not None else None
    if to is None:
        raise HTTPException(409, "Nobody at the place can take it just now.")
    dealt_with(db, Actor(account=account), issue)
    if issue.raised_as == "enquiry" and issue.status == CLOSED:
        # A question asked again is open again.
        issue.status, issue.closed_at, issue.closed_by = OPEN, None, ""
    db.add(Message(
        issue_id=issue.id, company_id=issue.company_id, author_id=account.id, author=account.name, author_role=MEMBER,
        to_id=to.id, to_name=to.name, text=words,
    ))
    shown = words if len(words) <= 160 else words[:159].rstrip() + "…"
    note(
        db, "sent", account.name, f"{account.name} wrote to {to.name}: “{shown}”",
        company_id=issue.company_id, report_id=issue.report_id, issue_id=issue.id, author_id=account.id, assignee_id=to.id,
    )
    db.commit()
    writer.nudge()
    try:
        post.send(to.email, *mailer.sent_email(to.name, f"{account.name}, a user of the place,", issue.title, issue.site, words))
    except mailer.MailError:
        pass
    return _rows(db, account, [issue])[0]


@router.post("/requests/{issue_id}/heard")
def heard(issue_id: str, account: Account = Depends(current_account), db: Session = Depends(get_db)) -> dict:
    """The sender has read, or been read, what the place wrote: it no longer waits on them."""
    issue = _mine(db, account, issue_id)
    dealt_with(db, Actor(account=account), issue)
    db.commit()
    return _rows(db, account, [issue])[0]
