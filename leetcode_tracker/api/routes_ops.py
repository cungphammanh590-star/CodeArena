"""维护台 API：日志清理、stats 重建、图谱与只读配置。"""

from __future__ import annotations

from typing import Any, Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from leetcode_tracker.infra.autostart import clean_logs
from leetcode_tracker.coach.debug_log import clean_coach_debug_logs
from leetcode_tracker.infra.config import (
    clear_llm_api_key,
    load_config,
    mask_config_for_display,
    update_llm_config,
)
from leetcode_tracker.infra.db import init_db
from leetcode_tracker.kg.import_maps import get_kg_status, import_maps
from leetcode_tracker.infra.paths import db_path
from leetcode_tracker.core.problem_stats import rebuild_stats
from leetcode_tracker.llm.provider import (
    DEEPSEEK_DEFAULT_MODEL,
    get_llm_settings,
    probe_chat_model,
)

router = APIRouter()


def _json_error(message: str, *, status_code: int = 400) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"status": "error", "message": message},
    )


async def _body(request: Request) -> dict[str, Any]:
    try:
        data = await request.json()
    except Exception:  # noqa: BLE001
        return {}
    return data if isinstance(data, dict) else {}


def _require_confirm(body: dict[str, Any]) -> Optional[JSONResponse]:
    if body.get("confirm") is not True:
        return _json_error("confirm=true required")
    return None


@router.get("/api/ops/config")
def ops_config() -> dict[str, Any]:
    cfg = mask_config_for_display(load_config())
    payload = dict(cfg)
    payload["db_path_readonly"] = str(db_path())
    return {"status": "ok", "config": payload}


@router.post("/api/ops/llm/config")
async def ops_llm_config(request: Request) -> Any:
    """保存陪练模型设置。api_key 留空表示保留原密钥。"""
    body = await _body(request)
    provider = str(body.get("provider") or "").strip().lower()
    if provider not in {"ollama", "api"}:
        return _json_error("provider 须为 ollama 或 api")
    api_provider = str(body.get("api_provider") or "").strip().lower()
    coach_model = str(body.get("coach_model") or "").strip()
    base_url = str(body.get("base_url") or "").strip()
    api_key_raw = body.get("api_key")
    api_key: str | None
    if api_key_raw is None:
        api_key = None
    else:
        api_key = str(api_key_raw).strip()

    if provider == "api":
        api_provider = api_provider or "deepseek"
        if api_provider != "deepseek":
            return _json_error("当前仅支持 api_provider=deepseek")
        coach_model = coach_model or DEEPSEEK_DEFAULT_MODEL
        existing = get_llm_settings()
        if not api_key and not existing.get("api_key"):
            return _json_error("使用 DeepSeek 时请填写 API Key")
    else:
        api_provider = ""
        coach_model = coach_model or "qwen2.5:7b-instruct-q4_K_M"

    try:
        cfg = update_llm_config(
            provider=provider,
            api_provider=api_provider,
            coach_model=coach_model,
            api_key=api_key if api_key else None,
            base_url=base_url if provider == "api" else "",
        )
    except ValueError as exc:
        return _json_error(str(exc))
    return {
        "status": "ok",
        "config": mask_config_for_display(cfg),
    }


@router.post("/api/ops/llm/test")
async def ops_llm_test(request: Request) -> Any:
    """用当前已保存配置探活（可先保存再测）。"""
    _ = await _body(request)
    settings = get_llm_settings()
    if settings["provider"] == "api" and not settings["api_key"]:
        return _json_error("未配置 API Key，请先填写并保存后再测试")
    try:
        result = probe_chat_model()
        return {"status": "ok", **result}
    except Exception as exc:  # noqa: BLE001
        return _json_error(str(exc), status_code=502)


@router.post("/api/ops/llm/clear-key")
async def ops_llm_clear_key(request: Request) -> Any:
    """一键清除 API Key，并切回本地 Ollama。"""
    body = await _body(request)
    denied = _require_confirm(body)
    if denied:
        return denied
    switch = body.get("switch_to_ollama", True) is not False
    cfg = clear_llm_api_key(switch_to_ollama=switch)
    return {
        "status": "ok",
        "config": mask_config_for_display(cfg),
        "message": "已清除 API Key" + ("，并切回 Ollama" if switch else ""),
    }


@router.get("/api/ops/kg")
def ops_kg_status() -> dict[str, Any]:
    conn = init_db()
    try:
        return {"status": "ok", **get_kg_status(conn)}
    finally:
        conn.close()


@router.post("/api/ops/kg/import")
async def ops_kg_import(request: Request) -> Any:
    body = await _body(request)
    denied = _require_confirm(body)
    if denied:
        return denied
    conn = init_db()
    try:
        result = import_maps(conn)
        return {"status": "ok", **result}
    except Exception as exc:  # noqa: BLE001
        return _json_error(str(exc), status_code=500)
    finally:
        conn.close()


@router.post("/api/ops/logs/clean")
async def ops_logs_clean(request: Request) -> Any:
    body = await _body(request)
    denied = _require_confirm(body)
    if denied:
        return denied
    include_coach = body.get("include_coach_debug", True)
    service_removed = clean_logs()
    coach_removed: list = []
    if include_coach is not False:
        coach_removed = clean_coach_debug_logs()
    return {
        "status": "ok",
        "service_logs": [str(p) for p in service_removed],
        "coach_debug_logs": [str(p) for p in coach_removed],
        "count": len(service_removed) + len(coach_removed),
    }


@router.post("/api/ops/stats/rebuild")
async def ops_stats_rebuild(request: Request) -> Any:
    body = await _body(request)
    denied = _require_confirm(body)
    if denied:
        return denied
    from_scratch = body.get("from_scratch", True) is not False
    conn = init_db()
    try:
        count = rebuild_stats(conn, from_scratch=from_scratch)
        return {
            "status": "ok",
            "problems": count,
            "from_scratch": from_scratch,
        }
    except Exception as exc:  # noqa: BLE001
        return _json_error(str(exc), status_code=500)
    finally:
        conn.close()
