"""
Three places to show DeskLess with: an apartment block, a college and an office park, each with a managing
director, a manager, an inspector and a technician who are already approved, and each open to the people
who use it. Run it where the server runs:

    docker compose -f docker-compose.server.yml exec server python -m app.demo

It prints the DeskLess ID of each of the twelve and the one password they share, which is made up anew
every time it is run; nothing is made twice. Nothing else on the server is touched. When the showing is
over,

    docker compose -f docker-compose.server.yml exec server python -m app.demo off

closes the three places to users and shuts the twelve accounts; what they did stays in the records.

These are stand-ins and say so: their email addresses end in a domain that cannot receive mail, and their
Aadhaar numbers are from the range kept for testing.
"""

import secrets
import sys

from PIL import Image
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import security
from .config import settings
from .db import SessionLocal, init_db
from .models import APPROVED, LEADS, ROLE_NAMES, SUSPENDED, Account, Company, now
from .service import fresh_invite, next_unique_id, note

#: The mark of a stand-in: an address at a name that is kept for testing and reaches nobody.
DOMAIN = "demo.deskless.test"

#: Each place: its name, its kind as users look for it, the report type its walks start with, its colour
#: on the stand-in photos, and its people from the top down.
PLACES = (
    ("Orchid Residency", "apartment", "RESIDENTIAL", (62, 207, 122),
     (("md", "Anita Menon"), ("manager", "Rahul Shetty"), ("inspector", "Kavya Nair"), ("technical", "Imran Sheikh"))),
    ("Northgate College", "college", "CAMPUS", (57, 135, 229),
     (("md", "Suresh Rao"), ("manager", "Priya Kulkarni"), ("inspector", "Vikram Joshi"), ("technical", "Lakshmi Devi"))),
    ("Lakeview Business Park", "office", "OFFICE", (255, 195, 31),
     (("md", "Neha Kapoor"), ("manager", "Arvind Pillai"), ("inspector", "Sneha Reddy"), ("technical", "Manoj Yadav"))),
)


def _address(name: str) -> str:
    return name.lower().replace(" ", ".") + "@" + DOMAIN


def _test_aadhaar(db: Session) -> str:
    """A number from the range kept for testing that nobody on this server has."""
    while True:
        base = "9999" + f"{secrets.randbelow(10_000_000):07d}"
        number = base + security.verhoeff_digit(base)
        if db.scalars(select(Account.id).where(Account.aadhaar_fingerprint == security.aadhaar_fingerprint(number))).first() is None:
            return number


def _person(db: Session, role: str, name: str, firm: Company | None, lead: Account | None, organisation: str, colour: tuple, phone: int, password_hash: str) -> tuple[Account, bool]:
    """The stand-in of this name, made if they are not there yet; and whether they were just made."""
    known = db.scalars(select(Account).where(Account.email == _address(name))).first()
    if known is not None:
        return known, False
    number = _test_aadhaar(db)
    account = Account(
        role=role, name=name, email=_address(name), phone=f"90000{phone:05d}", organisation=organisation,
        company_id=firm.id if firm else None, reports_to=lead.id if lead else None,
        aadhaar_sealed=security.seal_aadhaar(number), aadhaar_last4=number[-4:], aadhaar_fingerprint=security.aadhaar_fingerprint(number),
        photo="", password_hash=password_hash, ticket_hash=security.keyed_hash("ticket", secrets.token_urlsafe(24)),
        status=APPROVED, email_verified_at=now(), decided_at=now(), decided_by="Made for showing",
    )
    db.add(account)
    db.flush()
    account.unique_id = next_unique_id(db, role)
    if role in LEADS:
        account.invite_code = fresh_invite(db)
    folder = settings().media_dir
    folder.mkdir(parents=True, exist_ok=True)
    account.photo = f"{account.id}.jpg"
    Image.new("RGB", (400, 400), colour).save(folder / account.photo, "JPEG", quality=85)
    return account, True


def seed(db: Session, password: str) -> list[dict]:
    """Makes whatever of the three places and their people is not there yet, and gives all twelve [password]."""
    hashed = security.hash_password(password)
    told = []
    phone = 0
    for place, kind, usual, colour, people in PLACES:
        by_role: dict[str, Account] = {}
        firm = None
        for role, name in people:
            phone += 1
            lead = by_role.get("md") if role == "manager" else by_role.get("manager")
            account, fresh = _person(db, role, name, firm, lead, place, colour, phone, hashed)
            if role == "md":
                firm = db.get(Company, account.company_id) if account.company_id else None
                if firm is None:
                    firm = Company(name=place, report_type=usual)
                    db.add(firm)
                    db.flush()
                    account.company_id = firm.id
                firm.kind, firm.open_door = kind, True
            # Run again, everyone is let back in under the new password.
            account.password_hash = hashed
            account.status = APPROVED
            account.failed_logins, account.locked_until = 0, None
            if fresh:
                note(db, "joined", "DeskLess", f"{account.name} joined as {ROLE_NAMES[role]} ({account.unique_id})", company_id=firm.id, author_id=account.id)
            by_role[role] = account
            told.append({"place": place, "kind": kind, "role": ROLE_NAMES[role], "name": name, "unique_id": account.unique_id})
    db.commit()
    return told


def off(db: Session) -> int:
    """Closes the three places to users and shuts the stand-ins' accounts. Returns how many accounts were shut."""
    shut = 0
    for account in db.scalars(select(Account).where(Account.email.like("%@" + DOMAIN))):
        if account.status == APPROVED:
            account.status = SUSPENDED
            shut += 1
        if account.role == "md" and account.company_id:
            firm = db.get(Company, account.company_id)
            if firm is not None:
                firm.open_door = False
    db.commit()
    return shut


def main() -> None:
    init_db()
    with SessionLocal() as db:
        if sys.argv[1:] == ["off"]:
            print(f"The three places are closed to users and {off(db)} stand-in accounts are shut.")
            return
        password = secrets.token_urlsafe(9)
        rows = seed(db, password)
    place = ""
    for row in rows:
        if row["place"] != place:
            place = row["place"]
            print(f"\n{place}  ({row['kind']})")
        print(f"  {row['unique_id']:<12} {row['role']:<18} {row['name']}")
    print(f"\nPassword for all twelve: {password}")
    print("Each place is open to the people who use it: a user of the app finds it under its kind of place.")
    print("They are stand-ins for showing. `python -m app.demo off` closes the places and shuts the accounts.")


if __name__ == "__main__":
    main()
