"""Rehnuma backend: `/v1/health`, `/v1/plan`, `/v1/stt`, `/v1/tts` (SPEC 6.1).

Run locally: `uvicorn app.main:app --host 0.0.0.0 --port 8000`
"""

import hmac

from fastapi import Depends, FastAPI, Header, HTTPException, Request

from .config import Settings, get_settings


def require_key(request: Request, x_rehnuma_key: str | None = Header(default=None)) -> None:
    """All endpoints except health need `X-Rehnuma-Key` equal to `REHNUMA_APP_KEY` (SPEC 6.1)."""
    expected = request.app.state.settings.rehnuma_app_key
    if not expected or not x_rehnuma_key or not hmac.compare_digest(x_rehnuma_key, expected):
        raise HTTPException(status_code=401, detail="invalid or missing X-Rehnuma-Key")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(title="Rehnuma backend", version="1.0.0")
    app.state.settings = settings

    @app.get("/v1/health")
    async def health() -> dict:
        return {"status": "ok", "model": settings.llm_model}

    @app.get("/v1/ping", dependencies=[Depends(require_key)])
    async def ping() -> dict:
        """Authenticated no-op so the app's Settings screen can test the key."""
        return {"status": "ok"}

    return app


app = create_app()
