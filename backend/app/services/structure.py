"""Turns a note (and photo) into a structured item: local LLM when available, rules otherwise.

Whatever the model says, owner, due date and location are only kept when the note itself supports them
(or, for a vision model, when the photo may). A small model that makes something up cannot put it in a report.
"""
import logging
import re
from datetime import date

from ..config import get_settings
from . import llm, rules
from .rules import SEVERITIES, Structured

log = logging.getLogger("deskless.structure")
_BLANK = {"", "null", "none", "n/a", "na", "unknown", "not specified", "not mentioned", "not stated", "-"}
_STOP = {"the", "and", "for", "with", "from", "this", "that", "near", "team", "site"}


def _clean(value) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return None if text.lower() in _BLANK else text


def _words(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-z0-9]+", text.lower()) if len(w) > 2 and w not in _STOP}


def _supported(value: str | None, source: str) -> bool:
    """True when at least one meaningful word of `value` appears in the source text."""
    return bool(value) and bool(_words(value) & _words(source))


def run(note: str, original: str | None, area: str, photo_path: str | None, today: date | None = None) -> tuple[Structured, list[str]]:
    """Returns the structured item and any warnings worth showing to the user."""
    today = today or date.today()
    warnings: list[str] = []
    said = " ".join(p for p in (note, original) if p)
    baseline = rules.structure(note, area, today)
    if original:  # owner / due / place may only be recognisable in the original wording
        other = rules.structure(original, area, today)
        baseline.owner = baseline.owner or other.owner
        if not baseline.due:
            baseline.due, baseline.due_date = other.due, other.due_date

    if not (llm.enabled() and llm.ready()):
        baseline.needs_review = [n for n in baseline.needs_review if getattr(baseline, n, None) in (None, "") or n == "severity"]
        return baseline, warnings

    try:
        data = llm.structure(note, original, area, photo_path)
    except Exception as exc:
        log.warning("local LLM failed, using rules: %s", exc)
        warnings.append("The local model did not answer, so the item was structured with built-in rules.")
        return baseline, warnings

    vision = bool(photo_path) and llm.status()["vision"]
    out = Structured(engine=f"llm:{get_settings().llm_model}")
    out.english = _clean(data.get("english"))

    title = _clean(data.get("title"))
    if title and (vision or _supported(title, f"{said} {out.english or ''}")):
        out.title = title[:200]
    else:
        out.title = rules.make_title(out.english or note) if (out.english or note) else baseline.title

    sev = str(data.get("severity", "")).capitalize()
    out.severity = sev if sev in SEVERITIES else baseline.severity

    owner = _clean(data.get("owner"))
    out.owner = baseline.owner or (owner if _supported(owner, said) else None)

    due_text = _clean(data.get("due"))
    if baseline.due:
        out.due, out.due_date = baseline.due, baseline.due_date
    elif due_text and due_text.lower() in said.lower():
        out.due, out.due_date = due_text[:60], rules.parse_due(due_text, today)[1]

    loc = _clean(data.get("location"))
    specific = rules.find_location(said, "")
    if specific:
        out.location = f"{area} · {specific}" if area and area.lower() not in specific.lower() else specific
    elif loc and (vision or _supported(loc, said)):
        out.location = loc if not area or area.lower() in loc.lower() else f"{area} · {loc}"
    else:
        out.location = area or None

    out.needs_review = [n for n in ("location", "owner", "due") if not getattr(out, n)]
    return out, warnings
