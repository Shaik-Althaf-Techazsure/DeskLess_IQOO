"""Settings, read from the environment. Every one has a default that works on a developer's machine."""

import base64
import json
import os
import secrets
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Settings:
    database_url: str
    #: Where photos, reports and generated secrets are kept.
    data_dir: Path
    #: Signs sign-in tokens and one-time codes.
    secret_key: str
    #: 32 bytes. Aadhaar numbers are encrypted with it before they are stored.
    aadhaar_key: bytes
    admin_email: str
    admin_password: str
    smtp_host: str
    smtp_port: int
    smtp_user: str
    smtp_password: str
    #: "none", "starttls" or "ssl".
    smtp_security: str
    mail_from: str
    #: How long a one-time code stays good, and a sign-in on a phone.
    otp_minutes: int = 10
    token_days: int = 30
    #: How far the company's clock is from UTC, in minutes. A deadline is overdue by that clock.
    utc_offset_minutes: int = 330
    #: Where people reach this server from outside, e.g. "https://example.com/competitions/IQOO".
    #: Empty on a developer's machine, where it sits at the root of its own port.
    public_url: str = ""
    #: Where the writing model answers, e.g. "http://ai:8080": an open-source model run on this machine,
    #: spoken to in the way llama.cpp and Ollama both understand. Empty, or nothing answering there,
    #: and the assistant words things itself from the fields each issue came with.
    ai_url: str = ""
    #: The model's name where the runner serves several (Ollama); llama.cpp serves one and ignores it.
    ai_model: str = ""
    #: The assistant chasing work by itself. How often it looks (0 switches it off), after how many
    #: minutes an urgent issue nobody has taken is brought to its manager and then to the managing
    #: director, and after how many hours a repair nobody has checked is brought up.
    autopilot_seconds: int = 60
    untaken_minutes: int = 30
    escalate_minutes: int = 120
    unchecked_hours: int = 24

    @property
    def base_path(self) -> str:
        """The path the server sits under on its domain, e.g. "/competitions/IQOO"; empty at the root."""
        return urlsplit(self.public_url).path.rstrip("/")

    @property
    def dashboard_url(self) -> str:
        """The address of the dashboards, for emails; empty when the public address is not known."""
        return f"{self.public_url}/dashboard" if self.public_url else ""

    @property
    def media_dir(self) -> Path:
        return self.data_dir / "photos"

    @property
    def reports_dir(self) -> Path:
        """The PDFs the phones sent."""
        return self.data_dir / "reports"

    @property
    def issues_dir(self) -> Path:
        """The picture of each issue, and of its repair."""
        return self.data_dir / "issues"

    @property
    def small_dir(self) -> Path:
        """Little copies of pictures, for lists. They can be thrown away: each is made again when asked for."""
        return self.data_dir / "small"


def _generated(data_dir: Path) -> dict:
    """
    Secrets that were not given in the environment are made once and kept in the data folder, so the
    server starts with one command on any machine and keeps the same keys from then on. On a real server
    they should be set in the environment instead.
    """
    path = data_dir / "secrets.json"
    if path.exists():
        return json.loads(path.read_text())
    made = {
        "secret_key": secrets.token_urlsafe(48),
        "aadhaar_key": base64.b64encode(secrets.token_bytes(32)).decode(),
        "admin_password": secrets.token_urlsafe(12),
    }
    data_dir.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(made))
    path.chmod(0o600)
    return made


@lru_cache
def settings() -> Settings:
    env = os.environ
    data_dir = Path(env.get("DATA_DIR", "./data")).resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    given = {
        "secret_key": env.get("SECRET_KEY", ""),
        "aadhaar_key": env.get("AADHAAR_KEY", ""),
        "admin_password": env.get("ADMIN_PASSWORD", ""),
    }
    made = _generated(data_dir) if not all(given.values()) else {}
    admin_password = given["admin_password"] or made["admin_password"]
    if not given["admin_password"]:
        # The admin reads the generated password from this file; it is never written to the log.
        note = data_dir / "admin-password.txt"
        note.write_text(admin_password + "\n")
        note.chmod(0o600)
    aadhaar_key = base64.b64decode(given["aadhaar_key"] or made["aadhaar_key"])
    if len(aadhaar_key) != 32:
        raise RuntimeError("AADHAAR_KEY must be 32 bytes, base64-encoded.")
    return Settings(
        database_url=env.get("DATABASE_URL", f"sqlite:///{data_dir / 'accounts.db'}"),
        data_dir=data_dir,
        secret_key=given["secret_key"] or made["secret_key"],
        aadhaar_key=aadhaar_key,
        admin_email=env.get("ADMIN_EMAIL", "admin@deskless.local").strip().lower(),
        admin_password=admin_password,
        smtp_host=env.get("SMTP_HOST", ""),
        smtp_port=int(env.get("SMTP_PORT", "25")),
        smtp_user=env.get("SMTP_USER", ""),
        smtp_password=env.get("SMTP_PASSWORD", ""),
        smtp_security=env.get("SMTP_SECURITY", "none").lower(),
        mail_from=env.get("MAIL_FROM", "DeskLess <no-reply@deskless.local>"),
        otp_minutes=int(env.get("OTP_MINUTES", "10")),
        token_days=int(env.get("TOKEN_DAYS", "30")),
        utc_offset_minutes=int(env.get("UTC_OFFSET_MINUTES", "330")),
        public_url=env.get("PUBLIC_URL", "").strip().rstrip("/"),
        ai_url=env.get("AI_URL", "").strip().rstrip("/"),
        ai_model=env.get("AI_MODEL_NAME", "").strip(),
        autopilot_seconds=int(env.get("AUTOPILOT_EVERY_SECONDS", "60")),
        untaken_minutes=int(env.get("AUTOPILOT_UNTAKEN_MINUTES", "30")),
        escalate_minutes=int(env.get("AUTOPILOT_ESCALATE_MINUTES", "120")),
        unchecked_hours=int(env.get("AUTOPILOT_UNCHECKED_HOURS", "24")),
    )
