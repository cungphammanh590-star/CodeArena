"""FastAPI 应用装配。"""

from __future__ import annotations

from contextlib import asynccontextmanager
from collections.abc import AsyncIterator

from fastapi import FastAPI, Request, Response
from starlette.middleware.base import BaseHTTPMiddleware

from leetcode_tracker.api.routes_coach import router as coach_router
from leetcode_tracker.api.routes_core import router as core_router
from leetcode_tracker.api.routes_learning import router as learning_router
from leetcode_tracker.api.routes_ops import router as ops_router
from leetcode_tracker.api.routes_pages import router as pages_router
from leetcode_tracker.api.routes_stats import router as stats_router
from leetcode_tracker.infra.db import init_db


class MirrorOriginCORS(BaseHTTPMiddleware):
    """回显 Origin，供 Chrome 扩展跨域访问本机桥接。"""

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
async def _lifespan(_app: FastAPI) -> AsyncIterator[None]:
    conn = init_db()
    try:
        from leetcode_tracker.coach.catalog import ensure_hot100_materialized
        from leetcode_tracker.kg.import_maps import ensure_kg_imported

        ensure_hot100_materialized(conn)
        try:
            ensure_kg_imported(conn)
        except Exception:  # noqa: BLE001
            pass
    finally:
        conn.close()
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="LeetCode Tracker Bridge",
        version="0.3.4",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=_lifespan,
    )
    app.add_middleware(MirrorOriginCORS)
    app.include_router(core_router)
    app.include_router(pages_router)
    app.include_router(stats_router)
    app.include_router(learning_router)
    app.include_router(coach_router)
    app.include_router(ops_router)
    return app
