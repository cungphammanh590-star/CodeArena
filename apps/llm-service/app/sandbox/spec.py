"""Sandbox value types: limits, exec request/result."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
import shlex


@dataclass(frozen=True)
class ResourceLimits:
    timeout_s: int = 10
    memory_mb: int = 256
    max_output_chars: int = 8_000
    cpu_seconds: int = 10


@dataclass(frozen=True)
class ExecRequest:
    """Command to run inside the sandbox.

    Prefer ``argv`` (no shell). ``command`` is the shell-equivalent form.
    """

    command: str
    workdir: str = ""
    env: dict[str, str] = field(default_factory=dict)
    limits: ResourceLimits = field(default_factory=ResourceLimits)
    argv: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.argv and self.command != shlex.join(self.argv):
            raise ValueError(
                "ExecRequest.argv and .command disagree; use ExecRequest.of_argv()"
            )

    @classmethod
    def of_argv(cls, argv: Sequence[str], **kwargs: object) -> ExecRequest:
        items = tuple(str(item) for item in argv)
        if not items:
            raise ValueError("ExecRequest.of_argv needs at least one argument")
        return cls(command=shlex.join(items), argv=items, **kwargs)  # type: ignore[arg-type]


@dataclass(frozen=True)
class ExecResult:
    stdout: str = ""
    stderr: str = ""
    exit_code: int = 0
    timed_out: bool = False
    error: str = ""
    duration_ms: int = 0

    @property
    def ok(self) -> bool:
        return not self.error and not self.timed_out

    def render(self, max_chars: int) -> str:
        if self.error:
            return f"Error: {self.error}"
        parts: list[str] = []
        if self.stdout.strip():
            parts.append(self.stdout)
        if self.stderr.strip():
            parts.append(f"STDERR:\n{self.stderr}")
        if self.timed_out:
            parts.append("\n(command timed out)")
        parts.append(f"\nExit code: {self.exit_code}")
        text = "\n".join(parts) if parts else "(no output)"
        if len(text) > max_chars:
            half = max_chars // 2
            text = (
                text[:half]
                + f"\n\n... ({len(text) - max_chars:,} chars truncated) ...\n\n"
                + text[-half:]
            )
        return text


def truncate_preview(text: str, max_chars: int = 2_000) -> str:
    raw = text or ""
    if len(raw) <= max_chars:
        return raw
    half = max_chars // 2
    return raw[:half] + f"\n…(+{len(raw) - max_chars} chars)…\n" + raw[-half:]


__all__ = [
    "ExecRequest",
    "ExecResult",
    "ResourceLimits",
    "truncate_preview",
]
