from dataclasses import dataclass
from typing import Literal, Protocol

from pydantic import BaseModel


@dataclass(frozen=True)
class Turn:
    role: Literal["user", "model"]
    text: str


class LLMError(Exception):
    pass


class LLMProvider(Protocol):
    async def plan(self, system: str, turns: list[Turn], schema: type[BaseModel]) -> dict:
        ...

    async def transcribe(self, audio: bytes, mime: str) -> dict:
        ...


class UnconfiguredProvider:
    def __init__(self, reason: str):
        self.reason = reason

    async def plan(self, system: str, turns: list[Turn], schema: type[BaseModel]) -> dict:
        raise LLMError(self.reason)

    async def transcribe(self, audio: bytes, mime: str) -> dict:
        raise LLMError(self.reason)
