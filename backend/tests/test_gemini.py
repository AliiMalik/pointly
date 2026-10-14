import json
import types as pytypes

import pytest
from google.genai import errors

from app.llm.base import Turn
from app.llm.gemini import GeminiProvider
from app.schemas import LLMPlan

ANSWER = {"action": "done", "element_id": None, "scroll_direction": None, "say_en": "Well done",
          "say_ur": "شاباش", "goal_key": "g", "confidence": 1, "reason": "r"}


class FakeModels:
    def __init__(self, busy: set[str]):
        self.busy = busy
        self.calls: list[str] = []

    async def generate_content(self, model, contents, config):
        self.calls.append(model)
        if model in self.busy:
            raise errors.ServerError(503, {"error": {"code": 503, "message": "high demand", "status": "UNAVAILABLE"}})
        return pytypes.SimpleNamespace(text=json.dumps(ANSWER))


def provider(busy, fallback="fallback-model"):
    p = GeminiProvider(api_key="test", llm_model="main-model", stt_model="", fallback_model=fallback)
    fake = FakeModels(busy)
    p.client = pytypes.SimpleNamespace(aio=pytypes.SimpleNamespace(models=fake))
    return p, fake


async def test_busy_primary_falls_back_then_is_skipped():
    p, fake = provider({"main-model"})
    assert (await p.plan("sys", [Turn("user", "hi")], LLMPlan))["action"] == "done"
    assert fake.calls == ["main-model", "fallback-model"]
    await p.plan("sys", [Turn("user", "hi")], LLMPlan)
    assert fake.calls[2:] == ["fallback-model"]


async def test_healthy_primary_is_used():
    p, fake = provider(set())
    await p.plan("sys", [Turn("user", "hi")], LLMPlan)
    assert fake.calls == ["main-model"]


async def test_no_fallback_configured_raises():
    from app.llm.base import LLMError

    p, fake = provider({"main-model"}, fallback="")
    with pytest.raises(LLMError):
        await p.plan("sys", [Turn("user", "hi")], LLMPlan)


def test_deadline_is_at_least_ten_seconds():
    p, _ = provider(set())
    assert p.client is not None
    real = GeminiProvider(api_key="test", llm_model="m", stt_model="")
    assert real.client._api_client._http_options.timeout >= 10_000
