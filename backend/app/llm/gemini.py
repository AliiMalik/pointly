"""Gemini provider using the `google-genai` SDK (SPEC 6.5). Model ids come from env."""

import json

from google import genai
from google.genai import errors, types
from pydantic import BaseModel

from .base import LLMError, Turn

STT_PROMPT = """\
Transcribe this short voice message from a user in Pakistan. It is in Urdu or Punjabi, possibly
mixed with English app names (WhatsApp, Settings, WiFi). Return JSON with:
- text_urdu: the words in Urdu script
- text_roman: the same words in simple Roman Urdu
If nothing intelligible was said, return empty strings."""


class _Transcript(BaseModel):
    text_urdu: str
    text_roman: str


class GeminiProvider:
    def __init__(
        self,
        api_key: str,
        llm_model: str,
        stt_model: str,
        thinking_level: str = "low",
        timeout_s: float = 6.5,
    ):
        self.client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(timeout=int(timeout_s * 1000)),
        )
        self.llm_model = llm_model
        self.stt_model = stt_model or llm_model
        self.thinking = types.ThinkingConfig(thinking_level=thinking_level) if thinking_level else None

    async def plan(self, system: str, turns: list[Turn], schema: type[BaseModel]) -> dict:
        contents = [
            types.Content(role=t.role, parts=[types.Part.from_text(text=t.text)]) for t in turns
        ]
        config = types.GenerateContentConfig(
            system_instruction=system,
            response_mime_type="application/json",
            response_schema=schema,
            temperature=0.2,
            thinking_config=self.thinking,
        )
        try:
            resp = await self.client.aio.models.generate_content(
                model=self.llm_model, contents=contents, config=config,
            )
        except (errors.APIError, OSError, TimeoutError) as e:
            raise LLMError(f"gemini plan failed: {e}") from e
        return _json(resp.text)

    async def transcribe(self, audio: bytes, mime: str) -> dict:
        config = types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=_Transcript,
            temperature=0.0,
            thinking_config=self.thinking,
        )
        try:
            resp = await self.client.aio.models.generate_content(
                model=self.stt_model,
                contents=[types.Part.from_bytes(data=audio, mime_type=mime), STT_PROMPT],
                config=config,
            )
        except (errors.APIError, OSError, TimeoutError) as e:
            raise LLMError(f"gemini transcribe failed: {e}") from e
        try:
            return _json(resp.text)
        except ValueError as e:
            raise LLMError(str(e)) from e


def _json(text: str | None) -> dict:
    if not text:
        raise ValueError("empty response")
    try:
        value = json.loads(text)
    except json.JSONDecodeError as e:
        raise ValueError(f"response is not JSON: {e}") from e
    if not isinstance(value, dict):
        raise ValueError("response JSON is not an object")
    return value
