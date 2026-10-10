"""Keeping a picture that someone sent."""

import io
from pathlib import Path

from fastapi import HTTPException
from fastapi.responses import FileResponse
from PIL import Image, ImageOps, UnidentifiedImageError

#: The longer side of the little copy a list shows.
SMALL_EDGE = 256


def save_photo(raw: bytes, folder: Path, name: str, edge: int) -> str:
    """
    Stores the picture as a plain JPEG no longer than [edge] on its longer side. Re-drawing it throws away
    anything else the file carried, such as where and when it was taken.
    """
    try:
        picture = Image.open(io.BytesIO(raw))
        picture.load()
    except (UnidentifiedImageError, OSError):
        raise HTTPException(422, "That file is not a photo. Use a JPEG or PNG picture.")
    # A phone often stores the picture on its side with a note saying which way is up; turn it for good.
    picture = ImageOps.exif_transpose(picture).convert("RGB")
    picture.thumbnail((edge, edge))
    folder.mkdir(parents=True, exist_ok=True)
    picture.save(folder / name, "JPEG", quality=85)
    return name


def picture_response(path: Path, size: str, small_dir: Path) -> FileResponse:
    """
    A stored picture as it was asked for. For a list, size "small" gives a little copy, made the first
    time it is asked for and kept beside the others: a row of thumbnails then weighs a few kilobytes each
    instead of a full photograph each. The browser may keep a little copy for a few minutes.
    """
    if not path.is_file():
        raise HTTPException(404, "No picture.")
    keep = {"Cache-Control": "private, max-age=300"}
    if size != "small":
        # The picture itself is always fetched afresh: a repair photographed again must not be judged
        # from the copy of the first attempt a browser happened to have kept.
        return FileResponse(path, media_type="image/jpeg", headers={"Cache-Control": "private, no-cache"})
    # Named after the picture and when it was written, so a picture taken again gets a new little copy.
    little = small_dir / f"{path.stem}-{int(path.stat().st_mtime)}.jpg"
    if not little.is_file():
        try:
            small_dir.mkdir(parents=True, exist_ok=True)
            with Image.open(path) as picture:
                picture = picture.convert("RGB")
                picture.thumbnail((SMALL_EDGE, SMALL_EDGE))
                picture.save(little, "JPEG", quality=80)
        except OSError:
            return FileResponse(path, media_type="image/jpeg", headers=keep)
    return FileResponse(little, media_type="image/jpeg", headers=keep)
