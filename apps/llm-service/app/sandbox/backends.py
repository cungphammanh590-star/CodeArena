"""Sandbox backends (P0: RestrictedSubprocess)."""

from __future__ import annotations

import os
import resource
import shutil
import subprocess
import time
from typing import Protocol

from app.sandbox.spec import ExecRequest, ExecResult, truncate_preview


class SandboxBackend(Protocol):
    def exec(self, request: ExecRequest) -> ExecResult: ...


class RestrictedSubprocessBackend:
    """Plain subprocess with scrubbed env and confined cwd (APPLICATION isolation)."""

    _SAFE_ENV_KEYS = ("PATH", "LANG", "LC_ALL", "TMPDIR")

    def __init__(self, *, isolate_network: bool = False) -> None:
        self._isolate_network = isolate_network

    def exec(self, request: ExecRequest) -> ExecResult:
        env = {k: os.environ[k] for k in self._SAFE_ENV_KEYS if k in os.environ}
        env.update(request.env)
        cwd = request.workdir or None
        timeout = max(1, int(request.limits.timeout_s))
        max_chars = max(256, int(request.limits.max_output_chars))
        started = time.monotonic()
        try:
            if request.argv:
                argv = list(request.argv)
                if self._isolate_network:
                    unshare = shutil.which("unshare")
                    if not unshare:
                        return ExecResult(error="network-isolated sandbox unavailable")
                    argv = [unshare, "--net", "--", *argv]
                proc = subprocess.run(
                    argv,
                    capture_output=True,
                    text=True,
                    cwd=cwd,
                    env=env,
                    timeout=timeout,
                    check=False,
                    start_new_session=True,
                    preexec_fn=_limit_process(request.limits),
                )
            else:
                proc = subprocess.run(
                    request.command,
                    shell=True,
                    capture_output=True,
                    text=True,
                    cwd=cwd,
                    env=env,
                    timeout=timeout,
                    check=False,
                    start_new_session=True,
                    preexec_fn=_limit_process(request.limits),
                )
        except subprocess.TimeoutExpired as exc:
            out = (exc.stdout or "") if isinstance(exc.stdout, str) else ""
            err = (exc.stderr or "") if isinstance(exc.stderr, str) else ""
            return ExecResult(
                stdout=truncate_preview(out, max_chars),
                stderr=truncate_preview(err, max_chars),
                timed_out=True,
                exit_code=124,
                duration_ms=int((time.monotonic() - started) * 1000),
            )
        except Exception as exc:  # noqa: BLE001
            return ExecResult(
                error=f"{type(exc).__name__}: {exc}",
                duration_ms=int((time.monotonic() - started) * 1000),
            )
        return ExecResult(
            stdout=truncate_preview(proc.stdout or "", max_chars),
            stderr=truncate_preview(proc.stderr or "", max_chars),
            exit_code=int(proc.returncode if proc.returncode is not None else 0),
            duration_ms=int((time.monotonic() - started) * 1000),
        )


def _limit_process(limits):
    def apply() -> None:
        memory = max(32, int(limits.memory_mb)) * 1024 * 1024
        cpu = max(1, int(limits.cpu_seconds))
        _set_soft_limit(resource.RLIMIT_AS, memory)
        _set_soft_limit(resource.RLIMIT_CPU, cpu)
        _set_soft_limit(resource.RLIMIT_NPROC, 32)
        _set_soft_limit(resource.RLIMIT_FSIZE, 8 * 1024 * 1024)
    return apply


def _set_soft_limit(kind: int, requested: int) -> None:
    """Lower a supported soft limit without trying to raise the host hard limit."""
    try:
        _, hard = resource.getrlimit(kind)
        soft = requested if hard == resource.RLIM_INFINITY else min(requested, hard)
        resource.setrlimit(kind, (soft, hard))
    except (OSError, ValueError):
        # Some limits (notably NPROC/AS on macOS) are unavailable to an
        # unprivileged child. Other supported limits remain active.
        return


__all__ = ["RestrictedSubprocessBackend", "SandboxBackend"]
