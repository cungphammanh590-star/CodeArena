"""学习偏好、题单、已掌握、今日复习 API。"""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, PlainTextResponse

from leetcode_tracker.coach.catalog import (
    HOT100_LIST_ID,
    SAMPLE_LIST_JSON,
    SAMPLE_SINGLE_JSON,
    create_list,
    delete_list,
    get_list_row,
    import_list_json,
    list_problem_lists,
    load_list_items,
    remove_list_item,
    restore_default_list,
    set_active_list,
    suggest_list_id,
    catalog_progress,
    ensure_hot100_materialized,
)
from leetcode_tracker.coach.mastered import list_mastered, set_mastered
from leetcode_tracker.coach.review import list_review_due, pick_review_queue
from leetcode_tracker.infra.config import (
    get_learning_config,
    mask_config_for_display,
    update_learning_config,
)
from leetcode_tracker.infra.db import init_db
from leetcode_tracker.kg.import_maps import ensure_kg_imported

router = APIRouter()


def _err(message: str, *, status_code: int = 400) -> JSONResponse:
    return JSONResponse(
        status_code=status_code, content={"status": "error", "message": message}
    )


async def _body(request: Request) -> dict[str, Any]:
    try:
        data = await request.json()
    except Exception:  # noqa: BLE001
        return {}
    return data if isinstance(data, dict) else {}


@router.get("/api/learning")
def api_learning() -> Any:
    conn = init_db()
    try:
        ensure_hot100_materialized(conn)
        try:
            ensure_kg_imported(conn)
        except Exception:  # noqa: BLE001
            pass
        learning = get_learning_config()
        progress = catalog_progress(conn)
        return {
            "status": "ok",
            "learning": learning,
            "progress": progress,
            "lists": list_problem_lists(conn),
        }
    finally:
        conn.close()


@router.post("/api/learning")
async def api_learning_update(request: Request) -> Any:
    body = await _body(request)
    kwargs: dict[str, Any] = {}
    if "list_mode" in body:
        kwargs["list_mode"] = bool(body["list_mode"])
    if "kg_mode" in body:
        kwargs["kg_mode"] = bool(body["kg_mode"])
    if "active_list_id" in body:
        kwargs["active_list_id"] = str(body["active_list_id"])
    if not kwargs:
        return _err("无更新字段")
    cfg = update_learning_config(**kwargs)
    return {
        "status": "ok",
        "learning": cfg.get("learning"),
        "config": mask_config_for_display(cfg),
    }


@router.get("/api/lists")
def api_lists() -> Any:
    conn = init_db()
    try:
        return {"status": "ok", "lists": list_problem_lists(conn)}
    finally:
        conn.close()


@router.get("/api/lists/sample")
def api_lists_sample(kind: str = "list") -> Any:
    payload = SAMPLE_SINGLE_JSON if kind == "single" else SAMPLE_LIST_JSON
    return PlainTextResponse(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        media_type="application/json",
    )


@router.get("/api/lists/{list_id}")
def api_list_detail(list_id: str) -> Any:
    conn = init_db()
    try:
        row = get_list_row(conn, list_id)
        if row is None and list_id == HOT100_LIST_ID:
            ensure_hot100_materialized(conn)
            row = get_list_row(conn, list_id)
        if row is None:
            return _err(f"题单不存在: {list_id}", status_code=404)
        items = load_list_items(conn, list_id)
        return {
            "status": "ok",
            "list": row,
            "list_id": list_id,
            "items": items,
            "total": len(items),
        }
    finally:
        conn.close()


@router.post("/api/lists")
async def api_lists_create(request: Request) -> Any:
    body = await _body(request)
    name = str(body.get("name") or "").strip()
    list_id = str(body.get("list_id") or "").strip() or None
    if not name:
        return _err("请填写题单名称")
    conn = init_db()
    try:
        if not list_id:
            list_id = suggest_list_id(name)
            # 冲突时加后缀
            base = list_id
            n = 2
            while get_list_row(conn, list_id):
                list_id = f"{base}-{n}"[:64]
                n += 1
        row = create_list(conn, list_id=list_id, name=name)
        if body.get("set_active"):
            set_active_list(row["id"])
        return {"status": "ok", "list": row}
    except ValueError as exc:
        return _err(str(exc))
    finally:
        conn.close()


@router.post("/api/lists/active")
async def api_lists_set_active(request: Request) -> Any:
    body = await _body(request)
    list_id = str(body.get("list_id") or "").strip()
    if not list_id:
        return _err("list_id required")
    if list_id == "hot100" or body.get("restore_default"):
        cfg = restore_default_list()
    else:
        conn = init_db()
        try:
            if get_list_row(conn, list_id) is None:
                return _err(f"题单不存在: {list_id}", status_code=404)
        finally:
            conn.close()
        cfg = set_active_list(list_id)
    return {"status": "ok", "learning": cfg.get("learning")}


@router.post("/api/lists/import")
async def api_lists_import(request: Request) -> Any:
    body = await _body(request)
    mode = str(body.get("mode") or "append").strip().lower()
    list_id = str(body.get("list_id") or "").strip()
    if not list_id:
        return _err("请先选择或新建题单")
    raw = body.get("data")
    if raw is None and "problems" in body:
        raw = body
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            return _err("JSON 解析失败")
    create_if_missing = bool(body.get("create_if_missing"))
    new_name = str(body.get("name") or "").strip() or None
    conn = init_db()
    try:
        result = import_list_json(
            conn,
            raw,
            list_id=list_id,
            mode=mode,
            create_if_missing=create_if_missing,
            new_list_name=new_name,
        )
        if body.get("set_active"):
            set_active_list(result["list_id"])
        return {"status": "ok", **result}
    except ValueError as exc:
        return _err(str(exc))
    except Exception as exc:  # noqa: BLE001
        return _err(str(exc), status_code=500)
    finally:
        conn.close()


@router.delete("/api/lists/{list_id}/items/{problem_id}")
def api_list_remove_item(list_id: str, problem_id: int) -> Any:
    conn = init_db()
    try:
        result = remove_list_item(conn, list_id, problem_id)
        return {"status": "ok", **result}
    except ValueError as exc:
        return _err(str(exc))
    finally:
        conn.close()


@router.delete("/api/lists/{list_id}")
def api_list_delete(list_id: str) -> Any:
    conn = init_db()
    try:
        result = delete_list(conn, list_id)
        return {"status": "ok", **result, "learning": get_learning_config()}
    except ValueError as exc:
        return _err(str(exc))
    finally:
        conn.close()


@router.get("/api/review/today")
def api_review_today(limit: int = 20) -> Any:
    conn = init_db()
    try:
        due = list_review_due(conn, limit=max(1, min(limit, 50)))
        queue = pick_review_queue(conn, limit=min(3, len(due) or 3))
        progress = catalog_progress(conn)
        return {
            "status": "ok",
            "due": due,
            "queue": queue,
            "count": len(due),
            "progress": progress,
            "learning": get_learning_config(),
        }
    finally:
        conn.close()


@router.get("/api/mastered")
def api_mastered_list() -> Any:
    conn = init_db()
    try:
        return {"status": "ok", "items": list_mastered(conn)}
    finally:
        conn.close()


@router.post("/api/problems/{problem_id}/mastered")
async def api_mastered_set(problem_id: int, request: Request) -> Any:
    body = await _body(request)
    note = str(body.get("note") or "")
    conn = init_db()
    try:
        row = set_mastered(conn, problem_id, mastered=True, note=note)
        return {"status": "ok", **row}
    except ValueError as exc:
        return _err(str(exc))
    finally:
        conn.close()


@router.delete("/api/problems/{problem_id}/mastered")
def api_mastered_clear(problem_id: int) -> Any:
    conn = init_db()
    try:
        row = set_mastered(conn, problem_id, mastered=False)
        return {"status": "ok", **row}
    finally:
        conn.close()
