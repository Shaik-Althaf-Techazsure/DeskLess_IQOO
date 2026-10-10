"""The database: PostgreSQL in Docker, SQLite when run or tested without it."""

from collections.abc import Iterator

from sqlalchemy import Connection, MetaData, create_engine, inspect, text
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import settings

_url = settings().database_url
_postgres = _url.startswith("postgresql")

# In PostgreSQL the account tables live in a schema of their own, apart from anything else in the database.
SCHEMA = "accounts" if _postgres else None


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA)


engine = create_engine(_url, pool_pre_ping=True, connect_args={} if _postgres else {"check_same_thread": False})
SessionLocal = sessionmaker(engine, expire_on_commit=False)


def _add_new_columns(connection: Connection) -> None:
    """
    A database made by an earlier version gets the columns added since, so updating the server keeps
    everyone's accounts. Columns are only ever added, never changed or dropped.
    """
    existing = inspect(connection)
    for table in Base.metadata.sorted_tables:
        have = {column["name"] for column in existing.get_columns(table.name, schema=table.schema)}
        for column in table.columns:
            if column.name in have:
                continue
            kind = column.type.compile(connection.dialect)
            connection.execute(text(f"ALTER TABLE {table.fullname} ADD COLUMN {column.name} {kind}"))
            if column.unique or column.index:
                unique = "UNIQUE " if column.unique else ""
                connection.execute(
                    text(f"CREATE {unique}INDEX IF NOT EXISTS ix_{table.name}_{column.name} ON {table.fullname} ({column.name})")
                )


def init_db() -> None:
    from . import models  # noqa: F401  (the tables register themselves on import)
    from .upgrade import bring_forward

    with engine.begin() as connection:
        if SCHEMA:
            connection.execute(text(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}"))
        Base.metadata.create_all(connection)
        _add_new_columns(connection)
    with SessionLocal() as session:
        bring_forward(session)


def get_db() -> Iterator[Session]:
    with SessionLocal() as session:
        yield session
