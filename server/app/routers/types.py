"""
Report types. DeskLess is not for one trade: the app comes with a type for a building site, a factory, a
campus, an office, a hospital and more, and a general one for any place. A company whose place is none of
those makes its own here: a name, what is checked, what the place is called and the kinds of issue. Its
people's phones fetch it with their details, so a new kind of place needs no new version of the app.
"""

import json
import secrets

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import LEADS, Account, Company, ReportType
from ..service import Actor, current_actor, note, when

router = APIRouter(prefix="/api", tags=["report types"])

#: The types every phone has, by the name a walk is stored under, with the name people see.
BUILT_IN = {
    "GENERAL": "General inspection", "SITE": "Site inspection", "FACTORY": "Factory round", "WAREHOUSE": "Warehouse inspection",
    "CAMPUS": "Campus inspection", "OFFICE": "Office inspection", "RESIDENTIAL": "Apartment inspection", "RETAIL": "Mall inspection",
    "HOTEL": "Hotel inspection", "HOSPITAL": "Hospital round", "MEDICATION": "Medication audit", "COMMUNITY": "Community issue",
}
#: How many of its own a company may have, and how many kinds of issue each may sort into besides "Other".
MAX_OWN = 20
MAX_KINDS = 7


def _line(value, limit: int) -> str:
    return " ".join(str(value or "").split())[:limit]


def as_told(kind: ReportType) -> dict:
    """An own type as the app and the dashboard are given it."""
    return {
        "key": kind.key, "title": kind.title, "checks": kind.checks, "place": kind.place,
        "categories": json.loads(kind.categories or "[]"), "serious": kind.serious,
        "made_by": kind.made_by, "made_at": when(kind.made_at),
    }


def of_company(db: Session, company_id: str | None) -> list[ReportType]:
    if not company_id:
        return []
    return list(db.scalars(select(ReportType).where(ReportType.company_id == company_id).order_by(ReportType.id.asc())))


def for_phone(db: Session, account: Account) -> dict:
    """What a phone is told with the person's details: the company's own types and the one it usually starts with."""
    firm = db.get(Company, account.company_id) if account.company_id else None
    return {"report_types": [as_told(kind) for kind in of_company(db, account.company_id)], "report_type": (firm.report_type or "") if firm else ""}


def _firm(db: Session, actor: Actor, leads: bool = False) -> Company:
    """The asker's company, when they are its managing director (or, with [leads], one of its managers)."""
    allowed = LEADS if leads else ("md",)
    firm = db.get(Company, actor.account.company_id) if actor.role in allowed and actor.account.company_id else None
    if firm is None:
        raise HTTPException(403, "Only the company's managing director or a manager makes report types." if leads else "Only the company's managing director sets this.")
    return firm


@router.get("/report-types")
def report_types(actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """The types that come with the app, the asker's company's own, and the one it usually starts a walk with."""
    company_id = None if actor.is_admin else actor.account.company_id
    firm = db.get(Company, company_id) if company_id else None
    return {
        "built_in": [{"key": key, "title": title} for key, title in BUILT_IN.items()],
        "own": [as_told(kind) for kind in of_company(db, company_id)],
        "usual": (firm.report_type or "") if firm else "",
        "may_make": actor.role in LEADS and firm is not None,
        "may_set": actor.role == "md" and firm is not None,
    }


class Make(BaseModel):
    title: str
    #: What is walked and looked at, e.g. "buses, bays and the fuel point of a bus depot".
    checks: str = ""
    #: What the place is called, e.g. "Depot".
    place: str = ""
    #: The kinds an issue can be sorted into; "Other" is always added.
    categories: list[str] = []
    #: What counts as serious in this kind of place, in the company's own words.
    serious: str = ""


@router.post("/report-types")
def make(body: Make, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """A managing director or manager makes a report type for their company's kind of place."""
    firm = _firm(db, actor, leads=True)
    title = _line(body.title, 40)
    if len(title) < 3:
        raise HTTPException(422, "Give the report type a name of at least three letters.")
    taken = {known.lower() for known in BUILT_IN.values()} | {kind.title.lower() for kind in of_company(db, firm.id)}
    if title.lower() in taken:
        raise HTTPException(409, "There is already a report type of that name.")
    if (db.scalar(select(func.count()).select_from(ReportType).where(ReportType.company_id == firm.id)) or 0) >= MAX_OWN:
        raise HTTPException(409, f"A company can have {MAX_OWN} report types of its own. Remove one first.")
    kinds: list[str] = []
    for one in body.categories:
        name = _line(one, 40)
        if name and name.lower() != "other" and name.lower() not in {kept.lower() for kept in kinds}:
            kinds.append(name)
    kind = ReportType(
        company_id=firm.id, key="OWN-" + secrets.token_hex(4).upper(), title=title, checks=_line(body.checks, 200), place=_line(body.place, 30),
        categories=json.dumps(kinds[:MAX_KINDS]), serious=_line(body.serious, 300), made_by=actor.name,
    )
    db.add(kind)
    note(db, "joined", actor.name, f"{actor.name} made the report type “{title}”", company_id=firm.id, author_id=actor.account.id)
    db.commit()
    return as_told(kind)


@router.delete("/report-types/{key}")
def remove(key: str, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """Takes one of the company's own types away. Reports already made with it keep its name."""
    firm = _firm(db, actor)
    kind = db.scalars(select(ReportType).where(ReportType.company_id == firm.id, ReportType.key == key)).first()
    if kind is None:
        raise HTTPException(404, "No such report type.")
    if firm.report_type == kind.key:
        firm.report_type = ""
    db.delete(kind)
    db.commit()
    return {"removed": key}


class Usual(BaseModel):
    key: str = ""


@router.post("/company/report-type")
def set_usual(body: Usual, actor: Actor = Depends(current_actor), db: Session = Depends(get_db)) -> dict:
    """The managing director says which report type the company's walks start with; empty leaves it to each person."""
    firm = _firm(db, actor)
    key = body.key.strip()
    if key and key not in BUILT_IN and key not in {kind.key for kind in of_company(db, firm.id)}:
        raise HTTPException(422, "Choose one of the report types.")
    firm.report_type = key
    db.commit()
    return {"usual": key}
