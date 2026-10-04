"""Rule-based structuring: turns an inspector's note into fields without any model.

This is the fallback when no local LLM is available. It only fills owner, due date and
location when they were actually said; severity is inferred from the kind of defect.
"""
import calendar
import re
from dataclasses import dataclass, field
from datetime import date, timedelta

SEVERITIES = ("High", "Medium", "Low")

ROLES = [
    ("structural engineer", "Structural engineer"),
    ("civil engineer", "Civil engineer"),
    ("site engineer", "Site engineer"),
    ("safety officer", "Safety officer"),
    ("fire officer", "Fire safety officer"),
    ("waterproofing contractor", "Waterproofing contractor"),
    ("waterproofing team", "Waterproofing contractor"),
    ("electrical contractor", "Electrical contractor"),
    ("hvac technician", "HVAC technician"),
    ("maintenance team", "Maintenance team"),
    ("site supervisor", "Site supervisor"),
    ("electrician", "Electrician"),
    ("plumber", "Plumber"),
    ("mason", "Mason"),
    ("painter", "Painter"),
    ("carpenter", "Carpenter"),
    ("welder", "Welder"),
    ("architect", "Architect"),
    ("housekeeping", "Housekeeping"),
    ("supervisor", "Site supervisor"),
    ("contractor", "Contractor"),
    ("maintenance", "Maintenance team"),
]

HIGH_WORDS = [
    "danger", "dangerous", "hazard", "unsafe", "urgent", "immediately", "exposed", "live wire", "loose wiring",
    "loose wire", "wiring is loose", "wire is loose", "short circuit", "spark", "fire", "gas leak", "collapse",
    "electrocution", "shock", "missing", "blocked exit", "exit is blocked", "no railing", "fall risk",
    "khatarnak", "turant", "khatra",
]
MEDIUM_WORDS = [
    "crack", "seepage", "leak", "leakage", "leaking", "damp", "rust", "corrosion", "broken", "damaged", "loose",
    "bent", "sagging", "water", "daraar", "darar", "paani", "pani", "toota", "tuta",
]
# Hazards that are phrased in many ways ("wiring at the panel is loose", "wires hanging loose").
HIGH_PATTERNS = [
    re.compile(r"\b(wiring|wires?|cables?)\b[^.!?]*\b(loose|exposed|hanging|open|damaged|burnt)\b"),
    re.compile(r"\b(loose|exposed|hanging|open|damaged|burnt)\b[^.!?]*\b(wiring|wires?|cables?)\b"),
    re.compile(r"\b(exit|staircase|escape route)\b[^.!?]*\bblocked\b"),
    re.compile(r"\b(extinguisher|railing|guard ?rail|barricade|helmet|harness)\b[^.!?]*\b(missing|not there|nahi)\b"),
]
LOW_WORDS = ["paint", "stain", "scratch", "dust", "cleaning", "cosmetic", "touch up", "touch-up", "minor", "label", "signage faded"]

WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
NUMBER_WORDS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "ek": 1, "do": 2, "teen": 3}

LOC_NOUNS = (
    "basement|level|floor|tower|block|wing|zone|column|pillar|panel|room|exit|gate|ramp|staircase|stairs|lift|"
    "shaft|bay|grid|section|unit|flat|terrace|podium|db"
)
LOC_CODE = re.compile(rf"\b({LOC_NOUNS})\s*(?:no\.?\s*|number\s*)?([A-Za-z]{{0,3}}-?\d+[A-Za-z]?)\b", re.I)
LOC_FLOOR = re.compile(r"\b(\d+(?:st|nd|rd|th)|ground|first|second|third|fourth|fifth|top)\s+floor\b", re.I)
LOC_COMPASS = re.compile(r"\b(north|south|east|west)(?:ern)?\s+(wall|side|wing|face|corner|gate|entrance)\b", re.I)
LOC_NEAR = re.compile(
    r"\bnear (?:the )?(ramp|lift|staircase|stairs|entrance|main gate|gate|parking|pump room|dg room|meter room|lobby)\b",
    re.I,
)


@dataclass
class Structured:
    title: str = ""
    location: str | None = None
    severity: str = "Medium"
    owner: str | None = None
    due: str | None = None
    due_date: date | None = None
    english: str | None = None
    needs_review: list[str] = field(default_factory=list)
    engine: str = "rules"


def _has(text: str, phrase: str) -> bool:
    return re.search(rf"\b{re.escape(phrase)}", text) is not None


def infer_severity(text: str) -> tuple[str, bool]:
    """Returns (severity, confident)."""
    t = text.lower()
    if re.search(r"\b(high|critical|major|top)\s+(priority|severity|risk)\b", t):
        return "High", True
    if re.search(r"\b(medium|moderate)\s+(priority|severity|risk)\b", t):
        return "Medium", True
    if re.search(r"\b(low|minor)\s+(priority|severity|risk)\b", t):
        return "Low", True
    if any(_has(t, w) for w in HIGH_WORDS) or any(p.search(t) for p in HIGH_PATTERNS):
        return "High", True
    if any(_has(t, w) for w in MEDIUM_WORDS):
        return "Medium", True
    if any(_has(t, w) for w in LOW_WORDS):
        return "Low", True
    return "Medium", False


def find_owner(text: str) -> str | None:
    t = text.lower()
    best: tuple[int, int, str] | None = None  # (position, -length, label)
    for phrase, label in ROLES:
        m = re.search(rf"\b{re.escape(phrase)}s?\b", t)
        if m:
            cand = (m.start(), -len(phrase), label)
            if best is None or cand < best:
                best = cand
    return best[2] if best else None


def _end_of_week(today: date) -> date:
    return today + timedelta(days=(4 - today.weekday()) % 7)  # this Friday (today if it is Friday)


def parse_due(text: str, today: date) -> tuple[str | None, date | None]:
    t = text.lower()
    if re.search(r"\b(immediately|right away|right now|asap|urgently|urgent|turant|abhi)\b", t):
        return "Today", today
    if re.search(r"\b(day after tomorrow|parso|parson)\b", t):
        return "In 2 days", today + timedelta(days=2)
    if re.search(r"\b(today|tonight|aaj|eod|by end of (the )?day)\b", t):
        return "Today", today
    if re.search(r"\b(tomorrow|kal)\b", t):
        return "Tomorrow", today + timedelta(days=1)
    m = re.search(r"\b(?:with)?in (\d+|one|two|three|four|five|six|seven) (?:days?|din)\b", t) or re.search(
        r"\b(\d+|ek|do|teen) din\b", t
    )
    if m:
        n = int(m.group(1)) if m.group(1).isdigit() else NUMBER_WORDS[m.group(1)]
        return f"In {n} day{'s' if n != 1 else ''}", today + timedelta(days=n)
    m = re.search(rf"\b(?:by|before|on|till|until)\s+({'|'.join(WEEKDAYS)})\b", t)
    if m:
        target = WEEKDAYS.index(m.group(1))
        delta = (target - today.weekday()) % 7 or 7
        return f"By {m.group(1).capitalize()}", today + timedelta(days=delta)
    if re.search(r"\b(this week|is hafte|within (a|the) week|by (the )?weekend|end of (the )?week)\b", t):
        return "This week", _end_of_week(today)
    if re.search(r"\b(next week|agle hafte)\b", t):
        return "Next week", _end_of_week(today) + timedelta(days=7)
    if re.search(r"\b(this month|within (a|the) month|end of (the )?month)\b", t):
        last = calendar.monthrange(today.year, today.month)[1]
        return "This month", today.replace(day=last)
    return None, None


def find_location(text: str, area: str = "") -> str | None:
    parts: list[str] = []

    def add(p: str) -> None:
        if p.lower() not in (x.lower() for x in parts) and p.lower() not in area.lower():
            parts.append(p)

    for m in LOC_CODE.finditer(text):
        noun, code = m.group(1), m.group(2)
        noun = "Panel" if noun.lower() == "db" else noun.capitalize()
        add(f"{noun} {code.upper()}")
    for m in LOC_FLOOR.finditer(text):
        add(f"{m.group(1).capitalize()} floor")
    for m in LOC_COMPASS.finditer(text):
        add(f"{m.group(1).capitalize()} {m.group(2).lower()}")
    for m in LOC_NEAR.finditer(text):
        add(f"near the {m.group(1).lower()}")
    specific = " · ".join(parts[:2])
    if area and specific:
        return f"{area} · {specific}"
    return specific or (area or None)


def make_title(text: str) -> str:
    first = re.split(r"(?<=[.!?।])\s+", text.strip())[0] if text.strip() else ""
    s = first.strip(" .!?।\"'“”")
    s = re.sub(r"^(there is|there's|there are|this is|here is|we have|i can see|i see|look at|note that)\s+", "", s, flags=re.I)
    s = re.sub(r"\s*\b(over here|right here|here)\b", "", s, flags=re.I)
    s = re.sub(r"\s+", " ", s).strip(" ,;:-")
    s = re.sub(r"^(a|an|the)\s+", "", s, flags=re.I)
    if len(s) > 72:
        s = s[:72].rsplit(" ", 1)[0] + "…"
    return (s[:1].upper() + s[1:]) if s else "Untitled issue"


def structure(note: str, area: str = "", today: date | None = None) -> Structured:
    """note should be the English text when available; Hinglish keywords are also understood."""
    today = today or date.today()
    note = (note or "").strip()
    out = Structured(engine="rules")
    out.title = make_title(note)
    out.severity, confident = infer_severity(note)
    out.owner = find_owner(note)
    out.due, out.due_date = parse_due(note, today)
    out.location = find_location(note, area)
    if not confident:
        out.needs_review.append("severity")
    for name in ("location", "owner", "due"):
        if not getattr(out, name):
            out.needs_review.append(name)
    if not note:
        out.needs_review.insert(0, "title")
    return out
