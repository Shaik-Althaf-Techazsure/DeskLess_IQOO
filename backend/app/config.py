"""Runtime settings, read from environment variables (see .env.example)."""
from functools import lru_cache

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "sqlite:///./deskless.db"
    media_dir: str = "./media"

    # Speech-to-text (faster-whisper, runs locally on CPU)
    whisper_enabled: bool = True
    whisper_model: str = "small"

    # Optional local LLM served by Ollama. Leave OLLAMA_URL empty to disable.
    ollama_url: str = ""
    llm_model: str = "gemma3:4b"
    llm_timeout_s: float = 120.0

    max_photo_mb: int = 20
    max_audio_mb: int = 30


@lru_cache
def get_settings() -> Settings:
    return Settings()
