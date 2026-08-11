"""Record per-user LLM token usage into business-service (in-app dashboard)."""

from __future__ import annotations

import logging
import threading
from typing import Any, Optional

import httpx

from app.config import get_settings
from app.observability.request_context import get_request_id

logger = logging.getLogger(__name__)


class UsageCollector:
    """Thread-local accumulator for one coach turn."""

    def __init__(self) -> None:
        self.prompt_tokens = 0
        self.completion_tokens = 0
        self.total_tokens = 0
        self.calls = 0
        self.model = ""
        self.success = True
        self.error_code = ""

    def add(
        self,
        *,
        prompt: int = 0,
        completion: int = 0,
        total: int = 0,
        model: str = "",
        success: bool = True,
        error_code: str = "",
    ) -> None:
        self.prompt_tokens += max(0, int(prompt or 0))
        self.completion_tokens += max(0, int(completion or 0))
        added_total = int(total or 0)
        if added_total <= 0:
            added_total = max(0, int(prompt or 0)) + max(0, int(completion or 0))
        self.total_tokens += max(0, added_total)
        self.calls += 1
        if model:
            self.model = model
        if not success:
            self.success = False
            if error_code:
                self.error_code = error_code


_local = threading.local()


def begin_usage_turn() -> UsageCollector:
    c = UsageCollector()
    _local.collector = c
    return c


def current_usage() -> Optional[UsageCollector]:
    return getattr(_local, "collector", None)


def end_usage_turn() -> Optional[UsageCollector]:
    c = getattr(_local, "collector", None)
    _local.collector = None
    return c


def make_usage_callback() -> Any:
    """LangChain callback that fills UsageCollector."""
    try:
        from langchain_core.callbacks import BaseCallbackHandler
    except ImportError:
        return None

    class _Handler(BaseCallbackHandler):
        def on_llm_end(self, response: Any, **kwargs: Any) -> None:  # noqa: ANN401
            c = current_usage()
            if c is None:
                return
            prompt = completion = total = 0
            model = ""
            try:
                gens = getattr(response, "generations", None) or []
                if gens and gens[0]:
                    msg = getattr(gens[0][0], "message", None)
                    meta = getattr(msg, "usage_metadata", None) or {}
                    if isinstance(meta, dict):
                        prompt = int(meta.get("input_tokens") or meta.get("prompt_tokens") or 0)
                        completion = int(
                            meta.get("output_tokens") or meta.get("completion_tokens") or 0
                        )
                        total = int(meta.get("total_tokens") or 0)
                llm_out = getattr(response, "llm_output", None) or {}
                if isinstance(llm_out, dict):
                    model = str(llm_out.get("model_name") or llm_out.get("model") or "")
                    usage = llm_out.get("token_usage") or llm_out.get("usage") or {}
                    if isinstance(usage, dict) and total <= 0:
                        prompt = int(usage.get("prompt_tokens") or prompt or 0)
                        completion = int(usage.get("completion_tokens") or completion or 0)
                        total = int(usage.get("total_tokens") or 0)
            except Exception:  # noqa: BLE001
                pass
            c.add(prompt=prompt, completion=completion, total=total, model=model, success=True)

        def on_llm_error(self, error: BaseException, **kwargs: Any) -> None:  # noqa: ANN401
            c = current_usage()
            if c is None:
                return
            c.add(success=False, error_code=type(error).__name__[:64])

    return _Handler()


def flush_usage_to_business(
    *,
    user_public_id: str,
    session_id: str,
    provider: str,
    api_provider: str,
    model: str,
) -> None:
    c = end_usage_turn()
    if c is None:
        return
    if c.calls <= 0 and c.total_tokens <= 0 and c.success:
        return
    cfg = get_settings()
    payload = {
        "session_id": session_id or "",
        "request_id": get_request_id() or "",
        "provider": provider or "",
        "api_provider": api_provider or "",
        "model": (c.model or model) or "",
        "prompt_tokens": c.prompt_tokens,
        "completion_tokens": c.completion_tokens,
        "total_tokens": c.total_tokens,
        "success": bool(c.success),
        "error_code": c.error_code or "",
    }
    url = f"{cfg.business_internal_url.rstrip('/')}/internal/llm/usage"
    headers = {
        "X-Internal-Token": cfg.internal_tool_token,
        "X-User-Public-Id": user_public_id or "",
        "Content-Type": "application/json",
    }
    try:
        with httpx.Client(timeout=5.0, trust_env=False) as client:
            resp = client.post(url, headers=headers, json=payload)
            if resp.status_code >= 400:
                logger.warning("llm usage record failed status=%s", resp.status_code)
    except Exception as exc:  # noqa: BLE001
        logger.warning("llm usage record error: %s", exc)
