"""Optional local LLM (served by Ollama) that structures a note, and reads the photo when the model has vision."""
import base64
import json
import logging
import time

import httpx

from ..config import get_settings

log = logging.getLogger("deskless.llm")

SCHEMA = {
    "type": "object",
    "properties": {
        "english": {"type": "string"},
        "title": {"type": "string"},
        "location": {"type": ["string", "null"]},
        "severity": {"type": "string", "enum": ["High", "Medium", "Low"]},
        "owner": {"type": ["string", "null"]},
        "due": {"type": ["string", "null"]},
    },
    "required": ["english", "title", "location", "severity", "owner", "due"],
}

SYSTEM = """You turn a site inspector's note into ONE structured inspection item.
The note may be in English, Hindi, or a mix of both. A photo of the issue may be attached.

Return JSON with these fields:
- english: the note rewritten in plain English, in one or two sentences. Keep every fact, add none.
- title: the defect described in THIS note, in at most 8 English words.
- location: where the defect is, only if the note says so or it is clearly readable in the photo (a label or sign). Otherwise null.
- severity: "High" for safety hazards (electrical, fire, structural, fall risk, blocked exits, missing safety equipment),
  "Medium" for defects that need repair soon (cracks, leaks, seepage, damage), "Low" for cosmetic issues (paint, stains).
- owner: the trade or person that the note says must act. If the note names nobody, null.
- due: when the note says it must be done, in the note's own words. If the note gives no deadline, null.

Base every field on this note only. Never invent an owner, a deadline or a location; use null instead."""

_cache: dict = {"at": 0.0, "status": None}


def enabled() -> bool:
    return bool(get_settings().ollama_url)


def status(force: bool = False) -> dict:
    """Reachability of the Ollama server and whether the configured model is pulled. Cached for 15 s."""
    s = get_settings()
    base = {"configured": enabled(), "model": s.llm_model, "reachable": False, "model_ready": False, "vision": False}
    if not enabled():
        return base
    if not force and _cache["status"] is not None and time.time() - _cache["at"] < 15:
        return _cache["status"]
    try:
        with httpx.Client(base_url=s.ollama_url, timeout=3.0) as c:
            tags = c.get("/api/tags").json().get("models", [])
            base["reachable"] = True
            names = {m.get("name") for m in tags} | {m.get("model") for m in tags}
            wanted = s.llm_model if ":" in s.llm_model else s.llm_model + ":latest"
            if wanted in names:
                base["model_ready"] = True
                show = c.post("/api/show", json={"model": s.llm_model}).json()
                base["vision"] = "vision" in (show.get("capabilities") or [])
    except Exception as exc:
        log.debug("ollama not reachable: %s", exc)
    _cache.update(at=time.time(), status=base)
    return base


def ready() -> bool:
    return status()["model_ready"]


def structure(note: str, original: str | None, area: str, photo_path: str | None) -> dict:
    """Ask the local model for the structured item. Raises on any failure so the caller can fall back to rules."""
    s = get_settings()
    st = status()
    if not st["model_ready"]:
        raise RuntimeError("local model is not ready")
    parts = [f"Site area: {area or 'not given'}", f"Inspector's note: {note or '(no note; rely on the photo)'}"]
    if original and original.strip() and original.strip() != (note or "").strip():
        parts.append(f"Original words as spoken: {original}")
    user: dict = {"role": "user", "content": "\n".join(parts)}
    if photo_path and st["vision"]:
        with open(photo_path, "rb") as fh:
            user["images"] = [base64.b64encode(fh.read()).decode()]
    payload = {
        "model": s.llm_model,
        "messages": [{"role": "system", "content": SYSTEM}, user],
        "format": SCHEMA,
        "stream": False,
        "options": {"temperature": 0},
    }
    with httpx.Client(base_url=s.ollama_url, timeout=s.llm_timeout_s) as c:
        r = c.post("/api/chat", json=payload)
        r.raise_for_status()
        data = json.loads(r.json()["message"]["content"])
    if not isinstance(data, dict) or not str(data.get("title", "")).strip():
        raise ValueError("model returned no usable item")
    return data
