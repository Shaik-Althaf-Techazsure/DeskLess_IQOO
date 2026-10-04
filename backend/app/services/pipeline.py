"""The capture pipeline: recording -> transcript -> structured item."""
import logging
import os
from datetime import date

from ..models import Inspection, Item
from . import speech, storage, structure

log = logging.getLogger("deskless.pipeline")


def process(item: Item, insp: Inspection, today: date | None = None) -> list[str]:
    """Fills the item's transcript and structured fields in place. Returns warnings for the user."""
    warnings: list[str] = []
    note = (item.note_text or "").strip()

    if item.audio_file:
        path = os.path.join(storage.folder(item.media_key), item.audio_file)
        try:
            tr = speech.transcribe(path)
            item.transcript, item.language, item.transcript_en = (tr.text or None), tr.language, tr.english
            if not tr.text:
                warnings.append("No speech was detected in the recording.")
        except Exception as exc:
            log.warning("transcription failed: %s", exc)
            warnings.append("Speech-to-text is unavailable, so the recording was saved without a transcript.")
    elif note and not item.transcript:
        item.transcript = note  # a typed note stands in for the spoken one

    source = (item.transcript_en or item.transcript or note or "").strip()
    photo = storage.photo_path(item.media_key) if item.has_photo else None
    result, extra = structure.run(source, item.transcript, insp.area, photo, today)
    warnings += extra

    item.title = result.title
    item.location = result.location
    item.severity = result.severity
    item.owner = result.owner
    item.due = result.due
    item.due_date = result.due_date
    item.engine = result.engine
    item.needs_review = list(result.needs_review)
    if result.english and not item.transcript_en and result.english.strip() != (item.transcript or "").strip():
        item.transcript_en = result.english
    return warnings
