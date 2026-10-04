"""Request and response shapes for the API."""
from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Severity = Literal["High", "Medium", "Low"]


class InspectionCreate(BaseModel):
    site_name: str = Field(min_length=1, max_length=120)
    area: str = Field(default="", max_length=120)
    inspector: str = Field(default="", max_length=120)


class InspectionUpdate(BaseModel):
    site_name: str | None = Field(default=None, min_length=1, max_length=120)
    area: str | None = Field(default=None, max_length=120)
    inspector: str | None = Field(default=None, max_length=120)


class ItemUpdate(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    location: str | None = Field(default=None, max_length=200)
    severity: Severity | None = None
    owner: str | None = Field(default=None, max_length=120)
    due: str | None = Field(default=None, max_length=60)
    transcript_en: str | None = None


class ItemOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    inspection_id: int
    seq: int
    photo_url: str | None = None
    thumb_url: str | None = None
    audio_url: str | None = None
    note_text: str | None
    transcript: str | None
    transcript_en: str | None
    language: str | None
    title: str
    location: str | None
    severity: Severity
    owner: str | None
    due: str | None
    due_date: date | None
    engine: str
    needs_review: list[str]
    created_at: datetime
    warnings: list[str] = []


class InspectionSummary(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    site_name: str
    area: str
    inspector: str
    created_at: datetime
    item_count: int = 0
    high_count: int = 0


class InspectionOut(InspectionSummary):
    items: list[ItemOut] = []


class ActionsOut(BaseModel):
    text: str
    count: int
