"""A ready-made walk-through so the app can be tried without a site: four photos with their spoken notes."""
import os
import shutil

from sqlalchemy.orm import Session

from ..models import Inspection, Item
from . import pipeline, storage

ASSETS = os.path.join(os.path.dirname(os.path.dirname(__file__)), "assets", "demo")

# (photo, what was said, English rendering)
NOTES = [
    ("issue1.jpg", "Yahan panel DB-3 ki wiring loose hai. Electrician ko kal tak bulana hai.",
     "The wiring at panel DB-3 is loose here. The electrician has to be called by tomorrow."),
    ("issue2.jpg", "There is a crack on column C4. The structural engineer should check it this week.", None),
    ("issue3.jpg", "North wall par paani ka seepage hai, ramp ke paas. Waterproofing contractor is hafte theek kare.",
     "There is water seepage on the north wall near the ramp. The waterproofing contractor should fix it this week."),
    ("issue4.jpg", "Fire extinguisher is missing at Exit 2. The safety officer must replace it today.", None),
]


def seed(db: Session) -> Inspection:
    insp = Inspection(site_name="Tower B", area="Basement B1", inspector="Demo inspector")
    db.add(insp)
    db.flush()
    for seq, (photo, said, english) in enumerate(NOTES, start=1):
        key = storage.new_key()
        with open(os.path.join(ASSETS, photo), "rb") as fh:
            storage.save_photo(key, fh.read())
        item = Item(inspection_id=insp.id, seq=seq, media_key=key, has_photo=True, note_text=said,
                    transcript=said, transcript_en=english, language="hi" if english else "en")
        pipeline.process(item, insp)
        db.add(item)
    db.commit()
    db.refresh(insp)
    return insp


def cleanup_media(keys: list[str]) -> None:
    for key in keys:
        shutil.rmtree(os.path.join(storage.get_settings().media_dir, key), ignore_errors=True)
