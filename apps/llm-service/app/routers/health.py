"""Health and metrics endpoints."""

from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import PlainTextResponse

from app import __version__

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "llm-service", "version": __version__}


@router.get("/metrics")
def metrics() -> PlainTextResponse:
    """本服务自身的 Prometheus 文本。"""
    body = (
        "# HELP llm_service_up 1 if the llm-service process is running\n"
        "# TYPE llm_service_up gauge\n"
        "llm_service_up 1\n"
    )
    return PlainTextResponse(body, media_type="text/plain; version=0.0.4")
