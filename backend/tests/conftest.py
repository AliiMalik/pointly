import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

TEST_KEY = "test-key"


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        _env_file=None,
        gemini_api_key="",
        llm_model="test-model",
        stt_model="test-model",
        rehnuma_app_key=TEST_KEY,
        log_dir=tmp_path / "logs",
        tts_cache_dir=tmp_path / "tts_cache",
    )


@pytest.fixture
def client(settings) -> TestClient:
    return TestClient(create_app(settings))


@pytest.fixture
def auth() -> dict:
    return {"X-Rehnuma-Key": TEST_KEY}
