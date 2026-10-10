"""
The work itself: reports arriving from the phones, the issues in them, the giving of an issue to a
technician, the repair shown with a picture, the check that closes it, and the numbers for each dashboard.
"""

import json
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import mailer
from ..config import settings
from ..db import get_db
from ..models import APPROVED, ASSIGNED, CLOSED, FIXED, OPEN, SEVERITIES, STATUSES, Account, Event, Issue, Report, now
from ..pictures import save_photo
from ..service import (
    Actor, aware, current_account, current_actor, get_mailer, may_direct, names_of, note, profile, sees_events,
    sees_issues, sees_reports, today, waiting_for, when,
)

router = APIRouter(prefix="/api", tags=["work"])

MAX_PDF_BYTES = 40 * 1024 * 1024
MAX_PICTURE_BYTES = 12 * 1024 * 1024
MAX_ISSUES = 300
PICTURE_EDGE = 1600
VERDICTS = ("GENUINE", "DOUBTFUL", "FAKE")
SERIOUS = ("CRITICAL", "HIGH")


def _line(value, limit: int) -> str:
    """Text from a phone as one tidy line of bounded length."""
    return " ".join(str(value or "").split())[:limit]


def _moment(milliseconds) -> datetime:
    try:
        return datetime.fromtimestamp(int(milliseconds) / 1000, timezone.utc)
    except (TypeError, ValueError, OverflowError, OSError):
        return now()


def _number(value) -> float | None:
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _due(text: str) -> date | None:
    """The app writes a deadline it could date as "12 Oct 2026"; anything else stays words only."""
    try:
        return datetime.strptime(text.strip(), "%d %b %Y").date()
    except ValueError:
        return None


def _keep_picture(raw: bytes | None, name: str) -> str:
    """Stores a picture and returns its file name; a file that is no picture is left out, not fatal."""
    if not raw:
        return ""
    try:
        return save_photo(raw, settings().issues_dir, name, PICTURE_EDGE)
    except HTTPException:
        return ""


# ---------------------------------------------------------------- reports arriving from the phones


@router.post("/reports")
async def send_report(
    meta: str = Form(...),
    pdf: UploadFile = File(...),
    photos: list[UploadFile] = File(default=[]),
    account: Account = Depends(current_account),
    db: Session = Depends(get_db),
) -> dict:
    """
    Takes a finished report from the phone that made it: what it holds, the PDF, and a picture per issue.
    A report built again for the same walk replaces the earlier one, and what has since been done about
    its issues (given out, fixed, closed) is kept.
    """
    try:
        about = json.loads(meta)
    except ValueError:
        about = None
    listed = (about.get("issues") or []) if isinstance(about, dict) else None
    if not isinstance(listed, list):
        raise HTTPException(422, "The report's details could not be read.")
    walk = _line(about.get("walk"), 40)
    if not walk:
        raise HTTPException(422, "The report does not say which walk it is.")
    document = await pdf.read(MAX_PDF_BYTES + 1)
    if len(document) > MAX_PDF_BYTES:
        raise HTTPException(413, "That report is too large to send.")
    if not document.startswith(b"%PDF"):
        raise HTTPException(422, "That file is not a PDF report.")
    pictures: dict[str, bytes] = {}
    for upload in photos:
        raw = await upload.read(MAX_PICTURE_BYTES + 1)
        if 0 < len(raw) <= MAX_PICTURE_BYTES:
            pictures[Path(upload.filename or "").name] = raw

    report = db.scalars(select(Report).where(Report.account_id == account.id, Report.walk == walk)).first()
    fresh = report is None
    if fresh:
        report = Report(id=uuid4().hex, account_id=account.id, walk=walk)
        db.add(report)
    verdict = _line(about.get("review"), 12).upper()
    report.company_id = account.company_id
    report.template = _line(about.get("template"), 20)
    report.title = _line(about.get("title"), 80) or "Inspection report"
    report.place = _line(about.get("place"), 200)
    report.person = _line(about.get("person"), 120) or account.name
    report.built_at = _moment(about.get("built_at"))
    report.received_at = now()
    report.verdict = verdict if verdict in VERDICTS else ""
    report.verdict_reason = str(about.get("review_reason") or "").strip()[:2000]
    report.signed = bool(about.get("signed"))
    report.pdf = f"{report.id}.pdf"
    report.pdf_name = _line(about.get("file"), 160) or "DeskLess-report.pdf"
    folder = settings().reports_dir
    folder.mkdir(parents=True, exist_ok=True)
    (folder / report.pdf).write_bytes(document)
    db.flush()

    known = {issue.client_id: issue for issue in db.scalars(select(Issue).where(Issue.report_id == report.id))}
    kept = 0
    for at, item in enumerate(listed[:MAX_ISSUES]):
        if not isinstance(item, dict):
            continue
        client_id = _line(item.get("id"), 40) or f"n{at + 1}"
        issue = known.pop(client_id, None)
        if issue is None:
            issue = Issue(id=uuid4().hex, report_id=report.id, client_id=client_id, author_id=account.id, status=OPEN)
            db.add(issue)
        severity = _line(item.get("severity"), 10).upper()
        issue.company_id = account.company_id
        issue.number = at + 1
        issue.site = report.place
        issue.title = _line(item.get("title"), 200)
        issue.note = str(item.get("note") or "").strip()[:4000]
        issue.location = _line(item.get("location"), 200)
        issue.category = _line(item.get("category"), 60)
        issue.severity = severity if severity in SEVERITIES else "MEDIUM"
        issue.owner = _line(item.get("owner"), 120)
        issue.due_text = _line(item.get("due"), 60)
        issue.ai_check = _line(item.get("check"), 12).upper()
        issue.seen = str(item.get("seen") or "").strip()[:1000]
        issue.measured = str(item.get("measured") or "").strip()[:1000]
        issue.lat, issue.lon = _number(item.get("lat")), _number(item.get("lon"))
        issue.captured_at = _moment(item.get("captured_at"))
        issue.photo = _keep_picture(pictures.get(_line(item.get("photo"), 80)), f"{issue.id}.jpg") or issue.photo
        # Until someone at the office takes the issue up, the phone's word on it stands: its deadline,
        # and whether the inspector already saw it repaired on site.
        if issue.assigned_to is None and issue.status in (OPEN, CLOSED):
            issue.due_on = _due(issue.due_text)
            repair = _keep_picture(pictures.get(_line(item.get("fix_photo"), 80)), f"{issue.id}-fix.jpg")
            issue.fix_photo = repair or issue.fix_photo
            if item.get("resolved"):
                issue.status = CLOSED
                issue.fixed_at = issue.closed_at = _moment(item.get("fixed_at"))
                issue.closed_by = f"{account.name}, on site"
            elif issue.status == CLOSED:
                issue.status, issue.closed_at, issue.closed_by = OPEN, None, ""
        kept += 1
    # An issue the phone no longer lists is dropped, unless work has already been done on it.
    for gone in known.values():
        if gone.status == OPEN:
            for name in (gone.photo, gone.fix_photo):
                if name:
                    (settings().issues_dir / name).unlink(missing_ok=True)
            db.delete(gone)
        else:
            kept += 1
    report.issue_count = kept
    where = f" for {report.place}" if report.place else ""
    note(
        db, "report", account.name,
        f"{account.name} sent the report{where}: {kept} issue{'s' if kept != 1 else ''}" if fresh else f"{account.name} sent the report{where} again",
        company_id=account.company_id, report_id=report.id, author_id=account.id,
    )
    db.commit()
    return {"id": report.id, "issues": kept, "updated": not fresh}


def _report_row(report: Report, names: dict[str, str], unsettled: dict[str, int]) -> dict:
    return {
        "id": report.id,
        "title": report.title,
        "template": report.template,
        "place": report.place,
        "person": report.person,
        "sent_by": names.get(report.account_id, ""),
        "built_at": when(report.built_at),
        "received_at": when(report.received_at),
        "issues": report.issue_count,
        "open_issues": unsettled.get(report.id, 0),
        "verdict": report.verdict,
        "verdict_reason": report.verdict_reason,
        "signed": report.signed,
        "pdf_name": report.pdf_name,
    }


@router.get("/reports")
def reports(actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> list[dict]:
    """The reports in view, newest first."""
    found = list(db.scalars(select(Report).where(sees_reports(db, actor)).order_by(Report.built_at.desc()).limit(300)))
    names = names_of(db, [report.account_id for report in found])
    ids = [report.id for report in found]
    unsettled = dict(
        db.execute(select(Issue.report_id, func.count()).where(Issue.report_id.in_(ids), Issue.status != CLOSED).group_by(Issue.report_id)).all()
    ) if ids else {}
    return [_report_row(report, names, unsettled) for report in found]


def _report(db: Session, actor: Actor, report_id: str) -> Report:
    report = db.scalars(select(Report).where(Report.id == report_id, sees_reports(db, actor))).first()
    if report is None:
        raise HTTPException(404, "No such report.")
    return report


@router.get("/reports/{report_id}/pdf")
def report_pdf(report_id: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> FileResponse:
    report = _report(db, actor, report_id)
    path = settings().reports_dir / report.pdf
    if not path.is_file():
        raise HTTPException(404, "The PDF is not on the server.")
    return FileResponse(path, media_type="application/pdf", filename=report.pdf_name, content_disposition_type="inline")


# ---------------------------------------------------------------- issues


def _overdue(issue: Issue, day: date) -> bool:
    return issue.status in (OPEN, ASSIGNED) and issue.due_on is not None and issue.due_on < day


def _issue_row(issue: Issue, names: dict[str, str], day: date) -> dict:
    return {
        "id": issue.id,
        "report_id": issue.report_id,
        "number": issue.number,
        "title": issue.title or "Untitled issue",
        "note": issue.note,
        "site": issue.site,
        "location": issue.location,
        "category": issue.category,
        "severity": issue.severity,
        "owner": issue.owner,
        "due_text": issue.due_text,
        "due_on": issue.due_on.isoformat() if issue.due_on else None,
        "overdue": _overdue(issue, day),
        "check": issue.ai_check,
        "seen": issue.seen,
        "measured": issue.measured,
        "lat": issue.lat,
        "lon": issue.lon,
        "captured_at": when(issue.captured_at),
        "reported_by": names.get(issue.author_id, ""),
        "has_photo": bool(issue.photo),
        "status": issue.status,
        "assigned_to": issue.assigned_to,
        "assigned_to_name": names.get(issue.assigned_to, "") if issue.assigned_to else "",
        "assigned_by": issue.assigned_by,
        "assigned_at": when(issue.assigned_at),
        "fix_note": issue.fix_note,
        "has_fix_photo": bool(issue.fix_photo),
        "fixed_at": when(issue.fixed_at),
        "closed_at": when(issue.closed_at),
        "closed_by": issue.closed_by,
    }


def _rows(db: Session, found: list[Issue]) -> list[dict]:
    names = names_of(db, [one for issue in found for one in (issue.author_id, issue.assigned_to)])
    day = today()
    return [_issue_row(issue, names, day) for issue in found]


@router.get("/issues")
def issues(report: str = "", actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> list[dict]:
    """The issues in view, newest first; those of one report in the order of the walk."""
    query = select(Issue).where(sees_issues(db, actor))
    if report:
        query = query.where(Issue.report_id == report).order_by(Issue.number.asc())
    else:
        query = query.order_by(Issue.captured_at.desc()).limit(1000)
    return _rows(db, list(db.scalars(query)))


def _issue(db: Session, actor: Actor, issue_id: str) -> Issue:
    issue = db.scalars(select(Issue).where(Issue.id == issue_id, sees_issues(db, actor))).first()
    if issue is None:
        raise HTTPException(404, "No such issue.")
    return issue


@router.get("/issues/{issue_id}")
def issue_detail(issue_id: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """One issue with everything that has happened to it, oldest first."""
    issue = _issue(db, actor, issue_id)
    history = db.scalars(select(Event).where(Event.issue_id == issue.id).order_by(Event.id.asc()))
    return _rows(db, [issue])[0] | {"history": [{"at": when(event.at), "kind": event.kind, "text": event.text} for event in history]}


def _picture(name: str) -> FileResponse:
    path = settings().issues_dir / name if name else None
    if path is None or not path.is_file():
        raise HTTPException(404, "No picture.")
    return FileResponse(path, media_type="image/jpeg")


@router.get("/issues/{issue_id}/photo")
def issue_photo(issue_id: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> FileResponse:
    return _picture(_issue(db, actor, issue_id).photo)


@router.get("/issues/{issue_id}/fix-photo")
def issue_fix_photo(issue_id: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> FileResponse:
    return _picture(_issue(db, actor, issue_id).fix_photo)


def _lead(actor: Actor) -> Account:
    if not may_direct(actor):
        raise HTTPException(403, "Only a manager or the managing director gives out work and checks it.")
    return actor.account


def _technicians(db: Session, lead: Account):
    """The technicians this person may give work to: a manager their own, a managing director the company's."""
    query = select(Account).where(Account.role == "technical", Account.status == APPROVED, Account.company_id == lead.company_id)
    if lead.role == "manager":
        query = query.where(Account.reports_to == lead.id)
    return query


@router.get("/technicians")
def technicians(actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> list[dict]:
    """Who an issue can be given to, with how much each already has."""
    found = list(db.scalars(_technicians(db, _lead(actor)).order_by(Account.name.asc())))
    ids = [account.id for account in found]
    busy = dict(
        db.execute(select(Issue.assigned_to, func.count()).where(Issue.assigned_to.in_(ids), Issue.status == ASSIGNED).group_by(Issue.assigned_to)).all()
    ) if ids else {}
    return [{"id": account.id, "name": account.name, "unique_id": account.unique_id, "open_work": busy.get(account.id, 0)} for account in found]


def _about(issue: Issue) -> dict:
    return {"company_id": issue.company_id, "report_id": issue.report_id, "issue_id": issue.id, "author_id": issue.author_id, "assignee_id": issue.assigned_to}


class Assign(BaseModel):
    to: str
    #: The day it is to be done by, as YYYY-MM-DD; empty keeps the deadline the inspector gave.
    due_on: str = ""


@router.post("/issues/{issue_id}/assign")
def assign(
    issue_id: str,
    body: Assign,
    actor: Actor = Depends(current_actor),
    db: Session = Depends(get_db),
    post: mailer.Mailer = Depends(get_mailer),
) -> dict:
    """Gives the issue to a technician, with a day to have it done by."""
    lead = _lead(actor)
    issue = _issue(db, actor, issue_id)
    if issue.status not in (OPEN, ASSIGNED):
        raise HTTPException(409, "This issue is already fixed or closed. Reopen it to give it out again.")
    technician = db.scalars(_technicians(db, lead).where(Account.id == body.to)).first()
    if technician is None:
        raise HTTPException(422, "Choose one of your technicians.")
    if body.due_on.strip():
        try:
            issue.due_on = date.fromisoformat(body.due_on.strip())
        except ValueError:
            raise HTTPException(422, "That date does not look right.")
    issue.assigned_to = technician.id
    issue.assigned_by = lead.name
    issue.assigned_at = now()
    issue.status = ASSIGNED
    due = issue.due_on.strftime("%d %b %Y").lstrip("0") if issue.due_on else issue.due_text
    note(db, "assigned", lead.name, f"{lead.name} gave it to {technician.name}" + (f", due {due}" if due else ""), **_about(issue))
    db.commit()
    try:
        post.send(technician.email, *mailer.assigned_email(technician.name, issue.title or "Untitled issue", issue.site, due, lead.name))
    except mailer.MailError:
        pass
    return _rows(db, [issue])[0]


@router.post("/issues/{issue_id}/fixed")
async def fixed(
    issue_id: str,
    note_text: str = Form("", alias="note"),
    photo: UploadFile = File(...),
    account: Account = Depends(current_account),
    db: Session = Depends(get_db),
) -> dict:
    """The technician says the issue is fixed, and shows it with a picture of the repair."""
    actor = Actor(account=account)
    issue = _issue(db, actor, issue_id)
    if issue.assigned_to != account.id:
        raise HTTPException(403, "This issue was not given to you.")
    if issue.status != ASSIGNED:
        raise HTTPException(409, "This issue is not waiting to be fixed.")
    raw = await photo.read(MAX_PICTURE_BYTES + 1)
    if not raw:
        raise HTTPException(422, "Add a photo of the repair.")
    if len(raw) > MAX_PICTURE_BYTES:
        raise HTTPException(422, "That photo is too large.")
    issue.fix_photo = save_photo(raw, settings().issues_dir, f"{issue.id}-fix.jpg", PICTURE_EDGE)
    issue.fix_note = note_text.strip()[:2000]
    issue.fixed_at = now()
    issue.status = FIXED
    note(db, "fixed", account.name, f"{account.name} marked it fixed" + (f": {issue.fix_note}" if issue.fix_note else ""), **_about(issue))
    db.commit()
    return _rows(db, [issue])[0]


class Remark(BaseModel):
    note: str = ""


@router.post("/issues/{issue_id}/close")
def close(issue_id: str, body: Remark = Remark(), actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """The repair is checked and accepted; or the issue is closed as needing nothing done."""
    lead = _lead(actor)
    issue = _issue(db, actor, issue_id)
    if issue.status == CLOSED:
        raise HTTPException(409, "This issue is already closed.")
    was_fixed = issue.status == FIXED
    issue.status = CLOSED
    issue.closed_at = now()
    issue.closed_by = lead.name
    remark = " ".join(body.note.split())[:500]
    note(
        db, "closed", lead.name,
        (f"{lead.name} checked the repair and closed it" if was_fixed else f"{lead.name} closed it") + (f": {remark}" if remark else ""),
        **_about(issue),
    )
    db.commit()
    return _rows(db, [issue])[0]


@router.post("/issues/{issue_id}/reopen")
def reopen(issue_id: str, body: Remark = Remark(), actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """The repair is not good enough, or the issue came back: it goes back to whoever had it."""
    lead = _lead(actor)
    issue = _issue(db, actor, issue_id)
    if issue.status not in (FIXED, CLOSED):
        raise HTTPException(409, "This issue is still open.")
    issue.status = ASSIGNED if issue.assigned_to else OPEN
    issue.closed_at, issue.closed_by, issue.fixed_at = None, "", None
    remark = " ".join(body.note.split())[:500]
    note(db, "reopened", lead.name, f"{lead.name} sent it back" + (f": {remark}" if remark else ""), **_about(issue))
    db.commit()
    return _rows(db, [issue])[0]


# ---------------------------------------------------------------- the numbers for a dashboard


def _local_day(moment: datetime | None) -> date | None:
    return (aware(moment) + timedelta(minutes=settings().utc_offset_minutes)).date() if moment else None


def _top(counts: dict[str, Counter], limit: int = 8) -> list[dict]:
    """The busiest few of a breakdown: for each name, how many issues in all and how many not yet closed."""
    rows = [{"name": name, "total": count["total"], "open": count["open"]} for name, count in counts.items()]
    return sorted(rows, key=lambda row: (-row["open"], -row["total"], row["name"]))[:limit]


@router.get("/dashboard")
def dashboard(actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """
    Everything a dashboard's first screen shows, worked out over what the person may see: a managing
    director's whole company, a manager's team, an inspector's own reports, a technician's own jobs.
    """
    found = list(db.scalars(select(Issue).where(sees_issues(db, actor))))
    sent = list(db.scalars(select(Report).where(sees_reports(db, actor))))
    day = today()
    week_ago = now() - timedelta(days=7)
    unsettled = [issue for issue in found if issue.status != CLOSED]
    status = Counter(issue.status for issue in found)
    tiles = {
        "reports": len(sent),
        "reports_week": sum(1 for report in sent if aware(report.built_at) >= week_ago),
        "doubtful_reports": sum(1 for report in sent if report.verdict in ("DOUBTFUL", "FAKE")),
        "issues": len(found),
        **{name: status.get(name, 0) for name in STATUSES},
        "unsettled": len(unsettled),
        "serious": sum(1 for issue in unsettled if issue.severity in SERIOUS),
        "overdue": sum(1 for issue in found if _overdue(issue, day)),
        "waiting_people": db.scalar(select(func.count()).select_from(Account).where(waiting_for(actor))) or 0,
    }
    if not actor.is_admin:
        # What is on this person's own hands, whatever else they may see.
        mine = [issue for issue in found if issue.assigned_to == actor.account.id]
        tiles |= {
            "my_jobs": sum(1 for issue in mine if issue.status == ASSIGNED),
            "my_fixed": sum(1 for issue in mine if issue.status == FIXED),
            "my_closed": sum(1 for issue in mine if issue.status == CLOSED),
            "my_overdue": sum(1 for issue in mine if _overdue(issue, day)),
        }

    by_severity = Counter(issue.severity for issue in unsettled)
    sites: dict[str, Counter] = defaultdict(Counter)
    kinds: dict[str, Counter] = defaultdict(Counter)
    for issue in found:
        for group, name in ((sites, issue.site or "No site named"), (kinds, issue.category or "Not sorted")):
            group[name]["total"] += 1
            group[name]["open"] += issue.status != CLOSED

    days = [day - timedelta(days=back) for back in range(13, -1, -1)]
    reported = Counter(_local_day(issue.captured_at) for issue in found)
    closed = Counter(_local_day(issue.closed_at) for issue in found if issue.status == CLOSED)
    trend = [{"day": one.isoformat(), "reported": reported.get(one, 0), "closed": closed.get(one, 0)} for one in days]

    teams = []
    if actor.is_admin or actor.role == "md":
        # Each lead with the work of the people who answer to them. An issue counts for the team of
        # whoever reported it.
        people = list(db.scalars(select(Account).where(Account.status == APPROVED, Account.id.in_({issue.author_id for issue in found} | {report.account_id for report in sent}))))
        lead_of_author = {person.id: (person.id if person.role in ("md", "manager") else person.reports_to) for person in people}
        per_lead: dict[str, Counter] = defaultdict(Counter)
        for report in sent:
            per_lead[lead_of_author.get(report.account_id)]["reports"] += 1
        for issue in found:
            count = per_lead[lead_of_author.get(issue.author_id)]
            count["issues"] += 1
            count["open"] += issue.status != CLOSED
            count["overdue"] += _overdue(issue, day)
            count["closed"] += issue.status == CLOSED
        names = names_of(db, per_lead.keys())
        teams = sorted(
            ({"lead": names.get(lead, "No manager"), **{key: count[key] for key in ("reports", "issues", "open", "overdue", "closed")}} for lead, count in per_lead.items()),
            key=lambda row: (-row["open"], row["lead"]),
        )

    recent = db.scalars(select(Event).where(sees_events(db, actor)).order_by(Event.id.desc()).limit(25))
    titles = {issue.id: issue.title or "Untitled issue" for issue in found}
    return {
        "me": {"role": "admin", "role_name": "Administrator", "name": "Administrator", "email": actor.admin, "organisation": "All companies"}
        if actor.is_admin
        else profile(db, actor.account) | {"id": actor.account.id},
        "may_direct": may_direct(actor),
        "today": day.isoformat(),
        "tiles": tiles,
        "by_severity": {name: by_severity.get(name, 0) for name in SEVERITIES},
        "by_site": _top(sites),
        "by_type": _top(kinds),
        "trend": trend,
        "teams": teams,
        "activity": [
            {"at": when(event.at), "kind": event.kind, "text": event.text, "issue_id": event.issue_id, "about": titles.get(event.issue_id, "")}
            for event in recent
        ],
    }
