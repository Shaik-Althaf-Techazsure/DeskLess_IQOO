"""Tables: an inspection (one site walk-through) has many items (one issue each)."""
from datetime import date, datetime, timezone

from sqlalchemy import JSON, Date, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


def _now() -> datetime:
    return datetime.now(timezone.utc)


class Inspection(Base):
    __tablename__ = "inspections"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    site_name: Mapped[str] = mapped_column(String(120))
    area: Mapped[str] = mapped_column(String(120), default="")
    inspector: Mapped[str] = mapped_column(String(120), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    items: Mapped[list["Item"]] = relationship(
        back_populates="inspection", cascade="all, delete-orphan", order_by="Item.seq"
    )


class Item(Base):
    __tablename__ = "items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    inspection_id: Mapped[int] = mapped_column(ForeignKey("inspections.id", ondelete="CASCADE"), index=True)
    seq: Mapped[int] = mapped_column(Integer, default=1)

    # Captured evidence
    media_key: Mapped[str] = mapped_column(String(40))
    has_photo: Mapped[bool] = mapped_column(default=False)
    audio_file: Mapped[str | None] = mapped_column(String(60), nullable=True)
    note_text: Mapped[str | None] = mapped_column(Text, nullable=True)      # typed instead of spoken
    transcript: Mapped[str | None] = mapped_column(Text, nullable=True)     # what was said, as spoken
    transcript_en: Mapped[str | None] = mapped_column(Text, nullable=True)  # English rendering
    language: Mapped[str | None] = mapped_column(String(12), nullable=True)

    # Structured item
    title: Mapped[str] = mapped_column(String(200), default="")
    location: Mapped[str | None] = mapped_column(String(200), nullable=True)
    severity: Mapped[str] = mapped_column(String(10), default="Medium")
    owner: Mapped[str | None] = mapped_column(String(120), nullable=True)
    due: Mapped[str | None] = mapped_column(String(60), nullable=True)
    due_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    engine: Mapped[str] = mapped_column(String(60), default="manual")       # "llm:<model>", "rules" or "manual"
    needs_review: Mapped[list] = mapped_column(JSON, default=list)          # field names left for a person to confirm

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, onupdate=_now)

    inspection: Mapped[Inspection] = relationship(back_populates="items")
