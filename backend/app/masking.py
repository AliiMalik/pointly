import re

PLACEHOLDER = "[number]"
LONG_NUMBER = re.compile(r"(?<!\d)\d(?:[\s-]?\d){5,}(?!\d)")


def mask(s: str | None) -> str | None:
    if s is None:
        return None
    return LONG_NUMBER.sub(PLACEHOLDER, s)
