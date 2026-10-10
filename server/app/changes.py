"""
A count of how often something has been written. Every answer of the API that may have changed what the
server holds moves it on by one, and so does the assistant when it writes on its own rounds. A phone that
waits to be told its desk has changed watches this count, and looks into the database only when it has
moved. Kept in memory: it starts afresh when the server does.
"""

from threading import Lock

_count = 0
_lock = Lock()


def bump() -> None:
    """Something was written."""
    global _count
    with _lock:
        _count += 1


def count() -> int:
    """How often something has been written since the server started."""
    with _lock:
        return _count
