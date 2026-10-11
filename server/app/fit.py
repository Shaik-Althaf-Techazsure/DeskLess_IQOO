"""
Whether DeskLess fits what somebody needs. A visitor to the home page writes what they want in their own
words; this reads it against what the product does today and answers plainly: yes, partly, or no.

The same rule as everywhere else in DeskLess: plain code decides, a model only words. What the product
does, what it does with a limit and what it is not for are written down here, each with the words people
use for it, and the verdict follows from which of them the request touches. Nothing is promised that is
not on these lists. Where a writing model runs beside the server it may word the opening sentences, from
the points already decided, and what it writes is checked before it is used.
"""

import json
import re
from dataclasses import dataclass

from . import writer


@dataclass(frozen=True)
class Point:
    key: str
    #: A few words, as a heading.
    title: str
    #: One sentence said to the person asking.
    says: str
    #: How people ask for it: beginnings of words, or phrases, in small letters.
    words: tuple[str, ...]


# ---------------------------------------------------------------- what DeskLess does today

DOES = (
    Point("report", "Reporting a problem by voice and photo",
          "Anyone can report a problem by taking a picture and saying what is wrong; the phone writes the entry.",
          ("complain", "complaint", "issue", "problem", "report", "ticket", "grievance", "fault", "breakdown", "defect", "snag", "broken", "not working", "leak", "damage", "shikayat", "kharab")),
    Point("rounds", "Inspection rounds with a finished report",
          "An inspector walks the place and leaves with a finished, signed PDF report: nothing is typed up afterwards.",
          ("inspect", "audit", "round", "checklist", "walk", "survey", "patrol", "handover", "site visit", "safety check", "housekeeping check", "maintenance check", "daily check")),
    Point("voice", "Speaking instead of typing",
          "People speak in Hindi, English or the mix of both, and it is written down in English on the phone.",
          ("voice", "speak", "spoken", "speech", "hindi", "hinglish", "cannot type", "can't type", "no typing", "without typing", "typing", "illiterate", "bol")),
    Point("offline", "Working with no network",
          "The AI runs inside the phone, so a report is made with no signal at all and sent when a network is found.",
          ("offline", "no network", "no internet", "without internet", "no signal", "poor network", "bad network", "basement", "connectivity", "airplane", "remote area", "no coverage")),
    Point("assign", "Giving each issue to a named person and following it",
          "Every issue is ranked, given to a named person with a date, and followed until it is closed.",
          ("assign", "track", "follow up", "follow-up", "status", "pending", "deadline", "due date", "overdue", "accountab", "responsib", "workflow", "delay", "who is", "nobody", "lost", "forgot", "resolve", "close")),
    Point("proof", "Proof that a repair was done",
          "A repair is closed with a photo, which the phone compares with the picture the problem was reported with.",
          ("proof", "evidence", "before and after", "verify", "verification", "really fixed", "actually fixed", "fake", "photo of the repair", "confirm the repair")),
    Point("console", "A live console for every role",
          "Managers and directors see what is open, late and closed on a live console in any browser.",
          ("dashboard", "console", "monitor", "overview", "management", "analytics", "real time", "real-time", "live view", "at a glance", "statistics")),
    Point("users", "The people who use the place report too",
          "Residents, students, staff and visitors report from their own phones with the DeskLess User app, and need no approval.",
          ("resident", "tenant", "student", "patient", "visitor", "customer", "public", "citizen", "guest", "occupant", "member", "anyone can", "everyone can")),
    Point("hears", "The person who reported hears back",
          "Whoever reported a problem is told who has it, what was answered and when it was repaired and closed.",
          ("notify", "notification", "hear back", "update", "inform", "feedback", "acknowledg", "reply", "answer")),
    Point("chases", "Late work is chased by itself",
          "When something urgent sits untaken or a job runs past its day, the assistant reminds the right person and takes it one level up.",
          ("remind", "chase", "escalat", "sla|", "alert", "running late", "gets delayed")),
    Point("private", "Your data on your own server",
          "Speech is turned into words on the phone, and what is sent goes only to your organisation's own server, which you can host yourself.",
          ("privacy", "private", "confidential", "data stays", "on-premise", "on premise", "self-host", "self host", "own server", "secure", "security of", "sensitive")),
    Point("where", "Where each issue is",
          "Each issue carries the phone's position, a place named by an NFC tag where you use them, and a map of the round.",
          ("location", "gps|", "map", "where it is", "position", "which floor", "nfc|", "tag", "geo")),
    Point("sensors", "Readings from the phone's sensors",
          "Level, noise and vibration can be read with the phone and stamped on an issue, as a guide.",
          ("noise", "vibration", "slope", "tilt", "spirit level", "lux|", "light level", "measure", "sensor reading")),
    Point("files", "PDF, spreadsheet and calendar files",
          "Each report comes as a PDF with a spreadsheet of its issues, a calendar file of deadlines and a task list for each owner.",
          ("pdf", "excel", "csv", "spreadsheet", "export", "calendar", "print", "document")),
    Point("own", "Your own kinds of check",
          "You make your own report type in a minute: its name, the kinds of issue and what counts as serious in your place.",
          ("custom", "our own", "own checklist", "own categories", "template", "specific to", "tailor", "configur", "categories")),
    Point("expiry", "Medication audits that read expiry dates",
          "In a medication audit the phone reads the expiry date printed on a pack and raises expired stock by itself.",
          ("expiry", "expired", "medicine", "medication", "pharmac", "drug")),
    Point("talk", "Talking between phones with no network",
          "Linked phones pass reports and push-to-talk voice to each other over Bluetooth and Wi-Fi, with no internet.",
          ("walkie", "push to talk", "intercom", "talk to each other", "phone to phone", "bluetooth")),
    Point("signed", "Reports signed by a person",
          "A finished report is signed with a fingerprint, and a changed copy no longer matches its signature.",
          ("signature", "signed", "sign off", "sign-off", "tamper", "authentic", "fingerprint")),
    Point("many", "Several places and teams in one organisation",
          "One organisation holds as many places, managers and teams as it has, and its director sees them side by side.",
          ("multiple", "several", "branches", "many sites", "many buildings", "all our", "across", "chain of", "locations")),
    Point("roles", "Accounts approved by the level above",
          "Everyone signs in with their own ID, approved by the person they report to, and sees only their part.",
          ("role", "permission", "approve", "approval", "hierarchy", "access control", "login", "account")),
    Point("clip", "A short clip instead of a photo",
          "An issue can be filmed for up to 20 seconds while it is described.",
          ("video", "clip", "film")),
)

# ---------------------------------------------------------------- what it does, with something to know

LIMITS = (
    Point("languages", "Other Indian languages",
          "Kannada, Tamil, Telugu, Marathi and Bengali can be chosen for speaking, but they are still being tested and are less accurate than Hindi and English.",
          ("kannada", "tamil", "telugu", "marathi", "bengali", "bangla", "regional language", "local language", "gujarati", "punjabi", "malayalam", "urdu", "odia")),
    Point("android", "Android only",
          "The app runs on Android phones today. There is no iPhone app yet; the consoles work in any browser.",
          ("iphone", "ios|", "apple", "ipad")),
    Point("messages", "No SMS, WhatsApp or push messages",
          "People are told in the app while it is open and by email. It does not send SMS, WhatsApp or push messages yet.",
          ("sms|", "whatsapp notif", "whatsapp alert", "whatsapp integration", "whatsapp message", "send on whatsapp", "notify on whatsapp", "push notification", "text message", "when the app is closed")),
    Point("links", "No ready-made link to other software",
          "It gives you PDF, spreadsheet and calendar files; there is no ready-made link to an ERP or another system yet.",
          ("erp|", "sap|", "tally|", "integrat", "api|", "existing software", "existing system", "our software", "sync with")),
    Point("guide", "Readings are a guide",
          "The phone's level, noise and vibration readings are a guide and are not calibrated instruments.",
          ("calibrat", "certified", "accurate measurement", "legal metrology")),
    Point("reach", "Phone-to-phone reach",
          "Phone to phone it reaches some tens of metres, or further through DeskLess phones in between; it is not a radio set.",
          ("long range", "kilomet", "across the city", "far away")),
)

# ---------------------------------------------------------------- what it is not for

NOT = (
    Point("people", "Attendance, payroll and rosters",
          "It is not an HR system: no attendance, payroll, leave or shift rosters.",
          ("attendance", "payroll", "salary", "salaries", "leave management", "roster", "shift schedul", "biometric attendance", "hr|", "human resource", "recruit")),
    Point("money", "Billing and payments",
          "It does not handle money: no billing, invoices, fees, rent or payments.",
          ("billing", "invoice", "payment", "accounting", "rent collection", "maintenance fee", "fees|", "gst|", "ledger")),
    Point("stock", "Stock and purchasing",
          "It does not keep stock, assets or purchase orders.",
          ("inventory", "stock management", "purchase order", "procure", "asset register", "asset management", "warehouse management", "spare parts")),
    Point("gate", "Visitors and gate passes",
          "It is not a visitor or gate system.",
          ("visitor management", "gate pass", "entry log", "visitor entry", "guest entry", "delivery entry")),
    Point("booking", "Bookings and timetables",
          "It does not take bookings or make timetables.",
          ("booking", "reservation", "appointment", "timetable", "schedule classes", "room allocation")),
    Point("cameras", "Cameras and fixed sensors",
          "It works from what a person sees and says; it does not watch CCTV or read fixed sensors and machines.",
          ("cctv|", "surveillance", "face recognition", "camera feed", "iot|", "scada", "bms|", "automatic detection", "detect automatically", "predictive")),
    Point("medical", "Medical records and advice",
          "It reports on the place, never on a patient, and gives no medical advice or records.",
          ("diagnos", "treatment", "patient record", "medical record", "emr|", "ehr|", "prescription", "medical advice")),
    Point("selling", "Selling and customers",
          "It is not a shop, a delivery app or a customer database.",
          ("e-commerce", "ecommerce", "online store", "delivery app", "crm|", "sales team", "marketing")),
    Point("learning", "Teaching and exams",
          "It is not for lessons, exams or marks.",
          ("exam|", "exams|", "lms|", "online class", "marksheet", "syllabus")),
)

# ---------------------------------------------------------------- the kind of place, and the report type that comes for it

PLACES = (
    ("medication", "a pharmacy", "Medication audit", ("pharmacy", "chemist", "medical store", "dispensary")),
    ("hospital", "a hospital", "Hospital round", ("hospital", "clinic", "nursing home", "healthcare", "icu|", "medical college")),
    ("campus", "a college or school", "Campus inspection", ("college", "school", "universit", "campus", "hostel", "institute", "classroom")),
    ("community", "a neighbourhood", "Community issue", ("neighbourhood", "neighborhood", "colony", "streetlight", "street light", "pothole", "drainage", "garbage", "panchayat", "municipal", "civic", "ward office", "village")),
    ("residential", "an apartment block or housing society", "Apartment inspection", ("apartment", "society", "flats", "residential", "housing", "rwa|", "gated community", "villa")),
    ("warehouse", "a warehouse or depot", "Warehouse inspection", ("warehouse", "godown", "depot", "logistics", "cold storage")),
    ("factory", "a factory", "Factory round", ("factory", "manufactur", "workshop", "industrial", "production line", "shop floor", "plant")),
    ("hotel", "a hotel or restaurant", "Hotel inspection", ("hotel", "restaurant", "resort", "hospitality", "guest house", "lodge")),
    ("retail", "a mall or shop", "Mall inspection", ("mall", "shop", "retail", "supermarket", "food court", "showroom")),
    ("site", "a construction site", "Site inspection", ("construction", "builder", "contractor", "project site", "civil work", "fit-out", "fit out")),
    ("office", "an office", "Office inspection", ("office", "government", "department", "corporate", "workplace", "coworking")),
)
PLACE_KEYS = {key for key, *_ in PLACES}

# What DeskLess is for at its heart. A request that touches none of these and asks for something on the
# list of what it is not for is answered no, even if it mentions students or a PDF in passing.
CORE = {"report", "rounds", "assign", "proof", "offline", "voice", "expiry"}

MIN_LETTERS = 12
MAX_LETTERS = 1500


def _touches(text: str, words: tuple[str, ...]) -> bool:
    """
    Whether the request uses one of these words. A word is looked for at the start of a word, so "assign"
    also finds "assigned"; one written with a stroke after it ("exam|") only as the whole word, so that
    it does not find "example".
    """
    for word in words:
        whole = word.endswith("|")
        if re.search(r"(?<![a-z])" + re.escape(word.rstrip("|")) + (r"(?![a-z])" if whole else ""), text):
            return True
    return False


def _place(text: str, chosen: str = ""):
    for place in PLACES:
        if place[0] == chosen:
            return place
    return next((place for place in PLACES if _touches(text, place[3])), None)


def read(text: str, chosen: str = "") -> dict:
    """What the request touches, and the verdict that follows from it. Nothing here is worded yet."""
    said = " " + " ".join(text.lower().split()) + " "
    does = [point for point in DOES if _touches(said, point.words)]
    limits = [point for point in LIMITS if _touches(said, point.words)]
    nots = [point for point in NOT if _touches(said, point.words)]
    place = _place(said, chosen)
    if nots and not any(point.key in CORE for point in does):
        verdict, does = "no", []
    elif does and nots:
        verdict = "partly"
    elif does and limits:
        verdict = "yes_but"
    elif does:
        verdict = "yes"
    elif limits:
        verdict = "partly"
    elif place:
        verdict = "likely"
    else:
        verdict = "unclear"
    return {"verdict": verdict, "does": does, "limits": limits, "nots": nots, "place": place}


_HEADLINE = {
    "yes": "Yes. DeskLess does this today.",
    "yes_but": "Yes, with something to know first.",
    "partly": "Partly. DeskLess covers some of this, and not the rest.",
    "no": "No. This is not what DeskLess is for.",
    "likely": "Very likely. Tell me what goes wrong there.",
    "unclear": "I could not tell from that. Tell me a little more.",
}


def _count(n: int, one: str, many: str) -> str:
    return f"{n} {one if n == 1 else many}"


def _summary(found: dict) -> str:
    """The opening sentences, made by plain code from what was found."""
    verdict, place = found["verdict"], found["place"]
    here = f"For {place[1]}, DeskLess comes with the {place[2]} report type. " if place else ""
    does, limits, outside = len(found["does"]), len(found["limits"]), len(found["nots"]) + len(found["limits"])
    covered = "what you describe" if does == 1 else "both of the things you describe" if does == 2 else f"all {does} of the things you describe"
    if verdict == "yes":
        return f"{here}It does {covered} today, as listed below, and nothing you asked for is outside it."
    if verdict == "yes_but":
        return f"{here}It does {covered}, with {_count(limits, 'thing', 'things')} to know before you decide."
    if verdict == "partly" and does:
        return (f"{here}It does {_count(does, 'part', 'parts')} of what you describe. {_count(outside, 'part', 'parts')} it does not do, "
                "or does only within a limit, and it is better you hear that now.")
    if verdict == "partly":
        return f"{here}What you ask for is something DeskLess does only within a limit today, and it is better you hear that now."
    if verdict == "no":
        return ("DeskLess is for finding what is wrong in a place, getting it to the person who fixes it and proving the repair. "
                "What you describe is a different kind of system, and it would be wrong to tell you otherwise.")
    if verdict == "likely":
        return (f"{here}It is made for exactly such places: people say what is wrong, the phone writes it up with no network, "
                "and it reaches whoever must fix it. Say what goes wrong in yours and who should deal with it, and I will be exact.")
    return ("DeskLess is for places that are walked and looked after: someone says what is wrong, the phone writes the report, "
            "and the right person gets it and proves the repair. Tell me what kind of place you have and what keeps going wrong.")


def _start(found: dict) -> list[str]:
    """How somebody would begin, when it fits at all."""
    if found["verdict"] in ("no", "unclear"):
        return []
    place = found["place"]
    kind = f"Start your rounds with the {place[2]} report type, or make your own in a minute." if place else "Pick the report type for your kind of place, or make your own in a minute."
    return [
        "Sign up as managing director with your organisation's name; your team joins with the code you are given.",
        kind,
        "Open your place to the people who use it, so they can report from the DeskLess User app.",
    ]


# ---------------------------------------------------------------- the model's part: wording, checked

_OTHERS = {point.key: point for point in LIMITS + NOT}


def _worded(found: dict, text: str) -> str:
    """
    The opening sentences in the writing model's words, or nothing. It is asked only when the answer is a
    plain yes, shown only the points already decided, and what it writes is dropped if it is too long,
    carries an address, or names anything from the lists of limits and of what DeskLess is not for.
    """
    if found["verdict"] != "yes" or not writer.reachable():
        return ""
    facts = "\n".join(f"- {point.says}" for point in found["does"])
    place = f"The place is {found['place'][1]}; DeskLess has the {found['place'][2]} report type for it.\n" if found["place"] else ""
    prompt = (
        "You answer a possible customer of DeskLess, an app for reporting and repairing what is wrong in a place.\n"
        "They wrote what they need. It has already been decided that DeskLess fits, for these reasons only:\n"
        f"{facts}\n{place}"
        "Write two short, plain sentences to them saying that it fits and why, using only these reasons. "
        "Promise nothing else. No prices, no greetings, no web addresses.\n"
        f"What they wrote: \"{text[:400]}\"\n"
        'Answer as JSON: {"reply": "..."}'
    )
    try:
        reply = str(json.loads(writer.ask(prompt, seconds=12) or "{}").get("reply", "")).strip()
    except (ValueError, AttributeError):
        return ""
    low = " " + reply.lower() + " "
    if not 40 <= len(reply) <= 420 or "http" in low or "www." in low or "₹" in reply or "rs." in low:
        return ""
    if any(_touches(low, point.words) for point in _OTHERS.values()):
        return ""
    return reply


def answer(text: str, chosen: str = "") -> dict:
    """The whole reply to a request, as the page shows it and as the brochure's email repeats it."""
    found = read(text, chosen)
    worded = _worded(found, text)
    place = found["place"]
    return {
        "verdict": found["verdict"],
        "headline": _HEADLINE[found["verdict"]],
        "summary": worded or _summary(found),
        "does": [{"title": point.title, "says": point.says} for point in found["does"]],
        "limits": [{"title": point.title, "says": point.says} for point in found["limits"]],
        "not": [{"title": point.title, "says": point.says} for point in found["nots"]],
        "place": {"key": place[0], "name": place[1], "type": place[2]} if place else None,
        "start": _start(found),
        # Who worded the opening: the model's name where it did, else the rules themselves.
        "engine": writer.status()["model"] if worded else "",
        "keys": [point.key for point in found["does"] + found["limits"] + found["nots"]],
    }
