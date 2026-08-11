"""LangGraph checkpoint：优先 Redis（需 RedisJSON），失败回退 MemorySaver。"""

from __future__ import annotations

import logging
import threading
from typing import Any, Optional

from app.config import get_settings

logger = logging.getLogger(__name__)

_LOCK = threading.Lock()
_CHECKPOINTER: Any = None
_BACKEND: str = "memory"

# 7 天
DEFAULT_TTL_SECONDS = 7 * 24 * 3600


def get_checkpoint_backend() -> str:
    return _BACKEND


def get_checkpointer() -> Any:
    """进程级单例 checkpointer。"""
    global _CHECKPOINTER, _BACKEND
    if _CHECKPOINTER is not None:
        return _CHECKPOINTER
    with _LOCK:
        if _CHECKPOINTER is not None:
            return _CHECKPOINTER
        settings = get_settings()
        prefer = (settings.checkpoint_backend or "auto").strip().lower()
        ttl = int(settings.checkpoint_ttl_seconds or DEFAULT_TTL_SECONDS)
        if prefer in {"redis", "auto"}:
            try:
                saver = _build_redis_saver(settings.redis_url, ttl=ttl)
                _CHECKPOINTER = saver
                _BACKEND = "redis"
                logger.info("LangGraph checkpoint backend=redis ttl=%s", ttl)
                return _CHECKPOINTER
            except Exception as exc:  # noqa: BLE001
                if prefer == "redis":
                    raise RuntimeError(f"Redis checkpointer required but failed: {exc}") from exc
                logger.warning(
                    "Redis checkpointer unavailable (%s); fallback MemorySaver. "
                    "本机普通 Redis 通常无 RedisJSON；可用 redis-stack，或设 CHECKPOINT_BACKEND=memory",
                    exc,
                )
        return _use_memory_locked("init")


def force_memory_checkpointer(reason: str = "") -> Any:
    """运行时 Redis 掉线时切到 MemorySaver，避免整段对话直接失败。"""
    with _LOCK:
        return _use_memory_locked(reason or "runtime")


def is_checkpoint_connectivity_error(exc: BaseException) -> bool:
    """识别 Redis/checkpoint 连通类错误（含嵌套 cause）。"""
    cur: Optional[BaseException] = exc
    seen = 0
    while cur is not None and seen < 6:
        text = f"{type(cur).__name__}: {cur}".lower()
        if any(
            tip in text
            for tip in (
                "connection refused",
                "error 61",
                "redis.exceptions",
                "connectionerror",
                "json.set",
                "checkpointer",
            )
        ):
            return True
        nxt = cur.__cause__ or cur.__context__
        cur = nxt if isinstance(nxt, BaseException) else None
        seen += 1
    return False


def thread_id_for(*, user_public_id: str, session_id: str) -> str:
    """按用户隔离 thread，避免串话。"""
    uid = (user_public_id or "anon").strip() or "anon"
    sid = (session_id or "unknown").strip() or "unknown"
    return f"smart:{uid}:{sid}"


def _use_memory_locked(reason: str) -> Any:
    global _CHECKPOINTER, _BACKEND
    from langgraph.checkpoint.memory import MemorySaver

    _CHECKPOINTER = MemorySaver()
    _BACKEND = "memory"
    logger.warning("LangGraph checkpoint backend=memory (%s)", reason)
    return _CHECKPOINTER


def _assert_redis_json(client: Any) -> None:
    """langgraph-checkpoint-redis 依赖 RedisJSON 模块（JSON.SET）。"""
    probe_key = "__codearena_lg_json_probe__"
    try:
        client.execute_command("JSON.SET", probe_key, "$", "{}")
        try:
            client.execute_command("JSON.DEL", probe_key)
        except Exception:  # noqa: BLE001
            client.delete(probe_key)
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(
            "Redis 不支持 JSON.SET（需要 RedisJSON / redis-stack）。"
            "本地开发可设 CHECKPOINT_BACKEND=memory，或改用 redis/redis-stack 镜像。"
        ) from exc


def _build_redis_saver(redis_url: str, *, ttl: int) -> Any:
    url = (redis_url or "").strip() or "redis://127.0.0.1:6380/0"
    try:
        from langgraph.checkpoint.redis import RedisSaver
    except ImportError as exc:
        raise RuntimeError(
            "缺少 langgraph-checkpoint-redis。pip install langgraph-checkpoint-redis redis"
        ) from exc

    import redis

    client = redis.from_url(url)
    # 先探测：避免「能连上 Redis、写 checkpoint 才炸」
    _assert_redis_json(client)

    # 兼容不同版本 API
    if hasattr(RedisSaver, "from_conn_string"):
        saver = RedisSaver.from_conn_string(url)
        # context manager 形态：进入后保持
        if hasattr(saver, "__enter__"):
            saver = saver.__enter__()
        if hasattr(saver, "setup"):
            try:
                saver.setup()
            except Exception:  # noqa: BLE001
                pass
        # 尽量设置 TTL
        for attr in ("ttl", "ttl_seconds"):
            if hasattr(saver, attr):
                try:
                    setattr(saver, attr, ttl)
                except Exception:  # noqa: BLE001
                    pass
        return saver

    try:
        return RedisSaver(client, ttl=ttl)
    except TypeError:
        return RedisSaver(client)
