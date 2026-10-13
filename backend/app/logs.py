"""JSONL logs (SPEC 6.8). Element texts are only written when LOG_SCREENS=true (development)."""

import json
import threading
from datetime import datetime, timezone
from pathlib import Path


class JsonlLog:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()

    def write(self, record: dict) -> None:
        line = json.dumps({"ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"), **record}, ensure_ascii=False)
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")
