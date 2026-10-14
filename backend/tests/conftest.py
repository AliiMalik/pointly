import copy

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.llm.base import LLMError, Turn
from app.main import create_app

TEST_KEY = "test-key"

EXAMPLE_REQUEST = {
    "session_id": "3f1c-test",
    "goal_text": "Beti Sara ko WhatsApp pe photo bhejni hai",
    "step_index": 2,
    "history": [
        {"step": 1, "instruction": "Tap the green WhatsApp icon", "target_label": "WhatsApp", "outcome": "done"}
    ],
    "screen": {
        "package": "com.whatsapp",
        "window_title": None,
        "width": 1080,
        "height": 2400,
        "elements": [
            {"id": 1, "text": "Chats", "cls": "TextView", "clickable": True, "bounds": [0, 300, 360, 400]},
            {"id": 2, "text": "Sara", "label": "Sara · Kal milte hain", "cls": "View", "clickable": True, "bounds": [0, 420, 1080, 560]},
            {"id": 3, "desc": "New chat", "cls": "ImageButton", "clickable": True, "bounds": [900, 2100, 1040, 2240]},
        ],
    },
    "prefs": {"language": "en"},
}

GOOD_ANSWER = {
    "action": "tap",
    "element_id": 2,
    "scroll_direction": None,
    "say_en": "Tap Sara's name",
    "say_ur": "سارہ کے نام پر دبائیں",
    "goal_key": "whatsapp_send_photo",
    "confidence": 0.92,
    "reason": "Chat with Sara is visible; opening it is the next step.",
}


class FakeProvider:
    """Returns queued answers. A dict is the model's JSON, a str is non-JSON text, an Exception is raised."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.calls: list[list[Turn]] = []
        self.audio: list[tuple[bytes, str]] = []

    async def plan(self, system, turns, schema):
        self.calls.append(list(turns))
        a = self.answers.pop(0)
        if isinstance(a, Exception):
            raise a
        if isinstance(a, str):
            raise ValueError(f"response is not JSON: {a}")
        return copy.deepcopy(a)

    async def transcribe(self, audio, mime):
        self.audio.append((audio, mime))
        a = self.answers.pop(0)
        if isinstance(a, Exception):
            raise a
        return a


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
def make_client(settings):
    def make(provider) -> TestClient:
        return TestClient(create_app(settings, provider=provider))

    return make


@pytest.fixture
def auth() -> dict:
    return {"X-Rehnuma-Key": TEST_KEY}


@pytest.fixture
def example_request() -> dict:
    return copy.deepcopy(EXAMPLE_REQUEST)


@pytest.fixture
def good_answer() -> dict:
    return copy.deepcopy(GOOD_ANSWER)


__all__ = ["FakeProvider", "LLMError"]
