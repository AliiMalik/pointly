"""`/v1/stt`: Urdu speech to text, the fallback when the phone's recognizer can't do Urdu (SPEC 6.7)."""

import io
import time
import wave

from . import masking
from .llm.base import LLMError, LLMProvider
from .schemas import SttResponse

ALLOWED_TYPES = {"audio/wav", "audio/x-wav", "audio/wave", "audio/ogg", "audio/mp4", "audio/m4a", "audio/aac"}
MAX_SECONDS = 20.5
MAX_BYTES = 2 * 1024 * 1024


class SttRejected(ValueError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def normalize_mime(content_type: str | None) -> str:
    mime = (content_type or "").split(";")[0].strip().lower()
    if mime in ("audio/x-wav", "audio/wave"):
        return "audio/wav"
    if mime == "audio/m4a":
        return "audio/mp4"
    return mime


def wav_seconds(data: bytes) -> float | None:
    try:
        with wave.open(io.BytesIO(data)) as w:
            return w.getnframes() / float(w.getframerate())
    except (wave.Error, EOFError, ZeroDivisionError):
        return None


async def transcribe(provider: LLMProvider, data: bytes, content_type: str | None) -> SttResponse:
    mime = normalize_mime(content_type)
    if mime not in {normalize_mime(t) for t in ALLOWED_TYPES}:
        raise SttRejected(415, f"unsupported audio type: {content_type}")
    if not data:
        raise SttRejected(422, "empty audio")
    if len(data) > MAX_BYTES:
        raise SttRejected(413, "audio too large")
    if mime == "audio/wav":
        seconds = wav_seconds(data)
        if seconds is None:
            raise SttRejected(422, "not a valid WAV file")
        if seconds > MAX_SECONDS:
            raise SttRejected(413, f"audio is {seconds:.1f} s; max is 20 s")

    start = time.monotonic()
    try:
        result = await provider.transcribe(data, mime)
    except LLMError:
        raise
    latency_ms = int((time.monotonic() - start) * 1000)
    return SttResponse(
        text=masking.mask(str(result.get("text") or "").strip()),
        latency_ms=latency_ms,
    )
