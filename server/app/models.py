"""
What is kept: companies and the accounts of the people in them, the one-time codes sent to those people,
the counters behind the unique IDs, and the inspection reports with their issues and what was done about each.
"""

from datetime import date, datetime, timezone
from uuid import uuid4

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Integer, LargeBinary, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base

# The kinds of account in a company, with the middle part of their unique ID. The platform's
# administrator is not an account: there is one, named in the server's settings.
ROLES = {"md": "MD", "manager": "MGR", "inspector": "INS", "technical": "TEC", "member": "USR"}
ROLE_NAMES = {"md": "Managing Director", "manager": "Manager", "inspector": "Inspector", "technical": "Technical", "member": "User"}
#: Someone who uses a place and works for nobody in it: a resident, a student, a visitor, a member of
#: staff. They belong to no company, need nobody's approval, and send what they notice to the place.
MEMBER = "member"
#: Those who have people answering to them, and so hand out a code to sign up with.
LEADS = ("md", "manager")

# Where an account stands, in the order it moves through them.
EMAIL_PENDING = "email_pending"        # signed up; the email address is not confirmed yet
PENDING_APPROVAL = "pending_approval"  # email confirmed; waiting for the person above
APPROVED = "approved"                  # has a unique ID and can sign in
REJECTED = "rejected"
SUSPENDED = "suspended"                # was approved; shut out until let back in

# Where an issue stands.
OPEN = "open"            # reported; nobody has been given it
ASSIGNED = "assigned"    # a technician has it
FIXED = "fixed"          # the technician says it is done and has shown a picture; waiting to be checked
CLOSED = "closed"        # checked and accepted
STATUSES = (OPEN, ASSIGNED, FIXED, CLOSED)

SEVERITIES = ("CRITICAL", "HIGH", "MEDIUM", "LOW")


def now() -> datetime:
    return datetime.now(timezone.utc)


def _id() -> str:
    return uuid4().hex


class Company(Base):
    """Made when the administrator approves its managing director."""

    __tablename__ = "company"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=_id)
    name: Mapped[str] = mapped_column(String(160))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    #: Whether the assistant may chase work by itself in this company: remind, and take a stuck issue
    #: one level up. On unless the managing director switches it off.
    autopilot: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=True)
    #: The report type the company's walks start with: one that comes with the app, or one of its own.
    #: Empty leaves the choice to each person.
    report_type: Mapped[str | None] = mapped_column(String(20), nullable=True, default="")
    #: The kind of place it looks after, as the people who use it look for it: "apartment", "college",
    #: "office" and so on. Empty until the managing director says.
    kind: Mapped[str | None] = mapped_column(String(20), nullable=True, default="")
    #: Whether the people who use the place may send it what they notice. Off until the managing
    #: director opens the door; only then is the company's name shown to them.
    open_door: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)


class ReportType(Base):
    """
    A report type a company made for its own kind of place: a bus depot, a temple, a data centre. The
    phones of its people fetch it, so nothing has to be built into the app for it.
    """

    __tablename__ = "report_type"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    company_id: Mapped[str] = mapped_column(String(32), index=True)
    #: What a walk of this type is stored under, on the phone and here: "OWN-" and eight letters or digits.
    key: Mapped[str] = mapped_column(String(20), unique=True)
    title: Mapped[str] = mapped_column(String(40))
    #: What is walked and looked at, in the company's words.
    checks: Mapped[str] = mapped_column(String(200), default="")
    #: What the place is called.
    place: Mapped[str] = mapped_column(String(30), default="")
    #: The kinds an issue can be sorted into, as a JSON list; the app adds "Other".
    categories: Mapped[str] = mapped_column(Text, default="[]")
    #: What counts as serious there, in the company's words; the phone's model is told it.
    serious: Mapped[str] = mapped_column(String(300), default="")
    made_by: Mapped[str] = mapped_column(String(120), default="")
    made_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)


class Account(Base):
    __tablename__ = "account"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=_id)
    role: Mapped[str] = mapped_column(String(16))
    name: Mapped[str] = mapped_column(String(120))
    email: Mapped[str] = mapped_column(String(254), index=True)
    phone: Mapped[str] = mapped_column(String(16))
    #: The company's name: typed by a managing director, taken from the company for everyone else.
    organisation: Mapped[str] = mapped_column(String(160), default="")

    #: The company the person belongs to. A managing director gets one on approval.
    company_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: Who this person answers to: a manager's managing director, an inspector's or technician's manager.
    #: That person approves the sign-up.
    reports_to: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: The code a managing director or manager gives to the people who are to answer to them.
    invite_code: Mapped[str | None] = mapped_column(String(12), nullable=True, unique=True)

    # The Aadhaar number is never stored as typed: only encrypted, with its last four digits beside it for
    # display and a keyed fingerprint to notice the same number being registered twice.
    aadhaar_sealed: Mapped[bytes] = mapped_column(LargeBinary)
    aadhaar_last4: Mapped[str] = mapped_column(String(4))
    aadhaar_fingerprint: Mapped[str] = mapped_column(String(64), index=True)

    #: File name of the profile photo in the photos folder.
    photo: Mapped[str] = mapped_column(String(64))
    password_hash: Mapped[str] = mapped_column(String(255))

    status: Mapped[str] = mapped_column(String(20), default=EMAIL_PENDING, index=True)
    #: Given on approval, e.g. "DL-INS-0042". It is what the person signs in with.
    unique_id: Mapped[str | None] = mapped_column(String(20), unique=True, nullable=True)
    #: Hash of the ticket the phone holds to ask how its sign-up is doing, before it can sign in.
    ticket_hash: Mapped[str] = mapped_column(String(64))

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    email_verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    decided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    decided_by: Mapped[str] = mapped_column(String(254), default="")
    reject_reason: Mapped[str] = mapped_column(Text, default="")

    # Wrong passwords in a row, and until when sign-in is refused after too many.
    failed_logins: Mapped[int] = mapped_column(Integer, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    #: When the app or a dashboard last asked the server something as this person.
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Otp(Base):
    """A one-time code: emailed to confirm an address or reset a password, or handed to the app to open
    the person's dashboard in a browser. Only its hash is kept."""

    __tablename__ = "otp"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    account_id: Mapped[str] = mapped_column(ForeignKey(Account.id, ondelete="CASCADE"), index=True)
    #: "signup", "reset" or "web".
    purpose: Mapped[str] = mapped_column(String(10))
    code_hash: Mapped[str] = mapped_column(String(64))
    sent_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Counter(Base):
    """The last number given out for each kind of account."""

    __tablename__ = "counter"

    role: Mapped[str] = mapped_column(String(16), primary_key=True)
    last: Mapped[int] = mapped_column(Integer, default=0)


class Report(Base):
    """One walk of a site, as the phone that made it sent it: the PDF and what it holds."""

    __tablename__ = "report"
    # A report built again for the same walk takes the place of the one sent before.
    __table_args__ = (UniqueConstraint("account_id", "walk"),)

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=_id)
    #: Who made it.
    account_id: Mapped[str] = mapped_column(String(32), index=True)
    company_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: The phone's own name for the walk.
    walk: Mapped[str] = mapped_column(String(40))
    #: The kind of report, as the app names it: SITE, MEDICATION or COMMUNITY.
    template: Mapped[str] = mapped_column(String(20), default="")
    title: Mapped[str] = mapped_column(String(80), default="")
    place: Mapped[str] = mapped_column(String(200), default="")
    #: The name printed on the report.
    person: Mapped[str] = mapped_column(String(120), default="")
    built_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    issue_count: Mapped[int] = mapped_column(Integer, default=0)
    #: What the phone's own review made of the report: GENUINE, DOUBTFUL, FAKE or blank.
    verdict: Mapped[str] = mapped_column(String(12), default="")
    verdict_reason: Mapped[str] = mapped_column(Text, default="")
    #: True when the inspector signed the PDF with their fingerprint.
    signed: Mapped[bool] = mapped_column(Boolean, default=False)
    #: File name of the PDF in the reports folder; blank while the walk is going on and none has been built.
    pdf: Mapped[str] = mapped_column(String(64), default="")
    #: True when issues have arrived since the last PDF was built, or before any was.
    in_progress: Mapped[bool | None] = mapped_column(Boolean, nullable=True, default=False)
    pdf_name: Mapped[str] = mapped_column(String(160), default="")


class Issue(Base):
    """One thing found on a walk, and what has been done about it since."""

    __tablename__ = "issue"
    __table_args__ = (UniqueConstraint("report_id", "client_id"),)

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=_id)
    report_id: Mapped[str] = mapped_column(ForeignKey(Report.id, ondelete="CASCADE"), index=True)
    #: The phone's own name for the issue; it stays the same when the report is built again.
    client_id: Mapped[str] = mapped_column(String(40))
    company_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: Who reported it.
    author_id: Mapped[str] = mapped_column(String(32), index=True)
    number: Mapped[int] = mapped_column(Integer, default=0)
    site: Mapped[str] = mapped_column(String(200), default="")

    title: Mapped[str] = mapped_column(String(200), default="")
    note: Mapped[str] = mapped_column(Text, default="")
    location: Mapped[str] = mapped_column(String(200), default="")
    category: Mapped[str] = mapped_column(String(60), default="")
    severity: Mapped[str] = mapped_column(String(10), default="MEDIUM")
    #: The person named in the spoken note as the one to fix it, and the deadline as it was said.
    owner: Mapped[str] = mapped_column(String(120), default="")
    due_text: Mapped[str] = mapped_column(String(60), default="")
    due_on: Mapped[date | None] = mapped_column(Date, nullable=True)
    #: What the on-device model made of the picture: MATCH, UNSURE, MISMATCH, CONFIRMED or NONE.
    ai_check: Mapped[str] = mapped_column(String(12), default="")
    seen: Mapped[str] = mapped_column(Text, default="")
    #: What the phone's sensors and instruments read, in words.
    measured: Mapped[str] = mapped_column(Text, default="")
    lat: Mapped[float | None] = mapped_column(Float, nullable=True)
    lon: Mapped[float | None] = mapped_column(Float, nullable=True)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    photo: Mapped[str] = mapped_column(String(64), default="")

    status: Mapped[str] = mapped_column(String(12), default=OPEN, index=True)
    assigned_to: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    assigned_by: Mapped[str] = mapped_column(String(120), default="")
    assigned_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    fix_note: Mapped[str] = mapped_column(Text, default="")
    fix_photo: Mapped[str] = mapped_column(String(64), default="")
    fixed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    closed_by: Mapped[str] = mapped_column(String(120), default="")
    #: For what a user of the place sent in: "issue", "report" or "enquiry". Empty for what was found on a walk.
    raised_as: Mapped[str | None] = mapped_column(String(12), nullable=True, default="")


class Event(Base):
    """Something that happened to a report or an issue, kept for the history and the dashboards."""

    __tablename__ = "event"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, index=True)
    company_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    report_id: Mapped[str | None] = mapped_column(String(32), nullable=True)
    issue_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: Whose report or issue it is about, and who has been given the issue: they decide who may see it.
    author_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    assignee_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: "report", "assigned", "fixed", "closed", "reopened", "sent" or "joined".
    kind: Mapped[str] = mapped_column(String(12))
    actor: Mapped[str] = mapped_column(String(120), default="")
    text: Mapped[str] = mapped_column(Text, default="")


class Message(Base):
    """
    A word from one person to another about an issue: sent on with a note, asked about, or answered. It
    waits on the person it is for until they answer, send the issue on, or do the work.
    """

    __tablename__ = "message"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, index=True)
    issue_id: Mapped[str] = mapped_column(ForeignKey(Issue.id, ondelete="CASCADE"), index=True)
    company_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: Who wrote it; nobody's account when it was the administrator or the assistant acting by itself.
    author_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    #: For a message the assistant sent by itself, the rule that made it send: each rule speaks once.
    rule: Mapped[str | None] = mapped_column(String(40), nullable=True, default="")
    author: Mapped[str] = mapped_column(String(120), default="")
    author_role: Mapped[str] = mapped_column(String(16), default="")
    #: Who it is for: a person, or the administrator.
    to_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    to_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    to_name: Mapped[str] = mapped_column(String(120), default="")
    text: Mapped[str] = mapped_column(Text, default="")
    #: When the person it was for dealt with it; empty while it still waits on them.
    done_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Brief(Base):
    """
    What the writing model made of an issue, kept so the model is asked once for each telling of it and
    the dashboards never wait for it.
    """

    __tablename__ = "brief"

    issue_id: Mapped[str] = mapped_column(ForeignKey(Issue.id, ondelete="CASCADE"), primary_key=True)
    #: A mark of what the issue said when the summary was written; when the issue changes, it is written again.
    mark: Mapped[str] = mapped_column(String(64), default="")
    #: What is wrong and why it matters, in a sentence or two.
    summary: Mapped[str] = mapped_column(Text, default="")
    #: What the messages about it come to so far, and the last message that was read for it.
    talk: Mapped[str] = mapped_column(Text, default="")
    talk_upto: Mapped[int] = mapped_column(Integer, default=0)
    #: The name of the model that wrote it.
    engine: Mapped[str] = mapped_column(String(80), default="")
    made_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    #: How often the model has failed on this telling; after a few it is left to the built-in wording.
    tries: Mapped[int] = mapped_column(Integer, default=0)


class Enquiry(Base):
    """
    What a visitor to the home page asked: whether DeskLess fits what they need. Kept with the answer they
    were given, and with their email address once they ask for the brochure, so the team can follow it up.
    """

    __tablename__ = "enquiry"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=_id)
    asked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    #: Their own words.
    text: Mapped[str] = mapped_column(Text, default="")
    #: The kind of place found in them or chosen, by its key; and the verdict: yes, yes_but, partly, no, likely, unclear.
    place: Mapped[str] = mapped_column(String(24), default="")
    verdict: Mapped[str] = mapped_column(String(12), default="")
    #: The answer as it was shown, as JSON, so the brochure's email says the same.
    reply: Mapped[str] = mapped_column(Text, default="")
    #: Filled when they ask for the brochure.
    name: Mapped[str] = mapped_column(String(80), default="")
    email: Mapped[str] = mapped_column(String(254), default="")
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    #: A mark of where the question came from that cannot be read back, to tell one asker from another.
    asker: Mapped[str] = mapped_column(String(32), default="")
