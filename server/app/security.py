"""Passwords, sign-in tokens, one-time codes and the handling of Aadhaar numbers."""

import base64
import hashlib
import hmac
import secrets
from datetime import datetime, timedelta, timezone

import jwt
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .config import settings

# ---------------------------------------------------------------- passwords

_SCRYPT_N, _SCRYPT_R, _SCRYPT_P = 2**15, 8, 1


def _scrypt(password: str, salt: bytes, n: int, r: int, p: int) -> bytes:
    return hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p, maxmem=128 * 1024 * 1024, dklen=32)


def hash_password(password: str) -> str:
    """A slow, salted hash (scrypt). The password itself is never stored."""
    salt = secrets.token_bytes(16)
    made = _scrypt(password, salt, _SCRYPT_N, _SCRYPT_R, _SCRYPT_P)
    return "scrypt${}${}${}${}${}".format(
        _SCRYPT_N, _SCRYPT_R, _SCRYPT_P, base64.b64encode(salt).decode(), base64.b64encode(made).decode()
    )


def check_password(password: str, stored: str) -> bool:
    try:
        kind, n, r, p, salt, expected = stored.split("$")
        if kind != "scrypt":
            return False
        made = _scrypt(password, base64.b64decode(salt), int(n), int(r), int(p))
        return hmac.compare_digest(made, base64.b64decode(expected))
    except (ValueError, TypeError):
        return False


# ---------------------------------------------------------------- tokens


def make_token(subject: str, kind: str, lifetime: timedelta) -> str:
    """A signed token saying who holds it and what for ("user" or "admin"), good for [lifetime]."""
    now = datetime.now(timezone.utc)
    return jwt.encode(
        {"sub": subject, "kind": kind, "iat": now, "exp": now + lifetime}, settings().secret_key, algorithm="HS256"
    )


def read_token(token: str, kind: str) -> str | None:
    """Who the token is for, or None if it is forged, expired or of another kind."""
    try:
        claims = jwt.decode(token, settings().secret_key, algorithms=["HS256"])
    except jwt.PyJWTError:
        return None
    return claims.get("sub") if claims.get("kind") == kind else None


# ---------------------------------------------------------------- one-time codes and tickets


def new_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def keyed_hash(*parts: str) -> str:
    """A hash only this server can make, for things that must be checked later but not read back."""
    return hmac.new(settings().secret_key.encode(), ":".join(parts).encode(), hashlib.sha256).hexdigest()


def same(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode(), b.encode())


def carry_key(account) -> str:
    """
    The secret a user's phone signs with when it has no network and hands what was written to a phone
    nearby, which posts it for them. Only that phone and this server know it. Nothing is stored for it:
    it is worked out from the account and its password hash, so a change of password changes it too.
    """
    return keyed_hash("carry", account.id, account.password_hash)


# ---------------------------------------------------------------- codes to sign up with

# Letters and digits that are not mistaken for one another when read aloud or copied by hand.
_INVITE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
INVITE_LENGTH = 8


def new_invite() -> str:
    """The code a managing director or manager hands to the people who are to answer to them."""
    return "".join(secrets.choice(_INVITE_LETTERS) for _ in range(INVITE_LENGTH))


def clean_invite(typed: str) -> str | None:
    """A code as it is stored, however it was typed (small letters, spaces, a dash), or None."""
    code = "".join(letter for letter in typed.upper() if letter.isalnum())
    return code if len(code) == INVITE_LENGTH and all(letter in _INVITE_LETTERS for letter in code) else None


def shown_invite(code: str | None) -> str:
    """A code as it is shown: in two halves, which are easier to read out."""
    return f"{code[:4]}-{code[4:]}" if code else ""


# ---------------------------------------------------------------- Aadhaar

# The Verhoeff checksum, which every Aadhaar number ends with. It catches any single wrong digit and any
# two neighbouring digits swapped.
_D = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
    [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
    [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
    [4, 0, 1, 2, 3, 9, 5, 6, 7, 8],
    [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2],
    [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
    [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
    [9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
]
_P = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
    [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
    [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
    [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
]
_INV = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9]


def verhoeff_ok(digits: str) -> bool:
    check = 0
    for i, digit in enumerate(reversed(digits)):
        check = _D[check][_P[i % 8][int(digit)]]
    return check == 0


def verhoeff_digit(digits: str) -> str:
    """The digit that makes [digits] pass the check when added at the end."""
    check = 0
    for i, digit in enumerate(reversed(digits)):
        check = _D[check][_P[(i + 1) % 8][int(digit)]]
    return str(_INV[check])


def clean_aadhaar(typed: str) -> str | None:
    """The 12 digits of an Aadhaar number typed with or without spaces, or None if it cannot be one."""
    digits = typed.replace(" ", "").replace("-", "")
    if len(digits) != 12 or not digits.isdigit() or digits[0] in "01" or not verhoeff_ok(digits):
        return None
    return digits


def seal_aadhaar(digits: str) -> bytes:
    """Encrypts the number (AES-256-GCM). What is stored is the nonce followed by the sealed number."""
    nonce = secrets.token_bytes(12)
    return nonce + AESGCM(settings().aadhaar_key).encrypt(nonce, digits.encode(), b"aadhaar")


def open_aadhaar(sealed: bytes) -> str:
    """For a lawful need only. No part of the API calls this."""
    return AESGCM(settings().aadhaar_key).decrypt(sealed[:12], sealed[12:], b"aadhaar").decode()


def aadhaar_fingerprint(digits: str) -> str:
    """The same number always gives the same fingerprint, and the number cannot be worked back from it."""
    return hmac.new(settings().aadhaar_key, b"fingerprint:" + digits.encode(), hashlib.sha256).hexdigest()


def masked(last4: str) -> str:
    return f"XXXX XXXX {last4}"
