"""
What the numbers say, in sentences: is the backlog shrinking or growing, where the risk sits, which kind
of issue is on the rise, which step of the work is the slow one, what keeps coming back. Each is worked
out from the issues a person may see, by plain arithmetic that is stated in the sentence itself, so
anyone can check it against the lists. Nothing here is guessed by a language model.
"""

from collections import Counter, defaultdict
from datetime import date, datetime, timedelta

from .config import settings
from .models import CLOSED, Issue
from .service import aware

#: How many are shown at most, the weightiest first.
SHOWN = 5


def _local_day(moment: datetime | None) -> date | None:
    return (aware(moment) + timedelta(minutes=settings().utc_offset_minutes)).date() if moment else None


def _hours(later: datetime | None, earlier: datetime | None) -> float | None:
    if later is None or earlier is None:
        return None
    return max(0.0, (aware(later) - aware(earlier)).total_seconds() / 3600)


def _span(hours: float) -> str:
    return f"{round(hours / 24)} days" if hours >= 48 else f"{max(1, round(hours))} h"


def insights(issues: list[Issue], bands: dict[str, str], day: date) -> list[dict]:
    """
    Up to five findings over these issues. [bands] says how pressing the assistant finds each open one.
    Each finding has a tone ("warn", "good" or "info"), the sentence, and where it applies, a word to
    search the issues by.
    """
    found: list[tuple[int, dict]] = []

    def say(weight: int, tone: str, text: str, search: str = "") -> None:
        found.append((weight, {"tone": tone, "text": text, "search": search}))

    open_ones = [issue for issue in issues if issue.status != CLOSED]
    week = day - timedelta(days=6)
    reported = Counter(_local_day(issue.captured_at) for issue in issues)
    closed = Counter(_local_day(issue.closed_at) for issue in issues if issue.status == CLOSED)
    in_week = sum(n for when, n in reported.items() if when and when >= week)
    out_week = sum(n for when, n in closed.items() if when and when >= week)

    # Is the pile shrinking or growing?
    if open_ones:
        if out_week > in_week:
            days = round(len(open_ones) / ((out_week - in_week) / 7))
            say(60, "good", f"The backlog is shrinking: {out_week} closed against {in_week} found in the last 7 days. At that pace the {len(open_ones)} open are cleared in about {days} day{'s' if days != 1 else ''}.")
        elif in_week > out_week:
            say(85, "warn", f"The backlog is growing: {in_week} found against {out_week} closed in the last 7 days, {in_week - out_week} more open than a week ago.")

    # A day far above the usual.
    today = reported.get(day, 0)
    before = [reported.get(day - timedelta(days=back), 0) for back in range(1, 14)]
    usual = sum(before) / len(before)
    if today >= 3 and today >= 2 * max(usual, 0.5):
        say(70, "warn", f"{today} issues were found today, against a usual {usual:.1f} a day over the fortnight before.")

    # Where the risk sits.
    sites: dict[str, Counter] = defaultdict(Counter)
    for issue in open_ones:
        if issue.site:
            sites[issue.site]["open"] += 1
            sites[issue.site]["now"] += bands.get(issue.id) == "now"
    if len(sites) > 1:
        site, count = max(sites.items(), key=lambda item: (item[1]["now"], item[1]["open"], item[0]))
        if count["now"]:
            say(80, "warn", f"{site} carries the most risk: {count['now']} of its {count['open']} open issue{'s' if count['open'] != 1 else ''} to act on now.", site)

    # A kind of issue on the rise.
    this_week, last_week = Counter(), Counter()
    for issue in issues:
        when = _local_day(issue.captured_at)
        if issue.category and when:
            if when >= week:
                this_week[issue.category] += 1
            elif when >= week - timedelta(days=7):
                last_week[issue.category] += 1
    rising = [(this_week[kind] - last_week[kind], kind) for kind in this_week if this_week[kind] >= 3 and this_week[kind] - last_week[kind] >= 2]
    if rising:
        more, kind = max(rising)
        say(65, "warn", f"{kind} issues are on the rise: {this_week[kind]} in the last 7 days, {last_week[kind]} in the 7 before.", kind)

    # Which step of the work is the slow one.
    steps = {"giving out": [], "the repair": [], "the check": []}
    for issue in issues:
        for name, hours in (
            ("giving out", _hours(issue.assigned_at, issue.captured_at)),
            ("the repair", _hours(issue.fixed_at, issue.assigned_at)),
            ("the check", _hours(issue.closed_at, issue.fixed_at) if issue.status == CLOSED else None),
        ):
            if hours is not None:
                steps[name].append(hours)
    timed = {name: sum(hours) / len(hours) for name, hours in steps.items() if len(hours) >= 2}
    if len(timed) >= 2:
        slow = max(timed, key=timed.get)
        if timed[slow] >= 2:
            others = ", ".join(f"{name} {_span(hours)}" for name, hours in timed.items() if name != slow)
            say(55, "info", f"The slow step is {slow}: {_span(timed[slow])} on average ({others}).")

    # What keeps coming back at the same place: found there on different days, not several on one walk.
    month = day - timedelta(days=29)
    again: dict[tuple[str, str], list[date]] = defaultdict(list)
    for issue in issues:
        when = _local_day(issue.captured_at)
        if issue.site and issue.category and when and when >= month:
            again[(issue.site, issue.category)].append(when)
    back = [(len(set(days)), len(days), where) for where, days in again.items() if len(days) >= 3 and len(set(days)) >= 2]
    if back:
        times, count, (site, kind) = max(back)
        say(50, "info", f"{kind} at {site} keeps coming back: {count} issues on {times} different days in the last 30.", site)

    # Pictures the phone's own model doubted.
    doubted = sum(1 for issue in open_ones if issue.ai_check == "MISMATCH")
    if doubted:
        say(45, "info", f"{doubted} open issue{'s have' if doubted != 1 else ' has'} a picture the phone’s AI doubts: look before giving {'them' if doubted != 1 else 'it'} out.")

    if not found and issues:
        say(1, "good", "Nothing stands out: no growing backlog, no spike, no site or kind of issue on the rise.")
    return [finding for _, finding in sorted(found, key=lambda pair: -pair[0])[:SHOWN]]
