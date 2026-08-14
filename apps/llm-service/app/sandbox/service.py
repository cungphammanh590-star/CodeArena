"""Sandbox service facade + settings."""

from __future__ import annotations

from dataclasses import dataclass
import logging
from pathlib import Path

from app.config import Settings, get_settings
from app.sandbox.backends import RestrictedSubprocessBackend, SandboxBackend
from app.sandbox.quota import QuotaExceeded, UserExecQuota
from app.sandbox.spec import ExecRequest, ExecResult

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class SandboxSettings:
    backend: str = "subprocess"  # subprocess | off
    timeout_s: int = 10
    memory_mb: int = 256
    max_output_chars: int = 8_000
    max_concurrent_per_user: int = 1
    max_runs_per_minute_per_user: int = 10
    data_dir: str = ""

    @classmethod
    def from_app(cls, settings: Settings | None = None) -> SandboxSettings:
        s = settings or get_settings()
        return cls(
            backend=str(s.sandbox_backend or "subprocess").strip().lower(),
            timeout_s=int(s.sandbox_timeout_s),
            memory_mb=int(s.sandbox_memory_mb),
            max_output_chars=int(s.sandbox_max_output_chars),
            max_concurrent_per_user=int(s.sandbox_max_concurrent_per_user),
            max_runs_per_minute_per_user=int(s.sandbox_max_runs_per_minute),
            data_dir=str(s.sandbox_data_dir or "").strip(),
        )


def build_backend(settings: SandboxSettings) -> SandboxBackend | None:
    if settings.backend in {"", "off", "none", "disabled"}:
        return None
    return RestrictedSubprocessBackend(isolate_network=settings.backend in {"unshare", "isolated"})


class SandboxService:
    def __init__(
        self,
        settings: SandboxSettings | None = None,
        *,
        backend: SandboxBackend | None = None,
    ) -> None:
        self._settings = settings or SandboxSettings.from_app()
        self._backend: SandboxBackend | None = (
            backend if backend is not None else build_backend(self._settings)
        )
        self._quota = UserExecQuota(
            max_concurrent=self._settings.max_concurrent_per_user,
            max_per_minute=self._settings.max_runs_per_minute_per_user,
        )

    @property
    def settings(self) -> SandboxSettings:
        return self._settings

    @property
    def available(self) -> bool:
        return self._backend is not None

    def run_dir(self, *, user_id: str, session_id: str, run_id: str) -> Path:
        root = Path(self._settings.data_dir or "/tmp/codearena-code-runs")
        path = root / _safe_segment(user_id) / _safe_segment(session_id) / _safe_segment(run_id)
        path.mkdir(parents=True, exist_ok=True)
        return path

    def run(self, request: ExecRequest, *, user_id: str) -> ExecResult:
        if self._backend is None:
            return ExecResult(error="sandbox backend unavailable")
        try:
            lease = self._quota.acquire(user_id or "anon")
        except QuotaExceeded as exc:
            return ExecResult(error=str(exc))
        with lease:
            result = self._backend.exec(request)
            logger.info("sandbox_run user=%s duration_ms=%s exit_code=%s timed_out=%s ok=%s", user_id or "anon", result.duration_ms, result.exit_code, result.timed_out, result.ok)
            return result


def _safe_segment(value: str) -> str:
    raw = (value or "anon").strip() or "anon"
    return "".join(c if c.isalnum() or c in "-_." else "_" for c in raw)[:80]


_service: SandboxService | None = None


def get_sandbox_service() -> SandboxService:
    global _service
    if _service is None:
        _service = SandboxService()
    return _service


def reset_sandbox_service(service: SandboxService | None = None) -> None:
    global _service
    _service = service


__all__ = [
    "SandboxService",
    "SandboxSettings",
    "build_backend",
    "get_sandbox_service",
    "reset_sandbox_service",
]
