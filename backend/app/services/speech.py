"""Local speech-to-text with faster-whisper (open-source Whisper weights, CPU, no network)."""
import logging
import threading
from dataclasses import dataclass

from ..config import get_settings

log = logging.getLogger("deskless.speech")
_lock = threading.Lock()
_model = None
_load_error: str | None = None
# Nudges Whisper to write Hindi in Devanagari rather than Urdu script.
HINDI_PROMPT = "यह साइट निरीक्षण का वॉइस नोट है।"


@dataclass
class Transcript:
    text: str            # as spoken, in the detected language
    language: str
    english: str | None  # English rendering (same as text when the speech was English)


def load_audio(path: str, rate: int = 16000):
    """Decodes any container/codec the browser or phone produced into mono float32 at 16 kHz."""
    import av
    import numpy as np

    resampler = av.AudioResampler(format="s16", layout="mono", rate=rate)
    chunks = []
    with av.open(path) as container:
        stream = next((s for s in container.streams if s.type == "audio"), None)
        if stream is None:
            raise ValueError("the recording has no audio track")
        for frame in container.decode(stream):
            chunks.extend(out.to_ndarray().reshape(-1) for out in resampler.resample(frame))
        chunks.extend(out.to_ndarray().reshape(-1) for out in resampler.resample(None))
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(chunks).astype(np.float32) / 32768.0


def _get_model():
    global _model, _load_error
    if _model is None and _load_error is None:
        with _lock:
            if _model is None and _load_error is None:
                try:
                    from faster_whisper import WhisperModel

                    name = get_settings().whisper_model
                    _model = WhisperModel(name, device="cpu", compute_type="int8")
                    log.info("speech model '%s' loaded", name)
                except Exception as exc:  # missing weights, unsupported CPU, ...
                    _load_error = f"{exc.__class__.__name__}: {exc}"
                    log.error("speech model failed to load: %s", _load_error)
    return _model


def status() -> dict:
    s = get_settings()
    return {
        "enabled": s.whisper_enabled,
        "model": s.whisper_model,
        "loaded": _model is not None,
        "error": _load_error,
    }


def warm_up() -> None:
    if get_settings().whisper_enabled:
        _get_model()


def transcribe(path: str) -> Transcript:
    if not get_settings().whisper_enabled:
        raise RuntimeError("speech-to-text is disabled")
    model = _get_model()
    if model is None:
        raise RuntimeError(f"speech model unavailable ({_load_error})")
    audio = load_audio(path)
    if audio.size < 1600:  # under a tenth of a second
        return Transcript(text="", language="unknown", english=None)
    with _lock:  # one transcription at a time keeps memory and CPU use predictable
        language, _, _ = model.detect_language(audio)
        if language == "ur":
            language = "hi"  # spoken Hindi and Urdu are close; write Hindi notes in Devanagari
        prompt = HINDI_PROMPT if language == "hi" else None
        segments, _ = model.transcribe(audio, task="transcribe", language=language, initial_prompt=prompt, beam_size=5, vad_filter=True)
        text = " ".join(s.text.strip() for s in segments).strip()
        english: str | None = text
        if text and language != "en":
            segments, _ = model.transcribe(audio, task="translate", language=language, beam_size=5, vad_filter=True)
            english = " ".join(s.text.strip() for s in segments).strip() or None
    return Transcript(text=text, language=language or "unknown", english=english)
