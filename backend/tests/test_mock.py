import json

import pytest

from app.llm.base import Turn
from app.llm.mock import MockProvider
from app.prompts import user_message
from app.schemas import LLMPlan, PlanRequest

SCREEN = {
    "package": "com.android.settings", "window_title": "Settings", "width": 1080, "height": 2400,
    "elements": [
        {"id": 4, "label": "Network & internet · Mobile, Wi-Fi", "cls": "View", "clickable": True, "bounds": [0, 0, 10, 10]},
    ],
}


@pytest.fixture
def script(tmp_path, monkeypatch):
    rules = [
        {"goal": "user answer", "title": "Settings", "label": "Network", "goal_key": "wifi_on"},
        {"goal": "wifi", "title": "Settings", "action": "ask", "say_roman": "Kaun sa?"},
    ]
    path = tmp_path / "rules.json"
    path.write_text(json.dumps(rules), encoding="utf-8")
    monkeypatch.setenv("MOCK_SCRIPT", str(path))


async def ask_mock(goal: str, step: int) -> dict:
    req = PlanRequest.model_validate({"session_id": "s", "goal_text": goal, "step_index": step, "screen": SCREEN})
    return await MockProvider().plan("", [Turn("user", user_message(req))], LLMPlan)


async def test_first_answer_is_ask(script):
    assert (await ask_mock("WiFi on karna hai", 1))["action"] == "ask"


async def test_answer_line_is_part_of_the_goal(script):
    answer = await ask_mock("WiFi on karna hai\nUser answer: ghar wala", 2)
    assert (answer["action"], answer["element_id"]) == ("tap", 4)
