"""
What the home page is filled with. Some of what it shows is finished later than the page itself: the
pitch deck, the business model canvas, the film. Each has its place on the page and a name in
`static/site/`; the part shows when its file is there and is left out when it is not, so a new deck or
a finished canvas is put on the page by putting the file in the folder.

  deck-01.webp, deck-02.webp, ...   the deck, slide by slide (.png and .jpg do as well);
                                    deck-01-s.webp and so on, when present, are the small copies
  any .pdf with "deck" in its name  the deck to download; with "concept" in its name it is introduced
                                    as the concept deck written before the build
  canvas.webp (or .png, .jpg)       the business model canvas; canvas-2.webp and so on for more sheets
  any .pdf with "canvas" in its name  the canvas to download
  film.mp4 with film-poster.jpg     the film
"""

import re
from html import escape
from pathlib import Path

SITE = Path(__file__).parent / "static" / "site"
_PICTURES = (".webp", ".png", ".jpg")
_SLIDE = re.compile(r"^deck-(\d+)\.(?:webp|png|jpg)$")
_SHEET = re.compile(r"^canvas(?:-(\d+))?\.(?:webp|png|jpg)$")
_BLOCK = re.compile(r"<!--if ([\w-]+)-->(.*?)<!--end \1-->", re.S)


def _numbered(names, pattern) -> list[str]:
    """The files of one numbered set, in the order of their numbers."""
    found = [(int(match.group(1) or 1), name) for name in names if (match := pattern.match(name))]
    return [name for _, name in sorted(found)]


def _small(name: str, names) -> str:
    """The small copy of a slide where there is one, else the slide itself."""
    stem = name.rsplit(".", 1)[0]
    return next((stem + "-s" + kind for kind in _PICTURES if stem + "-s" + kind in names), name)


def _pdf(names, word: str) -> str:
    return next((name for name in sorted(names) if name.lower().endswith(".pdf") and word in name.lower()), "")


def papers() -> dict:
    """The deck and the canvas to download, by the names they have in the folder now; empty where there is none."""
    names = {file.name for file in SITE.iterdir()} if SITE.is_dir() else set()
    return {"deck": _pdf(names, "deck"), "canvas": _pdf(names, "canvas")}


def held(names) -> dict:
    """Which of the page's later parts are there, and the words that go into their places."""
    slides = _numbered(names, _SLIDE)
    sheets = _numbered(names, _SHEET)
    deck_pdf, canvas_pdf = _pdf(names, "deck"), _pdf(names, "canvas")
    concept = bool(slides) and "concept" in deck_pdf.lower()
    first = ' aria-current="true"'
    thumbs = "\n".join(
        f'        <button{first if n == 0 else ""} data-full="static/site/{escape(name)}">'
        f'<img loading="lazy" src="static/site/{escape(_small(name, names))}" alt="Slide {n + 1}" width="320" height="180"></button>'
        for n, name in enumerate(slides)
    )
    more = "\n".join(
        f'      <button class="canvas-sheet shot" data-zoom="static/site/{escape(name)}">'
        f'<img loading="lazy" src="static/site/{escape(name)}" alt="Business model canvas, sheet {n + 2}"></button>'
        for n, name in enumerate(sheets[1:])
    )
    return {
        "deck": bool(slides),
        "deck-pdf": bool(slides and deck_pdf),
        "concept": concept,
        "pitch": bool(slides) and not concept,
        "canvas": bool(sheets),
        "canvas-pdf": bool(sheets and canvas_pdf),
        "film": "film.mp4" in names and "film-poster.jpg" in names,
        "words": {
            "deck-first": slides[0] if slides else "",
            "deck-count": str(len(slides)),
            "deck-thumbs": thumbs,
            "deck-pdf": deck_pdf,
            "canvas": sheets[0] if sheets else "",
            "canvas-more": more,
            "canvas-pdf": canvas_pdf,
        },
    }


def fill(page: str, names) -> str:
    """The home page with the parts whose files are missing taken out, and the others given their files."""
    has = held(names)
    # A part inside a part: the outer one is settled first, then what is left of it.
    for _ in range(4):
        settled = _BLOCK.sub(lambda part: part.group(2) if has.get(part.group(1)) else "", page)
        if settled == page:
            break
        page = settled
    for key, words in has["words"].items():
        page = page.replace("{{" + key + "}}", words)
    return page
