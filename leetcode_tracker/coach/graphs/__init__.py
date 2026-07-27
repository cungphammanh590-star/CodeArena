"""LangGraph 双图入口。"""

from __future__ import annotations

import threading
from contextlib import contextmanager
from typing import Any, Iterator

from leetcode_tracker.coach.graphs.api import compile_api_graph
from leetcode_tracker.coach.graphs.common import close_checkpoint_graph
from leetcode_tracker.coach.graphs.local import compile_local_graph
from leetcode_tracker.llm.provider import get_llm_settings


@contextmanager
def graph_for_provider(
    cancel_event: threading.Event,
    *,
    session_id: str,
    thread_id: str,
    provider: str | None = None,
) -> Iterator[Any]:
    settings = get_llm_settings()
    use = (provider or settings.get("provider") or "ollama").strip().lower()
    if use == "api":
        graph = compile_api_graph(
            cancel_event, session_id=session_id, thread_id=thread_id
        )
    else:
        graph = compile_local_graph(
            cancel_event, session_id=session_id, thread_id=thread_id
        )
    try:
        yield graph
    finally:
        close_checkpoint_graph(graph)
