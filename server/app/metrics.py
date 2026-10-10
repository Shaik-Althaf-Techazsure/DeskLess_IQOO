"""
What the server has been asked, minute by minute, for the administrator's live monitor. Kept in memory:
it is about the last hour, and starts afresh when the server does.
"""

import time
from collections import deque
from threading import Lock

STARTED = time.time()
MINUTES = 60

# One entry per minute that saw a request: [minute, requests, milliseconds in all, errors].
_minutes: deque[list] = deque(maxlen=MINUTES)
_lock = Lock()

# The monitor's own questions and the container's health checks are not what anyone wants counted.
_QUIET = ("/api/health", "/api/admin/system")


def record(path: str, status: int, milliseconds: float) -> None:
    """Notes one answered request to the API."""
    if not path.startswith("/api/") or path.startswith(_QUIET):
        return
    minute = int(time.time() // 60)
    with _lock:
        if not _minutes or _minutes[-1][0] != minute:
            _minutes.append([minute, 0, 0.0, 0])
        entry = _minutes[-1]
        entry[1] += 1
        entry[2] += milliseconds
        entry[3] += status >= 500


def last_hour() -> dict:
    """The last sixty minutes, oldest first, with the quiet minutes filled in as zeros."""
    this_minute = int(time.time() // 60)
    with _lock:
        seen = {entry[0]: tuple(entry) for entry in _minutes}
    requests, average, errors = [], [], []
    for minute in range(this_minute - MINUTES + 1, this_minute + 1):
        _, count, spent, failed = seen.get(minute, (minute, 0, 0.0, 0))
        requests.append(count)
        average.append(round(spent / count, 1) if count else 0)
        errors.append(failed)
    total = sum(requests)
    return {
        "per_minute": requests,
        "average_ms": average,
        "errors_per_minute": errors,
        "total": total,
        "errors": sum(errors),
        "typical_ms": round(sum(a * r for a, r in zip(average, requests)) / total, 1) if total else 0,
    }
