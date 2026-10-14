import json
import os
import re

from pydantic import BaseModel

from .base import Turn

LINE = re.compile(r'^#(\d+) \[([^\]]*)\] (".*") @ \((\d+),(\d+)\)-\((\d+),(\d+)\)$')
WORD = re.compile(r"[a-z0-9\-]+")

SYNONYMS = {
    "likhai": ["display", "text", "font", "size"],
    "bari": ["font", "size", "larger", "display"],
    "font": ["font", "size", "display"],
    "wifi": ["wi-fi", "wifi", "network", "internet"],
    "whatsapp": ["whatsapp"],
    "photo": ["attach", "gallery", "photo", "camera"],
    "call": ["phone", "call", "contacts", "dial"],
    "load": ["load", "recharge", "top", "mobile"],
}


SCREEN_LINE = re.compile(r'^SCREEN \(package=([^,]+), size=[^,)]+(?:, title=(".*"))?\):$')


def load_script() -> list[dict]:
    path = os.environ.get("MOCK_SCRIPT")
    if not path:
        return []
    with open(path, encoding="utf-8") as f:
        return json.load(f)


class MockProvider:
    def __init__(self) -> None:
        self.script = load_script()

    def _scripted(self, goal: str, text: str) -> dict | None:
        package = title = None
        step = None
        for line in text.splitlines():
            if line.startswith("STEP: "):
                step = int(line.removeprefix("STEP: ").strip())
            m = SCREEN_LINE.match(line)
            if m:
                package = m.group(1)
                title = json.loads(m.group(2)) if m.group(2) else None
        for rule in self.script:
            if "goal" in rule and rule["goal"].lower() not in goal:
                continue
            if "package" in rule and rule["package"] != package:
                continue
            if "title" in rule and rule["title"] != title:
                continue
            if "step" in rule and rule["step"] != step:
                continue
            action = rule.get("action", "tap")
            element_id = None
            if action in ("tap", "type", "scroll"):
                for line in text.splitlines():
                    m = LINE.match(line)
                    if m and rule.get("label", "").lower() in m.group(3).lower():
                        element_id = int(m.group(1))
                        break
                if element_id is None:
                    continue
            return {
                "action": action, "element_id": element_id,
                "scroll_direction": rule.get("scroll_direction", "down" if action == "scroll" else None),
                "say_en": rule.get("say_en", "Tap here"),
                "say_ur": rule.get("say_ur", "یہاں دبائیں"),
                "goal_key": rule.get("goal_key", "mock_scripted"), "confidence": 0.9, "reason": "mock script",
            }
        return None

    async def plan(self, system: str, turns: list[Turn], schema: type[BaseModel]) -> dict:
        text = turns[-1].text
        if text.startswith("Your last answer was invalid"):
            text = next(t.text for t in reversed(turns) if t.text.startswith("GOAL:"))
        goal = text.split("\nSTEP:", 1)[0].removeprefix("GOAL:").strip().lower()
        scripted = self._scripted(goal, text)
        if scripted:
            return scripted
        wanted: list[str] = []
        for w in WORD.findall(goal):
            wanted += SYNONYMS.get(w, [w] if len(w) > 3 else [])

        best, best_score, scrollable = None, 0, None
        for line in text.splitlines():
            m = LINE.match(line)
            if not m:
                continue
            eid, flags, label = int(m.group(1)), m.group(2), m.group(3).strip('"').lower()
            if "scrollable" in flags and scrollable is None:
                scrollable = eid
            if "clickable" not in flags:
                continue
            score = sum(1 for w in set(wanted) if w in label)
            if score > best_score:
                best, best_score, best_label = eid, score, m.group(3).strip('"')

        slug = "mock_" + "_".join(WORD.findall(goal))[:30]
        if best is not None:
            name = best_label.split(" · ")[0].split(" | ")[0]
            words = f"Tap {name}".split()[:12]
            return {
                "action": "tap", "element_id": best, "scroll_direction": None,
                "say_en": " ".join(words), "say_ur": "یہاں دبائیں",
                "goal_key": slug, "confidence": min(1.0, 0.4 + 0.2 * best_score), "reason": "mock keyword match",
            }
        if scrollable is not None:
            return {
                "action": "scroll", "element_id": scrollable, "scroll_direction": "down",
                "say_en": "Scroll down", "say_ur": "نیچے سکرول کریں",
                "goal_key": slug, "confidence": 0.3, "reason": "mock: nothing matched, scroll",
            }
        return {
            "action": "cant_help", "element_id": None, "scroll_direction": None,
            "say_en": "Sorry, I can't help with this task yet.",
            "say_ur": "اس کام میں میں ابھی مدد نہیں کر سکتی۔",
            "goal_key": slug, "confidence": 0.0, "reason": "mock: no match",
        }

    async def transcribe(self, audio: bytes, mime: str) -> dict:
        return {"text": os.environ.get("MOCK_STT_TEXT", "")}
