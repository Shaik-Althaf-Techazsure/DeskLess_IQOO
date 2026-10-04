from datetime import date

from app.services import rules

TODAY = date(2026, 10, 5)  # a Monday


def test_loose_wiring_note():
    r = rules.structure("The wiring at panel DB-3 is loose here. The electrician has to be called by tomorrow.", "Basement B1", TODAY)
    assert r.title == "Wiring at panel DB-3 is loose"
    assert r.severity == "High"
    assert r.owner == "Electrician"
    assert (r.due, r.due_date) == ("Tomorrow", date(2026, 10, 6))
    assert r.location == "Basement B1 · Panel DB-3"
    assert r.needs_review == []


def test_crack_note():
    r = rules.structure("There is a crack on column C4. The structural engineer should check it this week.", "Basement B1", TODAY)
    assert r.title == "Crack on column C4"
    assert r.severity == "Medium"
    assert r.owner == "Structural engineer"
    assert (r.due, r.due_date) == ("This week", date(2026, 10, 9))
    assert r.location == "Basement B1 · Column C4"


def test_seepage_note():
    r = rules.structure("There is water seepage on the north wall near the ramp. The waterproofing contractor should fix it this week.", "", TODAY)
    assert r.severity == "Medium"
    assert r.owner == "Waterproofing contractor"
    assert r.location == "North wall · near the ramp"


def test_missing_extinguisher_note():
    r = rules.structure("Fire extinguisher is missing at Exit 2. The safety officer must replace it today.", "Basement B1", TODAY)
    assert r.title == "Fire extinguisher is missing at Exit 2"
    assert r.severity == "High"
    assert r.owner == "Safety officer"
    assert r.due == "Today"
    assert r.location == "Basement B1 · Exit 2"


def test_hinglish_keywords():
    r = rules.structure("Yahan panel DB-3 ki wiring loose hai. Electrician ko kal tak bulana hai.", "Basement B1", TODAY)
    assert r.owner == "Electrician"
    assert r.due == "Tomorrow"
    assert "Panel DB-3" in r.location


def test_nothing_stated_is_left_for_review():
    r = rules.structure("Paint is peeling on the lobby ceiling.", "", TODAY)
    assert r.severity == "Low"
    assert r.owner is None and r.due is None and r.location is None
    assert r.needs_review == ["location", "owner", "due"]


def test_due_phrases():
    assert rules.parse_due("fix by Friday", TODAY) == ("By Friday", date(2026, 10, 9))
    assert rules.parse_due("within 3 days", TODAY) == ("In 3 days", date(2026, 10, 8))
    assert rules.parse_due("day after tomorrow", TODAY) == ("In 2 days", date(2026, 10, 7))
    assert rules.parse_due("do it immediately", TODAY) == ("Today", TODAY)
    assert rules.parse_due("no deadline mentioned", TODAY) == (None, None)


def test_empty_note():
    r = rules.structure("", "Roof", TODAY)
    assert r.title == "Untitled issue"
    assert "title" in r.needs_review


def test_title_drops_filler_words():
    assert rules.make_title("Here the wiring of the panel is loose. Call the electrician.") == "Wiring of the panel is loose"
