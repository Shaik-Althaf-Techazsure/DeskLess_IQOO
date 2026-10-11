"""
The form on the home page: somebody who does not have DeskLess yet writes what they need, is told plainly
whether it fits (`app/fit.py` decides), and may then have the brochure emailed: the PDF itself, attached,
with a link straight to WhatsApp for the price. Nobody is signed in here, so every door is counted: how often one address may
ask, how many brochures it may have sent, and how often one mailbox may be written to. The email carries
nothing the asker typed except a name, so the form cannot be used to send somebody else a message.
"""

import json
import re
import time
from datetime import timedelta
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy.orm import Session

from .. import fit, front, mailer, security
from ..config import settings
from ..db import get_db
from ..models import Enquiry, now
from ..service import get_mailer

router = APIRouter(prefix="/api/fit", tags=["fit"])

_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]{2,}$")
#: How many questions one address may ask in ten minutes, how many brochures it may have sent in an hour,
#: how long before the same mailbox is written to again, and how many brochures leave in a day in all.
ASKS, ASKS_SECONDS = 20, 600
SENDS, SENDS_SECONDS = 4, 3600
SAME_MAILBOX_SECONDS = 3600
A_DAY, DAY_SECONDS = 300, 86400
TICKET_HOURS = 6
#: A brochure larger than this is linked to, not attached.
MAX_ATTACHED = 8 * 1024 * 1024

_counted: dict[str, list[float]] = {}


def _address(request: Request) -> str:
    """Where the request really comes from. Behind the site's web server that is the address it passes on."""
    return request.headers.get("x-real-ip") or (request.client.host if request.client else "")


def _allowed(what: str, most: int, seconds: int) -> bool:
    """Whether this may happen once more within its time, and if so, counts it."""
    moment = time.time()
    if len(_counted) > 5000:
        for key in [key for key, times in _counted.items() if not times or times[-1] < moment - DAY_SECONDS]:
            del _counted[key]
    times = [at for at in _counted.get(what, []) if at > moment - seconds]
    if len(times) >= most:
        _counted[what] = times
        return False
    _counted[what] = times + [moment]
    return True


def _whatsapp(told: dict) -> str:
    """A link that opens WhatsApp to the team with the first message written."""
    place = (told.get("place") or {}).get("name")
    words = f"Hello, I asked DeskLess whether it fits {place or 'my place'}. Please share the pricing."
    return f"https://wa.me/{settings().contact_whatsapp}?text={quote(words)}"


def _phone() -> str:
    """The team's number as it is written for people to read."""
    digits = settings().contact_whatsapp
    return f"+91 {digits[2:7]} {digits[7:]}" if len(digits) == 12 and digits.startswith("91") else "+" + digits


def _home(request: Request) -> str:
    """The home page's address as people reach it from outside."""
    return (settings().public_url or str(request.base_url).rstrip("/")) + "/"


class Asked(BaseModel):
    text: str
    #: The kind of place, where the asker chose one; else it is looked for in their words.
    place: str = ""


@router.post("")
def does_it_fit(body: Asked, request: Request, db: Session = Depends(get_db)) -> dict:
    """Reads what somebody needs against what DeskLess does, and answers yes, partly or no, with the reasons."""
    text = " ".join(body.text.split())
    if len(text) < fit.MIN_LETTERS:
        raise HTTPException(422, "Tell me a little more: what goes wrong, and in what kind of place?")
    if len(text) > fit.MAX_LETTERS:
        raise HTTPException(422, f"That is a lot to read at once. Keep it under {fit.MAX_LETTERS} letters.")
    if not _allowed("ask:" + _address(request), ASKS, ASKS_SECONDS):
        raise HTTPException(429, "You have asked many times in a short while. Try again in a few minutes.")
    told = fit.answer(text, body.place if body.place in fit.PLACE_KEYS else "")
    told.pop("keys", None)
    asked = Enquiry(text=text, place=(told["place"] or {}).get("key", ""), verdict=told["verdict"], reply=json.dumps(told),
                    asker=security.keyed_hash("asker", _address(request))[:32])
    db.add(asked)
    db.commit()
    ticket = security.make_token(asked.id, "fit", timedelta(hours=TICKET_HOURS))
    return told | {"ticket": ticket, "whatsapp": _whatsapp(told)}


class Wanted(BaseModel):
    #: The pass given with the answer.
    ticket: str
    email: str
    name: str = ""


@router.post("/brochure")
def send_brochure(body: Wanted, request: Request, db: Session = Depends(get_db), post: mailer.Mailer = Depends(get_mailer)) -> dict:
    """Emails the brochure to whoever asked, with the answer they were given and the WhatsApp link, and tells the team."""
    asked = db.get(Enquiry, security.read_token(body.ticket, "fit") or "")
    if asked is None:
        raise HTTPException(400, "That took too long. Ask again, and then ask for the brochure.")
    email = body.email.strip().lower()
    if not _EMAIL.fullmatch(email) or len(email) > 254:
        raise HTTPException(422, "That email address does not look right.")
    # A name is a few words of letters; anything else in it is dropped, since it is repeated in the email.
    name = " ".join(re.sub(r"[^\w .'-]", " ", body.name).replace("_", " ").split())[:60]
    told = json.loads(asked.reply)
    home = _home(request)
    papers = front.papers()
    at = lambda name: f"{home}static/site/{name}" if name else ""
    links = {"site": home, "brochure": at(papers["brochure"]), "deck": at(papers["deck"]), "whatsapp": _whatsapp(told)}
    # The brochure itself travels with the email, where it is in the folder and small enough to be welcome.
    files = ()
    if papers["brochure"] and (front.SITE / papers["brochure"]).stat().st_size <= MAX_ATTACHED:
        files = (("DeskLess-Brochure.pdf", (front.SITE / papers["brochure"]).read_bytes()),)
    if not _allowed("mailbox:" + email, 1, SAME_MAILBOX_SECONDS):
        raise HTTPException(429, "The brochure was sent to that address a little while ago. Look in the inbox, and in the spam folder.")
    if not _allowed("send:" + _address(request), SENDS, SENDS_SECONDS) or not _allowed("all", A_DAY, DAY_SECONDS):
        raise HTTPException(429, "Too many brochures have been asked for from here just now. Try again later, or message us on WhatsApp.")
    config = settings()
    subject, text, html = mailer.brochure_email(name, told, links, config.admin_email, _phone(), attached=bool(files))
    try:
        sent = post.send(email, subject, text, html=html, reply_to=config.admin_email, files=files) is not False
    except mailer.MailError:
        sent = False
    if sent:
        asked.name, asked.email, asked.sent_at = name, email, now()
        db.commit()
        # The team hears of it as a courtesy: a mail server that fails now must not undo what was sent.
        try:
            post.send(config.admin_email, *mailer.enquiry_email(name, email, asked.text, told), reply_to=email)
        except mailer.MailError:
            pass
    # With no mail server, or one that refused, the page hands over the same links itself.
    return links | {"sent": sent, "email": email, "attached": bool(files) and sent}
