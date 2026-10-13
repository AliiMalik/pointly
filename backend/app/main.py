"""Rehnuma backend: `/v1/health`, `/v1/plan`, `/v1/stt`, `/v1/tts` (SPEC 6.1).

Run locally: `uvicorn app.main:app --host 0.0.0.0 --port 8000`
"""

import hmac

from fastapi import Depends, FastAPI, Header, HTTPException, Request

from .config import Settings, get_settings
from .llm.base import LLMProvider, UnconfiguredProvider
from .logs import JsonlLog
from .planner import Planner, PlannerUnavailable
from .schemas import PlanRequest, PlanResponse


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
    )


def create_app(settings: Settings | None = None, provider: LLMProvider | None = None) -> FastAPI:
    settings = settings or get_settings()
    provider = provider or build_provider(settings)
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

    return app


app = create_app()
