"""Per-user execution quotas (in-process)."""

from __future__ import annotations

from collections import defaultdict, deque
import threading
import time


class QuotaExceeded(Exception):
    """Raised when a user is over concurrency or rate quota."""


class UserExecQuota:
    def __init__(self, *, max_concurrent: int, max_per_minute: int) -> None:
        self._max_concurrent = max(1, max_concurrent)
        self._max_per_minute = max(1, max_per_minute)
        self._semaphores: dict[str, threading.Semaphore] = {}
        self._recent: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def _semaphore(self, user_id: str) -> threading.Semaphore:
        with self._lock:
            sem = self._semaphores.get(user_id)
            if sem is None:
                sem = threading.Semaphore(self._max_concurrent)
                self._semaphores[user_id] = sem
            return sem

    def _check_rate(self, user_id: str, now: float) -> None:
        window = self._recent[user_id]
        cutoff = now - 60.0
        while window and window[0] < cutoff:
            window.popleft()
        if len(window) >= self._max_per_minute:
            raise QuotaExceeded(
                f"execution rate limit reached ({self._max_per_minute}/min)"
            )
        window.append(now)

    class _Lease:
        def __init__(self, sem: threading.Semaphore) -> None:
            self._sem = sem

        def __enter__(self) -> UserExecQuota._Lease:
            return self

        def __exit__(self, *exc: object) -> None:
            self._sem.release()

    def acquire(self, user_id: str, *, now: float | None = None) -> _Lease:
        now = time.monotonic() if now is None else now
        sem = self._semaphore(user_id)
        with self._lock:
            # non-blocking probe: if already at capacity, refuse
            acquired = sem.acquire(blocking=False)
            if not acquired:
                raise QuotaExceeded(
                    f"too many concurrent executions (max {self._max_concurrent})"
                )
            try:
                self._check_rate(user_id, now)
            except QuotaExceeded:
                sem.release()
                raise
        return UserExecQuota._Lease(sem)


__all__ = ["QuotaExceeded", "UserExecQuota"]
