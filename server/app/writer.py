"""
The writing model: an open-source language model that runs on the server's own machine and words what
the assistant shows. It is given one issue at a time and writes a summary of it, and, once people have
written to each other about the issue, what their messages come to. Nothing is sent anywhere else.

It works in the background. An issue is on the dashboards the moment it arrives, with wording put
together from its own fields; the model's wording takes its place when it is ready, and is kept, so the
model is asked once for each telling of an issue and no page ever waits for it. With no model running,
everything else works the same.

The model is spoken to in the way both llama.cpp's server and Ollama understand (/v1/chat/completions),
so either can be the one behind AI_URL.
"""

import json
import logging
import threading
import time
import urllib.error
import urllib.request

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import pulse
from .assistant import mark_of
from .config import settings
from .db import SessionLocal
from .models import CLOSED, ROLE_NAMES, SEVERITIES, Brief, Issue, Message, now

log = logging.getLogger("deskless.ai")

#: How long the model is given for one issue, and how often a failing issue is tried again.
ANSWER_SECONDS = 180
MAX_TRIES = 3
#: How many issues not yet closed are looked over for one that needs writing.
LOOKED_OVER = 400
#: From how many messages on, the model says what they come to.
TALK_FROM = 2

_wake = threading.Event()
_state = {"ready": False, "model": "", "checked": 0.0, "written": 0, "last_ms": 0, "busy": False}


def nudge() -> None:
    """Something changed that the model may have to write about."""
    _wake.set()


def status() -> dict:
    """What the dashboards are told about the model."""
    return {"on": bool(settings().ai_url) and _state["ready"], "set": bool(settings().ai_url), "model": _state["model"], "busy": _state["busy"], "written": _state["written"], "last_ms": _state["last_ms"]}


def _name(model_id: str) -> str:
    """A model's name as a person would say it: without folders and the file's ending."""
    name = model_id.replace("\\", "/").rstrip("/").split("/")[-1]
    return name[:-5] if name.lower().endswith(".gguf") else name


def reachable() -> bool:
    """Whether a model answers at AI_URL, asked at most every half minute."""
    config = settings()
    if not config.ai_url:
        return False
    if time.time() - _state["checked"] < 30:
        return _state["ready"]
    _state["checked"] = time.time()
    try:
        with urllib.request.urlopen(config.ai_url + "/v1/models", timeout=5) as answer:
            listed = json.load(answer).get("data") or []
        _state["model"] = config.ai_model or (_name(str(listed[0].get("id", ""))) if listed else "") or "local model"
        _state["ready"] = True
    except (OSError, ValueError, AttributeError, IndexError):
        # Not running, or still loading its weights: the built-in wording stands until it answers.
        _state["ready"] = False
    return _state["ready"]


def ask(prompt: str) -> str | None:
    """One question to the model; its answer as text, or nothing when it did not answer."""
    config = settings()
    body = {
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
        "max_tokens": 260,
        "stream": False,
        "response_format": {"type": "json_object"},
    }
    if config.ai_model:
        body["model"] = config.ai_model
    request = urllib.request.Request(config.ai_url + "/v1/chat/completions", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=ANSWER_SECONDS) as answer:
            return json.load(answer)["choices"][0]["message"]["content"]
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        log.warning("The writing model did not answer: %s", error)
        _state["checked"] = 0.0
        return None


def worth_writing(issue: Issue) -> bool:
    """
    Whether there is anything for the model to summarise. Shown an issue with no note and no reading of
    its picture, a small model made a story up; such an issue keeps the wording put together from its fields.
    """
    return bool((issue.note or "").strip() or (issue.seen or "").strip())


def prompt_for(issue: Issue) -> str:
    """What the model is shown to summarise an issue: the issue as the phone told it, and nothing else."""
    lines = [
        "You help the team that looks after a place: a building site, a campus, an office, an apartment block, a mall or a hospital. "
        "Below is one issue found there during an inspection.",
        "",
        f"Issue: {issue.title or 'Untitled'}",
        f"Place: {', '.join(part for part in (issue.site, issue.location) if part) or 'not stated'}",
        f"Type: {issue.category or 'not stated'}",
        f"Severity: {issue.severity.title()}",
        f"Inspector's note: {' '.join((issue.note or '').split())[:1200] or 'none'}",
    ]
    if issue.seen:
        lines.append(f"What the photo shows: {' '.join(issue.seen.split())[:500]}")
    if issue.due_text:
        lines.append(f"Deadline said: {issue.due_text}")
    lines += [
        "",
        "Answer with one JSON object with exactly this key:",
        '"summary": one or two short, plain sentences saying what is wrong and where. Say nothing that is not written above: '
        "no causes, consequences, repairs, parts or measurements of your own.",
    ]
    return "\n".join(lines)


def prompt_for_talk(issue: Issue, thread: list[Message]) -> str:
    """What the model is shown to say what the messages come to: the messages, and only the issue's title."""
    lines = [f"People who look after a place wrote these messages to each other about one issue: {issue.title or 'Untitled'}.", "", "The messages, oldest first:"]
    for message in thread[-8:]:
        role = "Administrator" if message.author_role == "admin" else ROLE_NAMES.get(message.author_role, "")
        lines.append(f"- {message.author}{' (' + role + ')' if role else ''} to {message.to_name}: {' '.join(message.text.split())[:500]}")
    lines += [
        "",
        "Answer with one JSON object with exactly this key:",
        '"talk": one or two plain sentences saying who asked for what and what was answered, and what is still unanswered. Use only the messages.',
    ]
    return "\n".join(lines)


def _read(answer: str | None, key: str, limit: int) -> str:
    """One text out of the model's answer, however loosely it wrapped it; empty when there is none."""
    if not answer:
        return ""
    start, end = answer.find("{"), answer.rfind("}")
    if start < 0 or end <= start:
        return ""
    try:
        told = json.loads(answer[start:end + 1])
    except ValueError:
        return ""
    value = told.get(key) if isinstance(told, dict) else None
    return " ".join(value.split())[:limit] if isinstance(value, str) else ""


def _next(db: Session) -> tuple[Issue, list[Message], Brief | None, bool, bool] | None:
    """
    The issue most in need of writing: not closed, the most severe first, then the newest. With it, whether
    its summary is to be written, and whether what its messages come to is.
    """
    waiting = list(db.scalars(select(Issue).where(Issue.status != CLOSED).order_by(Issue.captured_at.desc()).limit(LOOKED_OVER)))
    if not waiting:
        return None
    ids = [issue.id for issue in waiting]
    briefs = {brief.issue_id: brief for brief in db.scalars(select(Brief).where(Brief.issue_id.in_(ids)))}
    threads: dict[str, list[Message]] = {}
    for message in db.scalars(select(Message).where(Message.issue_id.in_(ids)).order_by(Message.id.asc())):
        threads.setdefault(message.issue_id, []).append(message)
    waiting.sort(key=lambda issue: SEVERITIES.index(issue.severity) if issue.severity in SEVERITIES else len(SEVERITIES))
    for issue in waiting:
        thread = threads.get(issue.id, [])
        brief = briefs.get(issue.id)
        summary = worth_writing(issue) and (brief is None or brief.mark != mark_of(issue) or (not brief.summary and brief.tries < MAX_TRIES))
        # One message needs no summing up; from the second on, each new one is news.
        talk = len(thread) >= TALK_FROM and (brief is None or brief.talk_upto != thread[-1].id)
        if summary or talk:
            return issue, thread, brief, summary, talk
    return None


def write_next(db: Session) -> bool:
    """Has the model write about one issue. False when nothing needs writing."""
    found = _next(db)
    if found is None:
        return False
    issue, thread, brief, summary, talk = found
    began = time.perf_counter()
    if brief is None:
        brief = Brief(issue_id=issue.id, tries=0, talk_upto=0)
        db.add(brief)
    _state["busy"] = True
    title = issue.title or "Untitled issue"
    engine = _state["model"] or settings().ai_model or "local model"
    concerned = dict(company_id=issue.company_id, people=[issue.author_id, issue.assigned_to], issue_id=issue.id, ai=True)
    try:
        if summary:
            mark = mark_of(issue)
            if brief.mark != mark:
                brief.mark, brief.tries, brief.summary = mark, 0, ""
            pulse.record("model", pulse.SERVER, pulse.WRITER, f"asked {engine} to summarise “{title}”", **concerned)
            written = _read(ask(prompt_for(issue)), "summary", 500)
            spent = round((time.perf_counter() - began) * 1000)
            if written:
                brief.summary = written
                brief.engine = engine
                brief.made_at = now()
                _state["written"] += 1
                _state["last_ms"] = spent
                pulse.record("model", pulse.WRITER, pulse.SERVER, f"{engine} summarised “{title}” in {spent / 1000:.1f} s", ms=spent, **concerned)
            else:
                # Kept with nothing in it, so the same telling is not tried for ever.
                brief.tries = (brief.tries or 0) + 1
                pulse.record("model", pulse.WRITER, pulse.SERVER, f"{engine} gave no usable summary of “{title}”", ms=spent, **concerned)
        if talk:
            # Tried once for each new message: an empty answer just leaves the messages to speak for themselves.
            pulse.record("model", pulse.SERVER, pulse.WRITER, f"asked {engine} what the messages about “{title}” come to", **concerned)
            brief.talk = _read(ask(prompt_for_talk(issue, thread)), "talk", 400)
            brief.talk_upto = thread[-1].id
            pulse.record("model", pulse.WRITER, pulse.SERVER, f"{engine} summed up the {len(thread)} messages about “{title}”" if brief.talk else f"{engine} had nothing to say of the messages about “{title}”", **concerned)
    finally:
        _state["busy"] = False
    db.commit()
    return True


def _run() -> None:
    while True:
        # Woken when a walk or a message arrives; otherwise a look every minute is enough.
        _wake.wait(60)
        _wake.clear()
        try:
            while reachable():
                with SessionLocal() as db:
                    if not write_next(db):
                        break
        except Exception:  # the dashboards must never go down with the writer
            log.exception("The writing model's turn failed")
            time.sleep(10)


_started = False


def start() -> None:
    """Starts the writer beside the server, once, when a model's address is set."""
    global _started
    if _started or not settings().ai_url:
        return
    _started = True
    threading.Thread(target=_run, name="deskless-writer", daemon=True).start()
    nudge()
