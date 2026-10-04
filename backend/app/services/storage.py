"""Media files on disk: <MEDIA_DIR>/<key>/photo.jpg, thumb.jpg, audio.<ext>."""
import io
import os
import shutil
import uuid

from PIL import Image, ImageOps, UnidentifiedImageError

from ..config import get_settings

AUDIO_EXT = {
    "audio/webm": "webm", "video/webm": "webm", "audio/ogg": "ogg", "audio/mp4": "m4a", "audio/x-m4a": "m4a",
    "audio/m4a": "m4a", "video/mp4": "m4a", "audio/mpeg": "mp3", "audio/mp3": "mp3", "audio/wav": "wav",
    "audio/x-wav": "wav", "audio/wave": "wav", "audio/aac": "aac", "audio/3gpp": "3gp", "audio/amr": "amr",
}


class MediaError(ValueError):
    pass


def new_key() -> str:
    return uuid.uuid4().hex


def folder(key: str) -> str:
    path = os.path.join(get_settings().media_dir, key)
    os.makedirs(path, exist_ok=True)
    return path


def photo_path(key: str) -> str:
    return os.path.join(get_settings().media_dir, key, "photo.jpg")


def save_photo(key: str, data: bytes) -> None:
    s = get_settings()
    if len(data) > s.max_photo_mb * 1024 * 1024:
        raise MediaError(f"photo is larger than {s.max_photo_mb} MB")
    try:
        img = Image.open(io.BytesIO(data))
        img = ImageOps.exif_transpose(img).convert("RGB")
    except (UnidentifiedImageError, OSError) as exc:
        raise MediaError("the photo could not be read as an image") from exc
    img.thumbnail((1920, 1920))
    d = folder(key)
    img.save(os.path.join(d, "photo.jpg"), quality=86, optimize=True)
    img.thumbnail((640, 640))
    img.save(os.path.join(d, "thumb.jpg"), quality=80, optimize=True)


def save_audio(key: str, data: bytes, content_type: str | None, filename: str | None) -> str:
    s = get_settings()
    if len(data) > s.max_audio_mb * 1024 * 1024:
        raise MediaError(f"audio is larger than {s.max_audio_mb} MB")
    ctype = (content_type or "").split(";")[0].strip().lower()
    ext = AUDIO_EXT.get(ctype)
    if ext is None and filename and "." in filename:
        guess = filename.rsplit(".", 1)[1].lower()
        ext = guess if guess in set(AUDIO_EXT.values()) else None
    if ext is None:
        raise MediaError("unsupported audio format")
    name = f"audio.{ext}"
    with open(os.path.join(folder(key), name), "wb") as fh:
        fh.write(data)
    return name


def remove(key: str) -> None:
    shutil.rmtree(os.path.join(get_settings().media_dir, key), ignore_errors=True)
