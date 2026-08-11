"""Optional SkyWalking Python agent helpers for tool spans."""

from __future__ import annotations

import logging
from contextlib import contextmanager
from typing import Any, Iterator, Optional

logger = logging.getLogger(__name__)

_ACTIVE = False


def try_start_skywalking(*, service_name: str = "llm-service", collector: str = "127.0.0.1:11800") -> bool:
    """Start Java-compatible SW Python agent if enabled. Returns True when active."""
    global _ACTIVE
    if _ACTIVE:
        return True
    try:
        from skywalking import agent, config
    except ImportError:
        logger.warning("apache-skywalking not installed; SkyWalking disabled")
        return False
    try:
        config.init(
            agent_collector_backend_services=collector,
            agent_name=service_name,
            agent_protocol="grpc",
            agent_logging_level="WARN",
        )
        agent.start()
        _ACTIVE = True
        logger.info("SkyWalking Python agent started collector=%s", collector)
        return True
    except Exception as exc:  # noqa: BLE001
        logger.warning("SkyWalking Python agent failed to start: %s", exc)
        return False


@contextmanager
def tool_span(
    tool_name: str,
    *,
    session_id: str = "",
    peer: str = "business-service",
) -> Iterator[Optional[Any]]:
    """ExitSpan around Java tool HTTP; no-op when agent inactive."""
    if not _ACTIVE:
        yield None
        return
    try:
        from skywalking.trace.context import get_context
        from skywalking.trace.tags import Tag
    except ImportError:
        yield None
        return
    span = None
    try:
        ctx = get_context()
        span = ctx.new_exit_span(op=f"tool/{tool_name}", peer=peer)
        span.start()
        try:
            span.tag(Tag(key="tool.name", val=tool_name))
            if session_id:
                span.tag(Tag(key="session.id", val=session_id))
        except Exception:
            pass
        yield span
    except Exception as exc:  # noqa: BLE001
        logger.debug("tool_span open failed: %s", exc)
        yield None
    finally:
        if span is not None:
            try:
                span.stop()
            except Exception:  # noqa: BLE001
                pass
