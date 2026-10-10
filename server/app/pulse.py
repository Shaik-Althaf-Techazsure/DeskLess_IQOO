"""
What moves between whom, the moment it moves: a phone asking the server, a walk arriving, something
sent in by a user of a place, an issue passed from one person to another, given out, fixed and closed,
the assistant ranking it and choosing who is told, the writing model's turn, the assistant's own rounds,
an email going out, a phone being told that its desk has changed. Each is one pulse: from one node to
another, with a few words, and with who is concerned, so that each viewer is shown only what they may see.

The live linkage page draws these as they happen. They are kept in memory, the last few hundred, so the
page can catch up on what it missed while it was away, and they start afresh when the server does. The
database is never written for them: they are the server's running commentary, not its record.
"""

import re
import time
from collections import deque
from threading import Lock

#: How many pulses are kept.
KEPT = 500

# The nodes that are not people.
SERVER = "server"          # this server
ASSISTANT = "assistant"    # the rules: rank, route, next step
WRITER = "writer"          # the writing model
ROUNDS = "rounds"          # the assistant's own rounds
MAIL = "mail"              # the mail server
ADMIN = "admin"            # the administrator's console

#: The kinds of pulse, each with the word the page shows for it. The order is the legend's.
KINDS = {
    "walk": "A walk or report arriving from a phone",
    "sent_in": "Something sent in by a user of the place",
    "route": "The assistant ranking it and choosing who is told",
    "message": "An issue passed from one person to another",
    "assign": "An issue given to a technician",
    "fixed": "A repair shown with a photo",
    "closed": "Checked and closed",
    "reopened": "Sent back",
    "model": "The writing model asked, and answering",
    "rounds": "The assistant's own rounds",
    "push": "The server telling a phone its desk has changed",
    "mail": "An email going out",
    "signin": "Somebody signing in",
    "listen": "A phone listening for a change",
    "ask": "A phone or browser asking the server",
}

_pulses: deque[dict] = deque(maxlen=KEPT)
_count = 0
_lock = Lock()


def person(account_id: str | None) -> str:
    """The node of a person, by their account."""
    return f"p:{account_id}" if account_id else SERVER


def device_of(user_agent: str | None) -> str:
    """A browser says so in its user agent; the app, which uses Android's own HTTP client, does not."""
    return "browser" if user_agent and "mozilla" in user_agent.lower() else "phone"


def record(
    kind: str, frm: str, to: str, text: str, *, company_id: str | None = None, people=(), issue_id: str | None = "",
    ai: bool = False, device: str = "", ms: int | None = None,
) -> int:
    """
    Notes one pulse. `people` are the accounts concerned, whoever is at either end: they and their leads
    are who may see it. Returns its number, which only ever grows.
    """
    global _count
    entry = {
        "n": 0, "at": time.time(), "kind": kind if kind in KINDS else "ask", "from": frm, "to": to, "text": " ".join(text.split())[:240],
        "company_id": company_id, "people": sorted({one for one in people if one}), "issue_id": issue_id or "",
        "ai": bool(ai), "device": device, "ms": ms,
    }
    with _lock:
        _count += 1
        entry["n"] = _count
        _pulses.append(entry)
    return entry["n"]


def count() -> int:
    """How many pulses there have been since the server started."""
    with _lock:
        return _count


def since(n: int) -> list[dict]:
    """Every kept pulse after the one numbered `n`, oldest first."""
    with _lock:
        return [dict(one) for one in _pulses if one["n"] > n]


def last_device(account_id: str) -> str:
    """Whether this person's last word to the server came from the app or from a browser; empty when none is kept."""
    node = person(account_id)
    with _lock:
        for one in reversed(_pulses):
            if one["from"] == node and one["device"]:
                return one["device"]
    return ""


def clear() -> None:
    """Forgets everything; for the tests."""
    global _count
    with _lock:
        _pulses.clear()
        _count = 0


# ---------------------------------------------------------------- a question to the API, in words

#: Paths that record a pulse of their own, with what they mean; the plain question is not noted for them.
_OWN = re.compile(r"^/api/(reports|requests|requests/[^/]+/say|issues/[^/]+/(assign|send|fixed|close|reopen))$")
#: Paths that are nobody's doing: the linkage page watching, the container's health check, a phone's wait.
_QUIET = ("/api/linking", "/api/health", "/api/desk/wait")

_WORDS: list[tuple[str, re.Pattern, str]] = [
    ("GET", re.compile(r"^/api/desk$"), "looked at the desk"),
    ("GET", re.compile(r"^/api/dashboard$"), "opened the console"),
    ("GET", re.compile(r"^/api/me$"), "checked in"),
    ("GET", re.compile(r"^/api/issues$"), "read the issues"),
    ("GET", re.compile(r"^/api/issues/[^/]+$"), "opened an issue"),
    ("GET", re.compile(r"^/api/issues/[^/]+/(photo|fix-photo)$"), "looked at a picture"),
    ("GET", re.compile(r"^/api/inbox$"), "read the inbox"),
    ("GET", re.compile(r"^/api/reports$"), "read the reports"),
    ("GET", re.compile(r"^/api/reports/[^/]+/pdf$"), "opened a report"),
    ("GET", re.compile(r"^/api/people"), "read the people"),
    ("GET", re.compile(r"^/api/technicians$"), "looked up the technicians"),
    ("GET", re.compile(r"^/api/requests$"), "asked where their requests stand"),
    ("GET", re.compile(r"^/api/places$"), "looked up the places"),
    ("GET", re.compile(r"^/api/report-types"), "fetched the report types"),
    ("GET", re.compile(r"^/api/company/door$"), "read the door"),
    ("GET", re.compile(r"^/api/admin/system$"), "read the server's health"),
    ("GET", re.compile(r"^/api/admin/companies$"), "read the companies"),
    ("GET", re.compile(r"^/api/admin/accounts$"), "read the sign-ups"),
    ("GET", re.compile(r"^/api/admin/summary$"), "read the summary"),
    ("POST", re.compile(r"^/api/people/[^/]+/approve$"), "approved a sign-up"),
    ("POST", re.compile(r"^/api/people/[^/]+/reject$"), "rejected a sign-up"),
    ("POST", re.compile(r"^/api/people/[^/]+/suspend$"), "suspended an account"),
    ("POST", re.compile(r"^/api/people/[^/]+/reactivate$"), "let an account back in"),
    ("POST", re.compile(r"^/api/admin/accounts/[^/]+/approve$"), "approved a managing director"),
    ("POST", re.compile(r"^/api/admin/accounts/[^/]+/reject$"), "rejected a managing director"),
    ("POST", re.compile(r"^/api/company/door$"), "set the door for the people who use the place"),
    ("POST", re.compile(r"^/api/company/autopilot$"), "switched the assistant's rounds"),
    ("POST", re.compile(r"^/api/company/report-type$"), "set the company's usual report type"),
    ("POST", re.compile(r"^/api/report-types"), "made a report type"),
    ("DELETE", re.compile(r"^/api/report-types"), "removed a report type"),
    ("POST", re.compile(r"^/api/web-link$"), "asked for a dashboard link"),
    ("POST", re.compile(r"^/api/requests/[^/]+/heard$"), "heard the answer"),
]


def question(method: str, path: str) -> str | None:
    """What a question to the API comes to, in words; nothing when it is not worth a pulse."""
    if path.startswith(_QUIET) or _OWN.match(path):
        return None
    for verb, pattern, words in _WORDS:
        if verb == method and pattern.match(path):
            return words
    return f"asked {method} {path}"


def asked(request, node: str, *, people=(), company_id: str | None = None) -> None:
    """Notes that this person's phone or browser asked the server something; `request` may be nothing."""
    if request is None:
        return
    words = question(request.method, request.scope.get("path", ""))
    if words:
        record("ask", node, SERVER, words, people=people, company_id=company_id, device=device_of(request.headers.get("user-agent")))
