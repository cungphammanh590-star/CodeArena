"""CodeArena LLM / Coach FastAPI entrypoint."""

from __future__ import annotations

from contextlib import asynccontextmanager
from collections.abc import AsyncIterator

from fastapi import FastAPI, Request, Response
from starlette.middleware.base import BaseHTTPMiddleware

from app.config import get_settings
from app.routers import coach, health
from app.services.llm_client import shutdown_llm_client


class MirrorOriginCORS(BaseHTTPMiddleware):
    """Echo Origin for local web / client tooling."""

    async def dispatch(self, request: Request, call_next) -> Response:
        if request.method == "OPTIONS":
            response = Response(status_code=204)
        else:
            response = await call_next(request)
        origin = request.headers.get("origin") or "*"
        response.headers["Access-Control-Allow-Origin"] = origin
        response.headers["Access-Control-Allow-Credentials"] = "true"
        response.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
        response.headers["Access-Control-Allow-Headers"] = (
            request.headers.get("access-control-request-headers") or "Content-Type"
        )
        response.headers["Vary"] = "Origin"
        return response


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    yield
    await shutdown_llm_client()


def create_app() -> FastAPI:
    settings = get_settings()
    application = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        lifespan=lifespan,
    )
    application.add_middleware(MirrorOriginCORS)
    application.include_router(health.router)
    application.include_router(coach.router)
    return application


app = create_app()
