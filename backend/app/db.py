"""Database engine and session handling."""
import logging
import time

from sqlalchemy import create_engine, text
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from .config import get_settings

log = logging.getLogger("deskless.db")


class Base(DeclarativeBase):
    pass


_settings = get_settings()
_connect_args = {"check_same_thread": False} if _settings.database_url.startswith("sqlite") else {}
engine = create_engine(_settings.database_url, pool_pre_ping=True, connect_args=_connect_args)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def init_db(retries: int = 30, delay: float = 2.0) -> None:
    """Create tables, waiting for the database to accept connections first."""
    from . import models  # noqa: F401  (registers the tables)

    for attempt in range(1, retries + 1):
        try:
            with engine.connect() as conn:
                conn.execute(text("SELECT 1"))
            break
        except Exception as exc:  # database still starting
            if attempt == retries:
                raise
            log.warning("database not ready (%s), retry %d/%d", exc.__class__.__name__, attempt, retries)
            time.sleep(delay)
    Base.metadata.create_all(engine)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
