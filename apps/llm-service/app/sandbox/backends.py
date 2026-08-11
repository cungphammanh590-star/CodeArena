"""Sandbox backends (P0: RestrictedSubprocess)."""

from __future__ import annotations

import os
import subprocess
import time
from typing import Protocol

from app.sandbox.spec import ExecRequest, ExecResult, truncate_preview


class SandboxBackend(Protocol):
    def exec(self, request: ExecRequest) -> ExecResult: ...


class RestrictedSubprocessBackend:
    """Plain subprocess with scrubbed env and confined cwd (APPLICATION isolation)."""

    _SAFE_ENV_KEYS = ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR")

    def exec(self, request: ExecRequest) -> ExecResult:
        env = {k: os.environ[k] for k in self._SAFE_ENV_KEYS if k in os.environ}
        env.update(request.env)
        cwd = request.workdir or None
        timeout = max(1, int(request.limits.timeout_s))
        max_chars = max(256, int(request.limits.max_output_chars))
        started = time.monotonic()
        try:
            if request.argv:
                proc = subprocess.run(
                    list(request.argv),
                    capture_output=True,
                    text=True,
                    cwd=cwd,
                    env=env,
                    timeout=timeout,
                    check=False,
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


__all__ = ["RestrictedSubprocessBackend", "SandboxBackend"]
