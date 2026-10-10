"""Brings accounts made by an earlier version of the server into line with this one. Safe to run at every start."""

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from .models import APPROVED, LEADS, Account, Company
from .service import fresh_invite


def bring_forward(db: Session) -> None:
    # The first version had two kinds of account. An employer is what is now a managing director.
    db.execute(update(Account).where(Account.role == "employer").values(role="md"))
    for account in db.scalars(select(Account).where(Account.status == APPROVED, Account.role.in_(LEADS))):
        if account.role == "md" and account.company_id is None:
            company = Company(name=account.organisation or f"{account.name}'s company")
            db.add(company)
            db.flush()
            account.company_id = company.id
        if not account.invite_code:
            account.invite_code = fresh_invite(db)
    db.commit()
