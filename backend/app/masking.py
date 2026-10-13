"""Long-number masking, applied again on the server (SPEC rule 6, 5.3).

Same pattern as the phone (`Masking.kt`): 6+ digits with single spaces or dashes allowed
between digits. Trailing separators are kept.
"""

import re

PLACEHOLDER = "[number]"
LONG_NUMBER = re.compile(r"(?<!\d)\d(?:[\s-]?\d){5,}(?!\d)")


def mask(s: str | None) -> str | None:
    if s is None:
        return None
    return LONG_NUMBER.sub(PLACEHOLDER, s)
