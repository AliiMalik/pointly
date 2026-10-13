"""LLM provider interface (SPEC 6.5).

`plan` takes the conversation as turns instead of a single user string, so the few-shot
examples and the one corrective retry (SPEC 6.4 step 5) can be sent as earlier turns.
"""

from dataclasses import dataclass
from typing import Literal, Protocol

from pydantic import BaseModel


@dataclass(frozen=True)
class Turn:
    role: Literal["user", "model"]
    text: str


class LLMError(Exception):
    """The provider failed (network, quota, timeout). Not the same as an invalid answer."""


class LLMProvider(Protocol):
    async def plan(self, system: str, turns: list[Turn], schema: type[BaseModel]) -> dict:
        """Returns the model's JSON answer as a dict. Raises LLMError, or ValueError for non-JSON output."""
        ...

    async def transcribe(self, audio: bytes, mime: str) -> dict:
        """Returns {"text_urdu": ..., "text_roman": ...}. Raises LLMError."""
        ...


class UnconfiguredProvider:
    """Used when GEMINI_API_KEY or the model ids are missing: every call fails with a clear error."""

    def __init__(self, reason: str):
        self.reason = reason

    async def plan(self, system: str, turns: list[Turn], schema: type[BaseModel]) -> dict:
        raise LLMError(self.reason)

    async def transcribe(self, audio: bytes, mime: str) -> dict:
        raise LLMError(self.reason)
