"""Health/status and the demo walk-through."""
from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.orm import Session

from ..db import get_db
from ..schemas import InspectionOut
from ..services import demo, llm, speech
from .inspections import inspection_out

router = APIRouter(prefix="/api", tags=["system"])


@router.get("/health")
def health(db: Session = Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
        database = True
    except Exception:
        database = False
    return {"status": "ok" if database else "degraded", "database": database, "speech": speech.status(), "llm": llm.status()}


@router.post("/demo", response_model=InspectionOut, status_code=201)
def load_demo(db: Session = Depends(get_db)):
    insp = demo.seed(db)
    db.refresh(insp)
    return inspection_out(insp)
