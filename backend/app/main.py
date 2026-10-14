"""Rehnuma backend: `/v1/health`, `/v1/plan`, `/v1/stt`, `/v1/tts` (SPEC 6.1).

Run locally: `uvicorn app.main:app --host 0.0.0.0 --port 8000`
"""

import hmac
import os

from fastapi import Depends, FastAPI, File, Header, HTTPException, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import Response

from .config import Settings, get_settings
from .llm.base import LLMProvider, UnconfiguredProvider
from .logs import JsonlLog
from .llm.base import LLMError
from .planner import Planner, PlannerUnavailable
from .schemas import PlanRequest, PlanResponse, SttResponse, TtsRequest
from .stt import SttRejected, transcribe
from .tts import CachedTts, CloudTts, TtsEngine, TtsError, UnconfiguredTts


def require_key(request: Request, x_rehnuma_key: str | None = Header(default=None)) -> None:
    """All endpoints except health need `X-Rehnuma-Key` equal to `REHNUMA_APP_KEY` (SPEC 6.1)."""
    expected = request.app.state.settings.rehnuma_app_key
    if not expected or not x_rehnuma_key or not hmac.compare_digest(x_rehnuma_key, expected):
        raise HTTPException(status_code=401, detail="invalid or missing X-Rehnuma-Key")


def build_provider(settings: Settings) -> LLMProvider:
    if settings.llm_provider == "mock":
        from .llm.mock import MockProvider

        return MockProvider()
    if not settings.gemini_api_key:
        return UnconfiguredProvider("GEMINI_API_KEY is not set")
    if not settings.llm_model:
        return UnconfiguredProvider("LLM_MODEL is not set")
    from .llm.gemini import GeminiProvider

    return GeminiProvider(
        api_key=settings.gemini_api_key,
        llm_model=settings.llm_model,
        stt_model=settings.stt_model,
        thinking_level=settings.llm_thinking,
        fallback_model=settings.llm_fallback_model,
    )


def build_tts(settings: Settings) -> TtsEngine:
    if not settings.tts_model:
        return UnconfiguredTts("TTS_MODEL is not set")
    if settings.google_application_credentials:
        # The Google client reads credentials from the process environment, not from .env.
        os.environ.setdefault("GOOGLE_APPLICATION_CREDENTIALS", settings.google_application_credentials)
    return CloudTts(model=settings.tts_model, voice=settings.tts_voice)


def create_app(
    settings: Settings | None = None,
    provider: LLMProvider | None = None,
    tts_engine: TtsEngine | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    provider = provider or build_provider(settings)
    tts = CachedTts(tts_engine or build_tts(settings), settings.tts_cache_dir, key_extra=f"{settings.tts_model}|{settings.tts_voice}")
    app = FastAPI(title="Rehnuma backend", version="1.0.0")
    app.state.settings = settings
    app.state.provider = provider
    app.state.planner = Planner(
        provider,
        log=JsonlLog(settings.log_dir / "plan.jsonl"),
        log_screens=settings.log_screens,
    )

    @app.get("/v1/health")
    async def health() -> dict:
        return {"status": "ok", "model": "mock" if settings.llm_provider == "mock" else settings.llm_model}

    @app.get("/v1/ping", dependencies=[Depends(require_key)])
    async def ping() -> dict:
        """Authenticated no-op so the app's Settings screen can test the key."""
        return {"status": "ok"}

    @app.post("/v1/plan", dependencies=[Depends(require_key)], response_model=PlanResponse)
    async def plan(req: PlanRequest) -> PlanResponse:
        try:
            return await app.state.planner.plan(req)
        except PlannerUnavailable as e:
            raise HTTPException(status_code=503, detail=f"planner unavailable: {e}") from e

    @app.post("/v1/stt", dependencies=[Depends(require_key)], response_model=SttResponse)
    async def stt(audio: UploadFile = File(...)) -> SttResponse:
        data = await audio.read()
        try:
            return await transcribe(app.state.provider, data, audio.content_type)
        except SttRejected as e:
            raise HTTPException(status_code=e.status, detail=str(e)) from e
        except LLMError as e:
            raise HTTPException(status_code=503, detail=f"stt unavailable: {e}") from e

    @app.post("/v1/tts", dependencies=[Depends(require_key)])
    async def text_to_speech(req: TtsRequest) -> Response:
        try:
            audio = await run_in_threadpool(tts.get, req.text.strip())
        except TtsError as e:
            # The phone falls back to its own voice or captions only (SPEC 6.7).
            raise HTTPException(status_code=503, detail=str(e)) from e
        return Response(content=audio, media_type="audio/wav")

    return app


app = create_app()
