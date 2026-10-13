"""Development-only keyword planner (`LLM_PROVIDER=mock`). No API key, no network.

It reads the same prompt the real model gets and taps the clickable element whose label shares the
most keywords with the goal. Good enough to exercise the phone's guidance loop on an emulator; not a
substitute for the real planner.
"""

import re

from pydantic import BaseModel

from .base import Turn

LINE = re.compile(r'^#(\d+) \[([^\]]*)\] (".*") @ \((\d+),(\d+)\)-\((\d+),(\d+)\)$')
WORD = re.compile(r"[a-z0-9\-]+")

# Goal word -> words to look for on screen.
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


class MockProvider:
    async def plan(self, system: str, turns: list[Turn], schema: type[BaseModel]) -> dict:
        text = turns[-1].text
        if text.startswith("Your last answer was invalid"):
            text = next(t.text for t in reversed(turns) if t.text.startswith("GOAL:"))
        goal = text.splitlines()[0].removeprefix("GOAL:").strip().lower()
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
            words = f"{name} wala button dabayein".split()[:12]
            return {
                "action": "tap", "element_id": best, "scroll_direction": None,
                "say_urdu": "یہاں دبائیں", "say_roman": " ".join(words),
                "goal_key": slug, "confidence": min(1.0, 0.4 + 0.2 * best_score), "reason": "mock keyword match",
            }
        if scrollable is not None:
            return {
                "action": "scroll", "element_id": scrollable, "scroll_direction": "down",
                "say_urdu": "نیچے سکرول کریں", "say_roman": "Neeche scroll karein",
                "goal_key": slug, "confidence": 0.3, "reason": "mock: nothing matched, scroll",
            }
        return {
            "action": "cant_help", "element_id": None, "scroll_direction": None,
            "say_urdu": "اس کام میں میں ابھی مدد نہیں کر سکتی۔",
            "say_roman": "Is kaam mein main abhi madad nahi kar sakti.",
            "goal_key": slug, "confidence": 0.0, "reason": "mock: no match",
        }

    async def transcribe(self, audio: bytes, mime: str) -> dict:
        return {"text_urdu": "", "text_roman": ""}
