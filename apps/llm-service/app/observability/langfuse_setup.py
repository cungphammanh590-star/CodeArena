"""Langfuse tracing bootstrap for LangGraph / LangChain."""

from __future__ import annotations

import logging
import os
from typing import Any, Optional

logger = logging.getLogger(__name__)

_ENABLED = False


def configure_langfuse(
    *,
    enabled: bool,
    public_key: str,
    secret_key: str,
    host: str,
) -> bool:
    """Set Langfuse env. Returns whether tracing should be active."""
    global _ENABLED
    _ENABLED = False
    if not enabled:
        logger.info("Langfuse tracing disabled (LANGFUSE_TRACING=false)")
        return False
    pk = (public_key or "").strip()
    sk = (secret_key or "").strip()
    if not pk or not sk:
        logger.warning("Langfuse enabled but PUBLIC/SECRET key empty; tracing off")
        return False
    base = (host or "http://127.0.0.1:3030").strip().rstrip("/")
    os.environ["LANGFUSE_PUBLIC_KEY"] = pk
    os.environ["LANGFUSE_SECRET_KEY"] = sk
    os.environ["LANGFUSE_HOST"] = base
    os.environ["LANGFUSE_BASE_URL"] = base
    # 本机 Langfuse 勿走系统 HTTP(S)_PROXY（否则上报超时/失败、UI 空）
    for key in ("NO_PROXY", "no_proxy"):
        cur = (os.environ.get(key) or "").strip()
        extras = ["127.0.0.1", "localhost", "host.docker.internal"]
        parts = [p.strip() for p in cur.split(",") if p.strip()]
        for e in extras:
            if e not in parts:
                parts.append(e)
        os.environ[key] = ",".join(parts)
    _ENABLED = True
    logger.info("Langfuse tracing enabled host=%s", base)
    return True


def get_langfuse_handler() -> Optional[Any]:
    """LangChain CallbackHandler for graph.stream/invoke, or None."""
    if not _ENABLED:
        return None
    try:
        from langfuse.callback import CallbackHandler
    except ImportError:
        try:
            from langfuse.langchain import CallbackHandler  # type: ignore
        except ImportError:
            logger.warning("langfuse package missing; tracing off")
            return None
    try:
        # 显式传参，避免只靠环境变量时被代理/缓存干扰
        return CallbackHandler(
            public_key=os.environ.get("LANGFUSE_PUBLIC_KEY"),
            secret_key=os.environ.get("LANGFUSE_SECRET_KEY"),
            host=os.environ.get("LANGFUSE_HOST") or os.environ.get("LANGFUSE_BASE_URL"),
        )
    except TypeError:
        try:
            return CallbackHandler()
        except Exception as exc:  # noqa: BLE001
            logger.warning("Langfuse CallbackHandler init failed: %s", exc)
            return None
    except Exception as exc:  # noqa: BLE001
        logger.warning("Langfuse CallbackHandler init failed: %s", exc)
        return None


def flush_langfuse() -> None:
    if not _ENABLED:
        return
    try:
        from langfuse import get_client

        get_client().flush()
    except Exception:  # noqa: BLE001
        try:
            from langfuse import Langfuse

            Langfuse().flush()
        except Exception:  # noqa: BLE001
            pass
