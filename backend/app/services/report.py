"""Builds the inspection report PDF (ReportLab, no external services)."""
import io
import os
from datetime import datetime

from PIL import Image
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader, simpleSplit
from reportlab.pdfgen import canvas

from ..models import Inspection, Item
from . import storage

W, H = A4
M = 40  # page margin
INK = HexColor("#181B21")
YEL = HexColor("#FFC21A")
GREY = HexColor("#6B7280")
LINE = HexColor("#E1E4E8")
PANEL = HexColor("#F7F8FA")
SEV = {"High": HexColor("#DC2626"), "Medium": HexColor("#D97706"), "Low": HexColor("#059669")}
WHITE = HexColor("#FFFFFF")
TBC = "To be confirmed"


def _t(value) -> str:
    """Text safe for the built-in PDF fonts."""
    return str(value or "").encode("cp1252", "replace").decode("cp1252")


def _header(c: canvas.Canvas, title: str, insp: Inspection, page: int, pages: int) -> None:
    c.setFillColor(INK)
    c.rect(0, H - 70, W, 70, stroke=0, fill=1)
    c.setFillColor(YEL)
    c.rect(0, H - 75, W, 5, stroke=0, fill=1)
    c.setFillColor(WHITE)
    c.setFont("Helvetica-Bold", 19)
    c.drawString(M, H - 36, title)
    c.setFont("Helvetica", 10.5)
    c.setFillColor(HexColor("#C9CED6"))
    sub = " · ".join(p for p in (insp.site_name, insp.area) if p)
    c.drawString(M, H - 54, _t(sub))
    c.drawRightString(W - M, H - 40, f"Page {page} of {pages}")
    c.setFillColor(GREY)
    c.setFont("Helvetica-Oblique", 8)
    c.drawString(M, 24, "Generated locally by DeskLess. Nothing was sent to the internet.")


def _chip(c: canvas.Canvas, x: float, y: float, severity: str, w: float = 62, h: float = 16) -> None:
    c.setFillColor(SEV.get(severity, GREY))
    c.roundRect(x, y, w, h, h / 2, stroke=0, fill=1)
    c.setFillColor(WHITE)
    c.setFont("Helvetica-Bold", 8)
    c.drawCentredString(x + w / 2, y + 5, severity.upper())


def _photo(c: canvas.Canvas, item: Item, x: float, y: float, w: float, h: float) -> None:
    path = storage.photo_path(item.media_key)
    if item.has_photo and os.path.exists(path):
        img = Image.open(path).convert("RGB")
        target = w / h
        iw, ih = img.size
        if iw / ih > target:
            nw = int(ih * target)
            img = img.crop(((iw - nw) // 2, 0, (iw - nw) // 2 + nw, ih))
        else:
            nh = int(iw / target)
            img = img.crop((0, (ih - nh) // 2, iw, (ih - nh) // 2 + nh))
        img.thumbnail((900, 900))
        c.drawImage(ImageReader(img), x, y, w, h)
    else:
        c.setFillColor(HexColor("#E9EBEF"))
        c.rect(x, y, w, h, stroke=0, fill=1)
        c.setFillColor(GREY)
        c.setFont("Helvetica", 9)
        c.drawCentredString(x + w / 2, y + h / 2 - 3, "No photo")


CARD_H = 208


def _card(c: canvas.Canvas, item: Item, top: float) -> None:
    """Draws one item card whose top edge is at `top` (measured from the page bottom)."""
    x, w = M, W - 2 * M
    y = top - CARD_H
    c.setFillColor(PANEL)
    c.setStrokeColor(LINE)
    c.roundRect(x, y, w, CARD_H, 8, stroke=1, fill=1)
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 13)
    title = simpleSplit(_t(f"{item.seq}. {item.title or 'Untitled issue'}"), "Helvetica-Bold", 13, w - 28)[0]
    c.drawString(x + 14, top - 24, title)
    pw, ph = 200, 150
    _photo(c, item, x + 14, top - 36 - ph, pw, ph)
    fx = x + 14 + pw + 18
    fw = w - (pw + 46)
    fy = top - 48
    for label, value in (("LOCATION", item.location), ("OWNER", item.owner), ("DUE", item.due)):
        c.setFillColor(GREY)
        c.setFont("Helvetica-Bold", 7.5)
        c.drawString(fx, fy, label)
        c.setFillColor(INK if value else GREY)
        c.setFont("Helvetica" if value else "Helvetica-Oblique", 11)
        c.drawString(fx, fy - 14, simpleSplit(_t(value or TBC), "Helvetica", 11, fw)[0])
        fy -= 36
    _chip(c, fx, fy - 8, item.severity)
    note = item.transcript_en or item.note_text or item.transcript
    if note:
        c.setFillColor(GREY)
        c.setFont("Helvetica-Oblique", 8.5)
        lines = simpleSplit(_t(f"Note: {note}"), "Helvetica-Oblique", 8.5, w - 28)[:1]
        c.drawString(x + 14, y + 9, lines[0])


def _summary(c: canvas.Canvas, insp: Inspection, items: list[Item], top: float) -> float:
    c.setFillColor(GREY)
    c.setFont("Helvetica", 9.5)
    when = insp.created_at.strftime("%d %b %Y") if isinstance(insp.created_at, datetime) else ""
    meta = " · ".join(p for p in (f"Inspector: {insp.inspector}" if insp.inspector else "", f"Date: {when}") if p)
    c.drawString(M, top - 14, _t(meta))
    counts = [("Items", len(items), INK)] + [(s, sum(1 for i in items if i.severity == s), SEV[s]) for s in ("High", "Medium", "Low")]
    gap = 10
    tw = (W - 2 * M - gap * 3) / 4
    ty = top - 84
    for k, (label, n, col) in enumerate(counts):
        tx = M + k * (tw + gap)
        c.setFillColor(PANEL)
        c.setStrokeColor(LINE)
        c.roundRect(tx, ty, tw, 54, 6, stroke=1, fill=1)
        c.setFillColor(GREY)
        c.setFont("Helvetica", 9)
        c.drawString(tx + 12, ty + 36, label)
        c.setFillColor(col)
        c.setFont("Helvetica-Bold", 20)
        c.drawString(tx + 12, ty + 11, str(n))
    return ty - 14


def _actions_page(c: canvas.Canvas, items: list[Item], top: float) -> None:
    cols = [(M, "#"), (M + 26, "Action"), (M + 268, "Severity"), (M + 338, "Owner"), (M + 440, "Due")]
    c.setFillColor(HexColor("#EEF0F3"))
    c.rect(M, top - 22, W - 2 * M, 22, stroke=0, fill=1)
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 9)
    for x, label in cols:
        c.drawString(x + 6, top - 15, label)
    y = top - 22
    for item in items:
        title = simpleSplit(_t(item.title or "Untitled issue"), "Helvetica-Bold", 10, 228)[:2]
        loc = simpleSplit(_t(item.location or ""), "Helvetica", 8.5, 228)[:1]
        row_h = 16 + 12 * len(title) + (11 if loc else 0)
        c.setFillColor(INK)
        c.setFont("Helvetica-Bold", 10)
        c.drawString(M + 6, y - 17, str(item.seq))
        for k, ln in enumerate(title):
            c.drawString(M + 32, y - 17 - 12 * k, ln)
        if loc:
            c.setFillColor(GREY)
            c.setFont("Helvetica", 8.5)
            c.drawString(M + 32, y - 17 - 12 * len(title) + 1, loc[0])
        _chip(c, M + 274, y - 22, item.severity, w=56, h=14)
        c.setFont("Helvetica", 9.5)
        for x, value, width in ((M + 344, item.owner, 96), (M + 446, item.due, 66)):
            c.setFillColor(INK if value else GREY)
            c.drawString(x, y - 17, simpleSplit(_t(value or TBC), "Helvetica", 9.5, width)[0])
        y -= row_h
        c.setStrokeColor(LINE)
        c.line(M, y, W - M, y)


def build(insp: Inspection, items: list[Item]) -> bytes:
    first, rest = items[:2], items[2:]
    item_pages = [first] + [rest[i:i + 3] for i in range(0, len(rest), 3)]
    rows_per_page = 14
    action_pages = [items[i:i + rows_per_page] for i in range(0, max(len(items), 1), rows_per_page)]
    pages = len(item_pages) + len(action_pages)

    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=A4)
    c.setTitle(_t(f"Site inspection report - {insp.site_name}"))
    c.setAuthor("DeskLess")
    n = 0
    for k, chunk in enumerate(item_pages):
        n += 1
        _header(c, "SITE INSPECTION REPORT", insp, n, pages)
        top = H - 90
        if k == 0:
            top = _summary(c, insp, items, top)
            if not items:
                c.setFillColor(GREY)
                c.setFont("Helvetica-Oblique", 11)
                c.drawString(M, top - 20, "No issues were recorded in this walk-through.")
        for item in chunk:
            _card(c, item, top)
            top -= CARD_H + 12
        c.showPage()
    for chunk in action_pages:
        n += 1
        _header(c, "ACTION ITEMS", insp, n, pages)
        _actions_page(c, chunk, H - 96)
        c.showPage()
    c.save()
    return buf.getvalue()


def actions_text(insp: Inspection, items: list[Item]) -> str:
    """Plain-text action list for the clipboard (email, chat, tracker)."""
    head = " · ".join(p for p in (insp.site_name, insp.area) if p)
    lines = [f"Action items from the walk-through: {head}"]
    for item in items:
        parts = [item.title or "Untitled issue", item.severity, item.owner or "owner to be confirmed", item.due or "due date to be confirmed"]
        loc = f" ({item.location})" if item.location else ""
        lines.append(f"{item.seq}. {parts[0]}{loc} · {parts[1]} · {parts[2]} · {parts[3]}")
    return "\n".join(lines)
