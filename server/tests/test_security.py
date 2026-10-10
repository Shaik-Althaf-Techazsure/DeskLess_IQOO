"""The pieces the account flow stands on: the Aadhaar check digit, password hashes and tokens."""

from datetime import timedelta

from app import security


def test_the_check_digit():
    # The worked example from Verhoeff's scheme: 236 takes the check digit 3.
    assert security.verhoeff_digit("236") == "3"
    assert security.verhoeff_ok("2363")
    assert not security.verhoeff_ok("2364")
    # Every single wrong digit is caught.
    good = "9999" + "0000001" + security.verhoeff_digit("99990000001")
    for place in range(12):
        for digit in "0123456789":
            changed = good[:place] + digit + good[place + 1 :]
            assert security.verhoeff_ok(changed) == (changed == good)


def test_an_aadhaar_number_as_typed():
    base = "23456789012"
    good = base + security.verhoeff_digit(base)
    assert security.clean_aadhaar(f"{good[:4]} {good[4:8]} {good[8:]}") == good
    assert security.clean_aadhaar(good[:-1]) is None
    assert security.clean_aadhaar("1" + good[1:]) is None  # no Aadhaar number starts with 0 or 1
    assert security.clean_aadhaar("abcd efgh ijkl") is None
    assert security.masked(good[-4:]) == f"XXXX XXXX {good[-4:]}"


def test_sealing_an_aadhaar_number():
    number = "999900000019"
    sealed = security.seal_aadhaar(number)
    assert number.encode() not in sealed
    assert security.open_aadhaar(sealed) == number
    # Sealed twice it looks different each time, yet its fingerprint is the same: that is what lets the
    # server notice a number registered twice without being able to read any of them.
    assert security.seal_aadhaar(number) != sealed
    assert security.aadhaar_fingerprint(number) == security.aadhaar_fingerprint(number)
    assert security.aadhaar_fingerprint(number) != security.aadhaar_fingerprint("999900000027")


def test_passwords():
    stored = security.hash_password("correct horse battery")
    assert "correct horse battery" not in stored
    assert security.check_password("correct horse battery", stored)
    assert not security.check_password("Correct horse battery", stored)
    assert not security.check_password("anything", "not a stored password")
    assert security.hash_password("correct horse battery") != stored  # a new salt every time


def test_tokens():
    token = security.make_token("account-1", "user", timedelta(minutes=5))
    assert security.read_token(token, "user") == "account-1"
    assert security.read_token(token, "admin") is None
    assert security.read_token(token + "x", "user") is None
    assert security.read_token(security.make_token("account-1", "user", timedelta(seconds=-1)), "user") is None
