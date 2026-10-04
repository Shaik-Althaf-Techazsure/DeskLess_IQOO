"""Walk-throughs, their items, the PDF report and the action list."""
import re

from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from ..db import get_db
from ..models import Inspection, Item
from ..schemas import ActionsOut, InspectionCreate, InspectionOut, InspectionSummary, InspectionUpdate, ItemOut, ItemUpdate
from ..services import pipeline, report, rules, storage

router = APIRouter(prefix="/api", tags=["inspections"])


def item_out(item: Item, warnings: list[str] | None = None) -> ItemOut:
    out = ItemOut.model_validate(item)
    base = f"/api/media/{item.media_key}"
    if item.has_photo:
        out.photo_url, out.thumb_url = f"{base}/photo.jpg", f"{base}/thumb.jpg"
    if item.audio_file:
        out.audio_url = f"{base}/{item.audio_file}"
    out.warnings = warnings or []
    return out


def inspection_out(insp: Inspection) -> InspectionOut:
    return InspectionOut(
        id=insp.id, site_name=insp.site_name, area=insp.area, inspector=insp.inspector, created_at=insp.created_at,
        item_count=len(insp.items), high_count=sum(1 for i in insp.items if i.severity == "High"),
        items=[item_out(i) for i in insp.items],
    )


def _inspection(db: Session, iid: int) -> Inspection:
    insp = db.scalars(select(Inspection).where(Inspection.id == iid).options(selectinload(Inspection.items))).first()
    if insp is None:
        raise HTTPException(404, "walk-through not found")
    return insp


def _item(db: Session, item_id: int) -> Item:
    item = db.get(Item, item_id)
    if item is None:
        raise HTTPException(404, "item not found")
    return item


@router.get("/inspections", response_model=list[InspectionSummary])
def list_inspections(db: Session = Depends(get_db)):
    rows = db.scalars(select(Inspection).options(selectinload(Inspection.items)).order_by(Inspection.id.desc())).all()
    return [
        InspectionSummary(
            id=r.id, site_name=r.site_name, area=r.area, inspector=r.inspector, created_at=r.created_at,
            item_count=len(r.items), high_count=sum(1 for i in r.items if i.severity == "High"),
        )
        for r in rows
    ]


@router.post("/inspections", response_model=InspectionOut, status_code=201)
def create_inspection(body: InspectionCreate, db: Session = Depends(get_db)):
    insp = Inspection(site_name=body.site_name.strip(), area=body.area.strip(), inspector=body.inspector.strip())
    db.add(insp)
    db.commit()
    return inspection_out(_inspection(db, insp.id))


@router.get("/inspections/{iid}", response_model=InspectionOut)
def get_inspection(iid: int, db: Session = Depends(get_db)):
    return inspection_out(_inspection(db, iid))


@router.patch("/inspections/{iid}", response_model=InspectionOut)
def update_inspection(iid: int, body: InspectionUpdate, db: Session = Depends(get_db)):
    insp = _inspection(db, iid)
    for name, value in body.model_dump(exclude_unset=True).items():
        if value is not None:
            setattr(insp, name, value.strip())
    db.commit()
    return inspection_out(_inspection(db, iid))


@router.delete("/inspections/{iid}", status_code=204)
def delete_inspection(iid: int, db: Session = Depends(get_db)):
    insp = _inspection(db, iid)
    keys = [i.media_key for i in insp.items]
    db.delete(insp)
    db.commit()
    for key in keys:
        storage.remove(key)
    return Response(status_code=204)


@router.post("/inspections/{iid}/items", response_model=ItemOut, status_code=201)
def create_item(
    iid: int,
    photo: UploadFile | None = File(default=None),
    audio: UploadFile | None = File(default=None),
    note: str | None = Form(default=None),
    db: Session = Depends(get_db),
):
    insp = _inspection(db, iid)
    note = (note or "").strip() or None
    if photo is None and audio is None and note is None:
        raise HTTPException(422, "send a photo, a voice note or a typed note")
    key = storage.new_key()
    item = Item(inspection_id=insp.id, seq=max((i.seq for i in insp.items), default=0) + 1, media_key=key, note_text=note)
    try:
        if photo is not None:
            storage.save_photo(key, photo.file.read())
            item.has_photo = True
        if audio is not None:
            item.audio_file = storage.save_audio(key, audio.file.read(), audio.content_type, audio.filename)
    except storage.MediaError as exc:
        storage.remove(key)
        raise HTTPException(422, str(exc)) from exc
    warnings = pipeline.process(item, insp)
    db.add(item)
    db.commit()
    return item_out(item, warnings)


@router.get("/items/{item_id}", response_model=ItemOut)
def get_item(item_id: int, db: Session = Depends(get_db)):
    return item_out(_item(db, item_id))


@router.patch("/items/{item_id}", response_model=ItemOut)
def update_item(item_id: int, body: ItemUpdate, db: Session = Depends(get_db)):
    item = _item(db, item_id)
    changes = body.model_dump(exclude_unset=True)
    review = set(item.needs_review or [])
    for name, value in changes.items():
        if isinstance(value, str):
            value = value.strip() or None
        if name == "title":
            item.title = value or "Untitled issue"
        elif name == "severity":
            if value:
                item.severity = value
        elif name == "due":
            item.due = value
            item.due_date = rules.parse_due(value, item.created_at.date())[1] if value else None
        else:
            setattr(item, name, value)
        if value:
            review.discard(name)  # a person has confirmed this field
    item.needs_review = [n for n in ("title", "severity", "location", "owner", "due") if n in review]
    db.commit()
    return item_out(item)


@router.post("/items/{item_id}/reprocess", response_model=ItemOut)
def reprocess_item(item_id: int, db: Session = Depends(get_db)):
    item = _item(db, item_id)
    insp = _inspection(db, item.inspection_id)
    warnings = pipeline.process(item, insp, today=item.created_at.date())
    db.commit()
    return item_out(item, warnings)


@router.delete("/items/{item_id}", status_code=204)
def delete_item(item_id: int, db: Session = Depends(get_db)):
    item = _item(db, item_id)
    key = item.media_key
    db.delete(item)
    db.commit()
    storage.remove(key)
    return Response(status_code=204)


@router.get("/inspections/{iid}/report.pdf")
def report_pdf(iid: int, download: bool = False, db: Session = Depends(get_db)):
    insp = _inspection(db, iid)
    pdf = report.build(insp, list(insp.items))
    slug = re.sub(r"[^A-Za-z0-9]+", "-", f"{insp.site_name} {insp.area}").strip("-") or "site"
    disposition = "attachment" if download else "inline"
    return Response(
        content=pdf,
        media_type="application/pdf",
        headers={"Content-Disposition": f'{disposition}; filename="Site_Report_{slug}.pdf"', "Cache-Control": "no-store"},
    )


@router.get("/inspections/{iid}/actions", response_model=ActionsOut)
def actions(iid: int, db: Session = Depends(get_db)):
    insp = _inspection(db, iid)
    items = list(insp.items)
    return ActionsOut(text=report.actions_text(insp, items), count=len(items))
