"""LangGraph checkpoint：优先 Redis（带 TTL），失败回退 MemorySaver。"""

from __future__ import annotations

import logging
import threading
from typing import Any

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
                logger.warning("Redis checkpointer unavailable (%s); fallback MemorySaver", exc)
        from langgraph.checkpoint.memory import MemorySaver

        _CHECKPOINTER = MemorySaver()
        _BACKEND = "memory"
        logger.info("LangGraph checkpoint backend=memory")
        return _CHECKPOINTER


def thread_id_for(*, user_public_id: str, session_id: str) -> str:
    """按用户隔离 thread，避免串话。"""
    uid = (user_public_id or "anon").strip() or "anon"
    sid = (session_id or "unknown").strip() or "unknown"
    return f"smart:{uid}:{sid}"


def _build_redis_saver(redis_url: str, *, ttl: int) -> Any:
    url = (redis_url or "").strip() or "redis://127.0.0.1:6379/0"
    try:
        from langgraph.checkpoint.redis import RedisSaver
    except ImportError as exc:
        raise RuntimeError(
            "缺少 langgraph-checkpoint-redis。pip install langgraph-checkpoint-redis redis"
        ) from exc

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

    import redis

    client = redis.from_url(url)
    try:
        return RedisSaver(client, ttl=ttl)
    except TypeError:
        return RedisSaver(client)
