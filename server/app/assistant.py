"""
The assistant on every dashboard. For each issue it says how pressing it is and why, who is best placed
to take it, what the person looking at it should do next, and gives the words to send with it.

The ranking, the choice of people and the next step are worked out here, by rules anyone can read, from
what the phone's own model wrote into the issue (title, severity, type, the check of the picture) and
from the team's record on the server. They are never left to a language model: the same issue always
gets the same rank, and each rank comes with its reasons.

The wording is the language model's part (see writer.py): a summary of the issue, and what the messages
about it come to. When no model runs on the server, the summary is put together here from the issue's
own fields, and the dashboard says which of the two it is showing. The model is not asked how to repair
anything: a small model's advice on that read well and was sometimes wrong.
"""

import hashlib
import json
import re
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .config import settings
from .models import APPROVED, ASSIGNED, CLOSED, FIXED, LEADS, MEMBER, OPEN, ROLE_NAMES, Account, Brief, Event, Issue, Message, now
from .service import Actor, aware, today

#: Where an issue starts on the scale of 0 to 100, by the severity the phone's model gave it.
BASE = {"CRITICAL": 72, "HIGH": 50, "MEDIUM": 30, "LOW": 14}
#: The scale in three words. An issue at or above the number belongs to the band.
BANDS = (("now", "Act now", 70), ("soon", "Soon", 45), ("routine", "Routine", 0))
#: Days a repair is given when the inspector named no day.
DAYS_TO_FIX = {"CRITICAL": 1, "HIGH": 2, "MEDIUM": 5, "LOW": 10}
SEVERITY_WORD = {"CRITICAL": "Critical", "HIGH": "High", "MEDIUM": "Medium", "LOW": "Low"}
#: Words in a note that mean somebody could get hurt, whatever severity it was given.
DANGER = re.compile(
    r"\b(live wires?|exposed|shock|electrocut\w*|short circuit|sparks?|sparking|fire|smoke|gas leak|collaps\w*|falling|unsafe|"
    r"injur\w*|danger\w*|hazard\w*|flood\w*|blocked exit|expired)\b",
    re.IGNORECASE,
)
URGENT = re.compile(r"\b(as soon as possible|asap|today|immediately|urgent\w*|at once|right away|tonight)\b", re.IGNORECASE)
#: How many people are named for an issue, and how many reasons are given for a rank.
SUGGESTED = 3
REASONS = 4


def role_word(role: str) -> str:
    """Who wrote a message, as a role: a person's, the administrator, or the assistant acting by itself."""
    return {"admin": "Administrator", "assistant": "Acting by itself"}.get(role) or ROLE_NAMES.get(role, role)


def band_of(score: int, closed: bool = False) -> tuple[str, str]:
    if closed:
        return "done", "Done"
    return next((key, name) for key, name, least in BANDS if score >= least)


def _first(name: str) -> str:
    return name.split()[0] if name.strip() else name


def _small(text: str) -> str:
    """A reason as it reads in the middle of a sentence: its first letter small, names left as they are."""
    return text[:1].lower() + text[1:]


def _days(n: int) -> str:
    return f"{n} day{'s' if n != 1 else ''}"


def _span(hours: float) -> str:
    return _days(round(hours / 24)) if hours >= 36 else f"{max(1, round(hours))} h"


def _ago(moment: datetime | None, at: datetime) -> str:
    if moment is None:
        return ""
    minutes = (at - aware(moment)).total_seconds() / 60
    if minutes < 2:
        return "just now"
    if minutes < 90:
        return f"{round(minutes)} min ago"
    return _span(minutes / 60) + " ago"


def _date(day: date | None) -> str:
    return day.strftime("%d %b").lstrip("0") if day else ""


def _place(issue: Issue) -> str:
    return ", ".join(part for part in (issue.site, issue.location) if part)


def _title(issue: Issue) -> str:
    return issue.title or "Untitled issue"


def _sentence(text: str, limit: int) -> str:
    """The first sentence of a note, or its beginning, tidied to one line."""
    line = " ".join(text.split())
    first = re.split(r"(?<=[.!?])\s", line, maxsplit=1)[0]
    return first if len(first) <= limit else first[: limit - 1].rstrip() + "…"


def mark_of(issue: Issue) -> str:
    """A mark of everything the writing model is shown about an issue; it changes when any of that does."""
    told = [issue.title, issue.note, issue.site, issue.location, issue.category, issue.severity, issue.seen, issue.due_text]
    return hashlib.sha256(json.dumps(told).encode()).hexdigest()


def rank(issue: Issue, day: date, moment: datetime, sent_back: bool = False, alike: int = 0) -> tuple[int, list[str]]:
    """
    How pressing an issue is, from 0 to 100, with the reasons in the order they weigh. `alike` is how
    many other issues of the same type are open at the same site.
    """
    if issue.status == CLOSED:
        return 0, ["Closed"]
    severity = issue.severity if issue.severity in BASE else "MEDIUM"
    if issue.status == FIXED:
        # A repair has been shown: what is left is the check, which is quick but should not sit.
        waited = (moment - aware(issue.fixed_at)).total_seconds() / 3600 if issue.fixed_at else 0
        return min(100, 30 + BASE[severity] // 4 + min(18, int(waited // 12) * 3)), [f"A repair was shown {_span(waited)} ago and waits for the check", f"{SEVERITY_WORD[severity]} severity"]

    points = BASE[severity]
    reasons = [f"{SEVERITY_WORD[severity]} severity"]
    if issue.due_on is not None:
        late = (day - issue.due_on).days
        if late > 0:
            points += 18 + min(12, 3 * late)
            reasons.insert(0, f"{_days(late)} past its day")
        elif late == 0:
            points += 14
            reasons.append("Due today")
        elif late >= -2:
            points += 8
            reasons.append(f"Due in {_days(-late)}")
    elif URGENT.search(issue.due_text or ""):
        points += 8
        reasons.append(f"The inspector said “{issue.due_text}”")
    if issue.status == OPEN:
        hours = (moment - aware(issue.captured_at)).total_seconds() / 3600
        if hours >= 4:
            points += 10 if hours >= 72 else 6 if hours >= 24 else 3
            reasons.append(f"Nobody has taken it for {_span(hours)}")
    if sent_back:
        points += 8
        reasons.append("Sent back once after a repair")
    said = DANGER.search(f"{issue.title} {issue.note}")
    if said:
        points += 8
        reasons.append(f"The note says “{said.group(0).lower()}”")
    if issue.ai_check == "MISMATCH":
        points += 4
        reasons.append("The phone’s AI doubts the picture matches the note")
    if alike >= 2:
        points += 5
        reasons.append(f"{alike} more {(issue.category or 'such').lower()} issues are open at this site")
    return min(100, points), reasons[:REASONS]


class Desk:
    """
    What is needed to rank a set of issues for one person and to say who could take each: read once for
    a request, then asked issue by issue.
    """

    def __init__(self, db: Session, actor: Actor, issues: list[Issue]):
        self.db = db
        self.actor = actor
        self.me = None if actor.is_admin else actor.account
        self.day = today()
        self.moment = now()
        ids = [issue.id for issue in issues]
        self.companies = {issue.company_id for issue in issues if issue.company_id}
        self.authors = {issue.author_id for issue in issues}
        self.sent_back = set(db.scalars(select(Event.issue_id).where(Event.kind == "reopened", Event.issue_id.in_(ids)))) if ids else set()
        self.alike: Counter = Counter()
        if self.companies:
            grouped = select(Issue.company_id, Issue.site, Issue.category, func.count()).where(Issue.company_id.in_(self.companies), Issue.status != CLOSED)
            for company, site, category, n in db.execute(grouped.group_by(Issue.company_id, Issue.site, Issue.category)):
                self.alike[(company, site, category)] = n
        self.briefs = {brief.issue_id: brief for brief in db.scalars(select(Brief).where(Brief.issue_id.in_(ids)))} if ids else {}
        self.messages: dict[str, list[Message]] = defaultdict(list)
        if ids:
            for message in db.scalars(select(Message).where(Message.issue_id.in_(ids)).order_by(Message.id.asc())):
                self.messages[message.issue_id].append(message)
        self._people: dict[str, Account] | None = None
        self._bench: dict[str, dict] | None = None

    # ------------------------------------------------------------ the people and their record

    @property
    def people(self) -> dict[str, Account]:
        """Everyone approved in the companies these issues belong to."""
        if self._people is None:
            found = self.db.scalars(select(Account).where(Account.status == APPROVED, Account.company_id.in_(self.companies))) if self.companies else []
            self._people = {person.id: person for person in found}
            if self.me is not None:
                self._people.setdefault(self.me.id, self.me)
            # Whoever sent an issue in from outside: a user of the place, who is in no company.
            outside = self.authors - self._people.keys()
            if outside:
                for person in self.db.scalars(select(Account).where(Account.id.in_(outside), Account.role == MEMBER, Account.status == APPROVED)):
                    self._people[person.id] = person
        return self._people

    @property
    def bench(self) -> dict[str, dict]:
        """Each technician's record: what they have in hand, what they have done, where, and how timely."""
        if self._bench is None:
            offset = timedelta(minutes=settings().utc_offset_minutes)
            technicians = [person for person in self.people.values() if person.role == "technical"]
            record = {
                person.id: {"open": 0, "late": 0, "done": 0, "timed": 0, "on_time": 0, "types": Counter(), "sites": Counter(), "hours": []}
                for person in technicians
            }
            jobs = self.db.scalars(select(Issue).where(Issue.assigned_to.in_(record.keys()))) if record else []
            for job in jobs:
                mine = record[job.assigned_to]
                if job.status == ASSIGNED:
                    mine["open"] += 1
                    mine["late"] += job.due_on is not None and job.due_on < self.day
                elif job.fixed_at is not None:
                    # Only a repair that was shown counts as knowing the kind of work, and the site.
                    mine["done"] += 1
                    mine["types"][job.category] += 1
                    mine["sites"][job.site] += 1
                    if job.due_on is not None:
                        mine["timed"] += 1
                        mine["on_time"] += (aware(job.fixed_at) + offset).date() <= job.due_on
                    if job.assigned_at is not None:
                        mine["hours"].append((aware(job.fixed_at) - aware(job.assigned_at)).total_seconds() / 3600)
            self._bench = record
        return self._bench

    def _within_reach(self, issue: Issue) -> list[Account]:
        """The technicians this person could give the issue to, or, for the administrator, tell its manager of."""
        technicians = [person for person in self.people.values() if person.role == "technical" and person.company_id == issue.company_id]
        if self.me is None or self.me.role == "md":
            return technicians
        if self.me.role == "manager":
            return [person for person in technicians if person.reports_to == self.me.id]
        return []

    def lead_for(self, issue: Issue) -> Account | None:
        """Who gives this issue out: the manager of whoever raised it, or failing that the managing director."""
        author = self.people.get(issue.author_id)
        if issue.raised_as:
            # Sent in by a user of the place: it is given out by whoever leads the person told of it first.
            first = next((message for message in self.messages[issue.id] if message.rule == "public"), None)
            told = self.people.get(first.to_id) if first is not None else None
            lead = None if told is None else told if told.role in LEADS else self.people.get(told.reports_to)
            if lead is not None:
                return lead
        elif author is not None:
            lead = author if author.role in LEADS else self.people.get(author.reports_to)
            if lead is not None:
                return lead
        return next((person for person in self.people.values() if person.role == "md" and person.company_id == issue.company_id), None)

    # ------------------------------------------------------------ rank, people, next step

    def priority(self, issue: Issue) -> dict:
        others = self.alike[(issue.company_id, issue.site, issue.category)] - 1 if issue.status != CLOSED else 0
        score, why = rank(issue, self.day, self.moment, issue.id in self.sent_back, others)
        band, name = band_of(score, issue.status == CLOSED)
        return {"score": score, "band": band, "band_name": name, "why": why}

    def who_could(self, issue: Issue) -> list[dict]:
        """The technicians best placed to take this issue, best first, each with the reasons."""
        if issue.status not in (OPEN, ASSIGNED):
            return []
        team_lead = self.lead_for(issue)
        named = (issue.owner or "").lower()
        since = self.moment - timedelta(minutes=15)
        ranked = []
        for person in self._within_reach(issue):
            record = self.bench[person.id]
            fit = 50
            why = []
            if named and (named in person.name.lower() or _first(person.name).lower() in named.split()):
                fit += 30
                why.append("Named in the inspector’s note")
            same = record["types"][issue.category] if issue.category else 0
            if same:
                fit += min(24, 8 * same)
                why.append(f"Has repaired {same} {issue.category.lower()} issue{'s' if same != 1 else ''}")
            here = record["sites"][issue.site] if issue.site else 0
            if here:
                fit += min(15, 5 * here)
                why.append(f"Knows {issue.site}: {here} job{'s' if here != 1 else ''} there")
            fit -= 7 * record["open"] + 10 * record["late"]
            why.append("Free now" if not record["open"] else f"{record['open']} job{'s' if record['open'] != 1 else ''} in hand" + (f", {record['late']} late" if record["late"] else ""))
            if record["timed"]:
                fit += round(10 * record["on_time"] / record["timed"])
                why.append(f"{record['on_time']} of {record['timed']} done on time")
            if person.last_seen_at and aware(person.last_seen_at) >= since:
                fit += 5
                why.append("Online now")
            if (self.me is None or self.me.role != "manager") and team_lead is not None and person.reports_to == team_lead.id:
                # Seen from above the teams, the work stays with the team that found it unless someone
                # elsewhere is clearly better placed.
                fit += 20
                why.insert(0, f"On {team_lead.name}’s team, like the inspector")
            ranked.append({
                "id": person.id, "name": person.name, "unique_id": person.unique_id, "fit": max(5, min(99, fit)), "why": why[:REASONS],
                "open_work": record["open"], "has_it": issue.assigned_to == person.id,
            })
        ranked.sort(key=lambda one: (-one["fit"], one["open_work"], one["name"]))
        return ranked[:SUGGESTED]

    def due_for(self, issue: Issue) -> date:
        """The day to ask for: the inspector's, when it is still ahead; otherwise by how severe the issue is."""
        if issue.due_on is not None and issue.due_on >= self.day:
            return issue.due_on
        return self.day + timedelta(days=DAYS_TO_FIX.get(issue.severity, 5))

    def _mine(self, message: Message) -> bool:
        return message.author_id is None and message.author_role == "admin" if self.me is None else message.author_id == self.me.id

    def _for_me(self, message: Message) -> bool:
        return bool(message.to_admin) if self.me is None else message.to_id == self.me.id

    def waiting_on_me(self, issue: Issue) -> Message | None:
        """The newest message about this issue that still waits for this person."""
        return next((message for message in reversed(self.messages[issue.id]) if message.done_at is None and self._for_me(message)), None)

    def waiting_on_others(self, issue: Issue) -> Message | None:
        return next((message for message in reversed(self.messages[issue.id]) if message.done_at is None and self._mine(message)), None)

    def already_with(self, issue: Issue, person_id: str) -> Message | None:
        """A message about the issue that still waits on this person, whoever sent it: a person, or the assistant on its rounds."""
        return next((message for message in reversed(self.messages[issue.id]) if message.done_at is None and message.to_id == person_id), None)

    def wording(self, issue: Issue) -> Brief | None:
        """What the writing model made of the issue, if it has written about it as it now stands."""
        brief = self.briefs.get(issue.id)
        return brief if brief is not None and brief.summary and brief.mark == mark_of(issue) else None

    def talk(self, issue: Issue) -> str:
        """What the messages about the issue come to, when the model has read them up to the last one."""
        brief = self.briefs.get(issue.id)
        thread = self.messages[issue.id]
        return brief.talk if brief is not None and thread and brief.talk_upto == thread[-1].id else ""

    def standing(self, issue: Issue) -> str:
        """Where the issue stands, in a sentence. Always worked out fresh: it changes while the summary does not."""
        names = self.people
        holder = names.get(issue.assigned_to)
        holder = holder.name if holder else "a technician"
        if issue.status == OPEN:
            author = names.get(issue.author_id)
            if issue.raised_as:
                return f"Sent in {_ago(issue.captured_at, self.moment)}{' by ' + author.name + ',' if author else ' by'} a user of the place; nobody has it yet."
            return f"Raised {_ago(issue.captured_at, self.moment)}{' by ' + author.name if author else ''}; nobody has it yet."
        if issue.status == ASSIGNED:
            late = (self.day - issue.due_on).days if issue.due_on else 0
            due = f", due {_date(issue.due_on)}" if issue.due_on else ""
            return f"With {holder}{due}{', ' + _days(late) + ' late' if late > 0 else ''}."
        if issue.status == FIXED:
            return f"{holder} showed the repair {_ago(issue.fixed_at, self.moment)}; it waits for the check."
        return f"Closed{' by ' + issue.closed_by if issue.closed_by else ''}{' on ' + _date((aware(issue.closed_at) + timedelta(minutes=settings().utc_offset_minutes)).date()) if issue.closed_at else ''}."

    def summary(self, issue: Issue) -> str:
        """What is wrong, in a sentence or two: the model's words when it has written, the issue's own otherwise."""
        written = self.wording(issue)
        if written is not None:
            return written.summary
        kind = f"{issue.category.lower()} issue" if issue.category else "issue"
        where = f" at {_place(issue)}" if _place(issue) else ""
        parts = [f"{SEVERITY_WORD.get(issue.severity, 'Medium')} {kind}{where}: {_title(issue).rstrip('.')}."]
        if issue.raised_as == "enquiry":
            parts = [f"A user of the place asks{where}: {_title(issue)}"]
        note = _sentence(issue.note or "", 200)
        if note and note.rstrip(".").lower() != _title(issue).rstrip(".").lower():
            parts.append(note if note[-1] in ".!?…" else note + ".")
        return " ".join(parts)

    def next_step(self, issue: Issue, could: list[dict] | None = None) -> dict:
        """
        What this person should do about the issue now, with the words to send. `kind` is "assign" (give
        it to a technician), "send" (pass it to someone with a note), "fix", "check", or "wait".
        """
        me = self.me
        role = self.actor.role
        people = self.people
        waits_on_me = self.waiting_on_me(issue)
        holder = people.get(issue.assigned_to)
        late = (self.day - issue.due_on).days if issue.due_on and issue.status in (OPEN, ASSIGNED) else 0

        def send(to: Account, label: str, text: str) -> dict:
            return {"kind": "send", "label": label, "to": to.id, "to_name": to.name, "text": text}

        def wait(label: str) -> dict:
            return {"kind": "wait", "label": label}

        def answer() -> dict | None:
            """An answer to whoever is waiting on this person."""
            if waits_on_me is None or waits_on_me.author_role == "assistant":
                return None
            text = self.standing(issue) if role in ("admin", *LEADS) else "I have seen it and will start on it today." if role == "technical" else "Here is more on it: "
            return {"kind": "send", "label": f"Answer {waits_on_me.author}", "to": waits_on_me.author_id or "admin", "to_name": waits_on_me.author, "text": text}

        if issue.status == CLOSED:
            return answer() or wait("Closed: nothing more to do.")

        if me is not None and issue.status == ASSIGNED and issue.assigned_to == me.id:
            return {"kind": "fix", "label": "Repair it, then mark it fixed with a photo" + (f", due {_date(issue.due_on)}" if issue.due_on else "")}

        if role in LEADS and me.company_id is not None:
            if issue.status == FIXED:
                return {"kind": "check", "label": "Compare the two pictures, then close it or send it back"}
            if issue.status == OPEN:
                asker = people.get(issue.author_id) if issue.raised_as == "enquiry" else None
                if asker is not None:
                    # A question is answered, not repaired, and the words are this person's to give.
                    return send(asker, f"Answer {asker.name}", "")
                could = self.who_could(issue) if could is None else could
                if could:
                    best = could[0]
                    due = self.due_for(issue)
                    words = (
                        f"{_first(best['name'])}, please take this up: {_title(issue).rstrip('.')}{' at ' + _place(issue) if _place(issue) else ''}. "
                        f"It is {SEVERITY_WORD.get(issue.severity, 'Medium').lower()} severity, due {_date(due)}. "
                        "Mark it fixed with a photo of the repair."
                    )
                    return {"kind": "assign", "label": f"Give it to {best['name']}, due {_date(due)}", "to": best["id"], "to_name": best["name"], "due_on": due.isoformat(), "text": words}
                lead = self.lead_for(issue)
                if lead is not None and lead.id != me.id and self.waiting_on_others(issue) is None:
                    return send(lead, f"Send it to {lead.name} to give out", f"{_first(lead.name)}, please give this one out: {self.summary(issue)}")
                return answer() or wait("You have no technician to give it to yet.")
            asked = self.already_with(issue, holder.id) if holder is not None else None
            if late > 0 and asked is not None:
                # Nobody is told to ask twice: what was asked, by a person or by the assistant, is waited for.
                return answer() or wait(f"{'You' if self._mine(asked) else asked.author} asked {holder.name} {_ago(asked.at, self.moment)}; waiting for the answer.")
            if late > 0 and holder is not None and self.waiting_on_others(issue) is None:
                return send(holder, f"Ask {holder.name} when it will be done", f"{_first(holder.name)}, this is {_days(late)} past its day: {_title(issue).rstrip('.')}{' at ' + _place(issue) if _place(issue) else ''}. What is holding it, and when will it be done?")
            return answer() or wait(f"With {holder.name if holder else 'a technician'}{', due ' + _date(issue.due_on) if issue.due_on else ''}.")

        brought = next((message for message in self.messages[issue.id] if message.rule == "public"), None) if issue.raised_as else None
        if me is not None and brought is not None and brought.to_id == me.id and issue.status == OPEN:
            # Sent in by a user of the place and brought to this person first. A question is theirs to
            # answer. Anything else is theirs to pass up to whoever gives out work, and stays so until
            # they have: a word of thanks to the sender does not take it off their hands.
            asker = people.get(issue.author_id)
            if issue.raised_as == "enquiry":
                if asker is not None and waits_on_me is not None:
                    return send(asker, f"Answer {asker.name}", "")
            else:
                above = self.lead_for(issue)
                passed_up = above is not None and any(self._mine(message) and message.to_id == above.id for message in self.messages[issue.id])
                if above is not None and above.id != me.id and not passed_up:
                    return send(above, f"Send it to {above.name} to give out", f"{_first(above.name)}, a user of the place sent this in: {self.summary(issue)} Please give it to a technician.")
        reply = answer()
        if reply is not None:
            return reply
        asked = self.waiting_on_others(issue)
        if asked is not None:
            return wait(f"You sent it to {asked.to_name} {_ago(asked.at, self.moment)}; waiting for them.")

        lead = self.lead_for(issue)
        if role == "admin":
            # The administrator does not hand out work: they bring it to whoever does, with the case made.
            if lead is None:
                return wait("Nobody in a company answers for this issue.")
            told = self.already_with(issue, lead.id)
            if told is not None:
                return wait(f"{told.author} told {lead.name} {_ago(told.at, self.moment)}; it waits on them.")
            score = self.priority(issue)
            if issue.status == FIXED:
                waited = (self.moment - aware(issue.fixed_at)).total_seconds() / 3600 if issue.fixed_at else 0
                if waited >= 24:
                    return send(lead, f"Remind {lead.name} to check the repair", f"{_first(lead.name)}, the repair for “{_title(issue)}”{' at ' + issue.site if issue.site else ''} was shown {_span(waited)} ago. Please check it against the first picture, then close it or send it back.")
                return wait(f"A repair was shown; {lead.name} checks it.")
            if issue.status == ASSIGNED:
                if late > 0:
                    return send(lead, f"Ask {lead.name} why it is late", f"{_first(lead.name)}, “{_title(issue)}”{' at ' + issue.site if issue.site else ''} is {_days(late)} past its day and still with {holder.name if holder else 'a technician'}. What is holding it?")
                return wait(f"With {holder.name if holder else 'a technician'}{', due ' + _date(issue.due_on) if issue.due_on else ''}.")
            if score["band"] == "routine":
                return wait(f"Routine: {lead.name} gives it out in the usual way.")
            could = self.who_could(issue) if could is None else could
            best = f" Best placed to take it: {could[0]['name']} ({_small(could[0]['why'][0])})." if could else ""
            return send(lead, f"Send it to {lead.name} ({ROLE_NAMES.get(lead.role, lead.role)})", f"{_first(lead.name)}, this one needs you. {self.summary(issue)} Why now: {'; '.join(_small(reason) for reason in score['why'][:3])}.{best}")
        if role == "inspector" and issue.status == OPEN and lead is not None and lead.id != me.id:
            hours = (self.moment - aware(issue.captured_at)).total_seconds() / 3600
            if hours >= 24 and issue.severity in ("CRITICAL", "HIGH"):
                return send(lead, f"Remind {lead.name}", f"{_first(lead.name)}, “{_title(issue)}”{' at ' + issue.site if issue.site else ''} is {SEVERITY_WORD[issue.severity].lower()} and nobody has taken it for {_span(hours)}. Could you give it to a technician?")
            return wait(f"With {lead.name}, to be given to a technician.")
        if issue.status == FIXED:
            return wait("The repair is shown and waits for the check.")
        return wait(self.standing(issue))

    def replies(self, issue: Issue) -> list[str]:
        """A few ready answers for this person, to send as they are or to start from."""
        role = self.actor.role
        if role == MEMBER:
            return ["Thank you.", "It is still not put right.", "Here is more on it: "]
        if issue.raised_as and role != "technical" and issue.status in (OPEN, ASSIGNED):
            # Ready words for the user of the place who sent it in.
            return ["Thank you for telling us. Someone will look at it today.", "Could you say exactly where it is, or send a picture?", "This is known already and is being put right.", self.standing(issue)]
        if role == "technical":
            return ["I have seen it and will start on it today.", "I need a clearer picture or more detail before I can start.", "I cannot take this one. Please give it to someone else."]
        if role == "inspector":
            return ["Here is more on it: ", "I will go back and take a clearer picture.", "It has become worse since I reported it."]
        ready = [self.standing(issue)]
        if issue.status in (OPEN, ASSIGNED):
            ready.append("Please send a clearer picture and say exactly where it is.")
        if issue.status == OPEN:
            ready.append("Looked at it: no repair is needed.")
        return ready

    def may_send_to(self, issue: Issue) -> list[dict]:
        """
        The people this person may pass the issue to, the likeliest first. The administrator reaches
        anyone in the issue's company; a managing director anyone in theirs, and the administrator; a
        manager their own people and their managing director; an inspector or technician the person they
        answer to. Anyone may also answer whoever wrote to them about the issue, or is already part of it.
        """
        me = self.me
        company = [person for person in self.people.values() if person.company_id == issue.company_id and issue.company_id]
        thread = self.messages[issue.id]
        wrote_to_me = {message.author_id or "admin" for message in thread if self._for_me(message) and message.author_role != "assistant"}
        in_it = {issue.author_id, issue.assigned_to}
        allowed: dict[str, Account | None] = {}
        if me is None:
            allowed = {person.id: person for person in company}
        else:
            for person in company:
                above = person.id == me.reports_to
                below = person.reports_to == me.id
                if me.role == "md" or above or below or person.id in in_it and me.role in LEADS:
                    allowed[person.id] = person
            for one in wrote_to_me | (in_it if me.role in LEADS else set()):
                if one in self.people:
                    allowed[one] = self.people[one]
            allowed.pop(me.id, None)
            if me.role == "md" or "admin" in wrote_to_me:
                allowed["admin"] = None
        lead = self.lead_for(issue)
        order = {"md": 1, "manager": 2, "technical": 3, "inspector": 4}

        def place(entry) -> tuple:
            key, person = entry
            if person is None:
                return (5, "")
            return (0 if lead is not None and key == lead.id else order.get(person.role, 9), person.name)

        return [
            {"id": key, "name": "The administrator" if person is None else person.name, "role_name": "Administrator" if person is None else ROLE_NAMES.get(person.role, person.role)}
            for key, person in sorted(allowed.items(), key=place)
        ]

    # ------------------------------------------------------------ what a page is given

    def thread(self, issue: Issue) -> list[dict]:
        return [
            {
                "id": message.id, "at": aware(message.at).isoformat(), "from": message.author, "from_role": role_word(message.author_role),
                "to": message.to_name, "text": message.text, "mine": self._mine(message), "for_me": self._for_me(message), "waiting": message.done_at is None,
            }
            for message in self.messages[issue.id]
        ]

    def brief(self, issue: Issue) -> dict:
        """Everything the assistant has to say about one issue to the person looking at it."""
        written = self.wording(issue)
        could = self.who_could(issue)
        return {
            "engine": written.engine if written is not None else "",
            "summary": self.summary(issue),
            "standing": self.standing(issue),
            "talk": self.talk(issue),
            "priority": self.priority(issue),
            "suggested": could,
            "next": self.next_step(issue, could),
            "replies": self.replies(issue),
        }

    def queue(self, issues: list[Issue], limit: int = 6) -> tuple[list[dict], list[dict]]:
        """
        The issues that need this person, most pressing first, each with its next step; and one line
        for each of the most pressing issues in view, whoever they wait on, for the triage console.
        """
        open_ones = [issue for issue in issues if issue.status != CLOSED]
        scored = sorted(((self.priority(issue), issue) for issue in open_ones), key=lambda pair: (-pair[0]["score"], aware(pair[1].captured_at)))
        needs_me, lines = [], []
        for score, issue in scored:
            if len(needs_me) >= limit and len(lines) >= 10:
                break
            step = self.next_step(issue)
            if len(lines) < 10:
                lines.append({"issue_id": issue.id, "score": score["score"], "band": score["band"], "title": _title(issue), "site": issue.site, "why": score["why"][0], "then": step["label"]})
            if step["kind"] != "wait" and len(needs_me) < limit:
                needs_me.append({
                    "id": issue.id, "title": _title(issue), "site": issue.site, "severity": issue.severity, "status": issue.status,
                    "score": score["score"], "band": score["band"], "band_name": score["band_name"], "why": score["why"][:2], "next": step,
                })
        return needs_me, lines

    def load(self) -> list[dict]:
        """How much each technician in reach has in hand, the freest first: for the team board."""
        since = self.moment - timedelta(minutes=15)
        rows = []
        for person in self.people.values():
            if person.role != "technical" or (self.me is not None and self.me.role == "manager" and person.reports_to != self.me.id):
                continue
            record = self.bench[person.id]
            rows.append({
                "id": person.id, "name": person.name, "open": record["open"], "late": record["late"], "done": record["done"],
                "on_time": round(100 * record["on_time"] / record["timed"]) if record["timed"] else None,
                "typical_hours": round(sorted(record["hours"])[len(record["hours"]) // 2], 1) if record["hours"] else None,
                "online": bool(person.last_seen_at and aware(person.last_seen_at) >= since),
            })
        return sorted(rows, key=lambda row: (row["open"], row["name"]))


def dealt_with(db: Session, actor: Actor, issue: Issue) -> None:
    """Whatever waited on this person about the issue is settled: they have answered, sent it on, or done the work."""
    waiting = select(Message).where(Message.issue_id == issue.id, Message.done_at.is_(None))
    waiting = waiting.where(Message.to_admin.is_(True)) if actor.is_admin else waiting.where(Message.to_id == actor.account.id)
    for message in db.scalars(waiting):
        message.done_at = now()
