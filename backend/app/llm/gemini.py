import json
import logging
import time

from google import genai
from google.genai import errors, types
from pydantic import BaseModel

from .base import LLMError, Turn

STT_PROMPT = """\
Transcribe this short voice message from a user in Pakistan. It is in English, Urdu or Punjabi,
often mixed with English app names (WhatsApp, Settings, WiFi). Return JSON with:
- text: the words as spoken: English in English letters, Urdu or Punjabi in Urdu script
If nothing intelligible was said, return an empty string."""


MIN_DEADLINE_S = 10.0
FALLBACK_CODES = {429, 500, 502, 503, 504}
PRIMARY_COOLDOWN_S = 60.0

log = logging.getLogger("pointly.gemini")


class _Transcript(BaseModel):
    text: str


class GeminiProvider:
    def __init__(
        self,
        api_key: str,
        llm_model: str,
        stt_model: str,
        thinking_level: str = "low",
        timeout_s: float = 6.5,
        fallback_model: str = "",
    ):
        self.client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(timeout=int(max(timeout_s, MIN_DEADLINE_S) * 1000)),
        )
        self.llm_model = llm_model
        self.stt_model = stt_model or llm_model
        self.fallback_model = fallback_model
        self._skip_until: dict[str, float] = {}
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
            resp = await self._generate(self.llm_model, contents, config)
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
            resp = await self._generate(
                self.stt_model, [types.Part.from_bytes(data=audio, mime_type=mime), STT_PROMPT], config,
            )
        except (errors.APIError, OSError, TimeoutError) as e:
            raise LLMError(f"gemini transcribe failed: {e}") from e
        try:
            return _json(resp.text)
        except ValueError as e:
            raise LLMError(str(e)) from e


    async def _generate(self, model: str, contents, config):
        models = [model]
        fallback = self.fallback_model
        if fallback and fallback != model:
            if time.monotonic() < self._skip_until.get(model, 0.0):
                models = [fallback]
            else:
                models.append(fallback)
        for i, m in enumerate(models):
            try:
                return await self.client.aio.models.generate_content(model=m, contents=contents, config=config)
            except errors.APIError as e:
                if e.code in FALLBACK_CODES and i < len(models) - 1:
                    self._skip_until[m] = time.monotonic() + PRIMARY_COOLDOWN_S
                    log.warning("model %s returned %s; using fallback %s", m, e.code, models[i + 1])
                    continue
                raise
        raise AssertionError("unreachable")


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
