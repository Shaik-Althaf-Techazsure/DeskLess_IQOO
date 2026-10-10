"""Keeping a picture that someone sent."""

import io
from pathlib import Path

from fastapi import HTTPException
from PIL import Image, ImageOps, UnidentifiedImageError


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
