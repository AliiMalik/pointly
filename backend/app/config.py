"""Backend settings from environment variables or `.env` (SPEC 6.9)."""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BACKEND_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    gemini_api_key: str = ""
    llm_model: str = ""
    stt_model: str = ""
    google_application_credentials: str = ""
    tts_model: str = ""
    rehnuma_app_key: str = "change-me"
    log_dir: Path = BACKEND_DIR / "logs"
    log_screens: bool = False
    tts_cache_dir: Path = BACKEND_DIR / "tts_cache"


@lru_cache
def get_settings() -> Settings:
    return Settings()
