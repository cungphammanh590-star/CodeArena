"""Service configuration via environment variables."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# apps/llm-service/app/config.py → repo root.  The container copies this
# package to /app/app, where the repository-level parent does not exist.
_CONFIG_PATH = Path(__file__).resolve()
_REPO_ROOT = _CONFIG_PATH.parents[3] if len(_CONFIG_PATH.parents) > 3 else Path.cwd()
_ENV_CANDIDATES = (
    _REPO_ROOT / ".env",
    Path(".env"),
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=tuple(str(p) for p in _ENV_CANDIDATES if p.is_file()) or (".env",),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "CodeArena LLM Service"
    host: str = "0.0.0.0"
    port: int = 8091
    log_level: str = "info"

    # Upstream LLM provider (optional; used by llm_client)
    llm_provider: str = "api"
    llm_base_url: str = "https://api.deepseek.com"
    llm_api_key: str = ""
    llm_coach_model: str = "deepseek-chat"
    llm_timeout_seconds: float = 60.0
    llm_max_connections: int = 20

    # Infra (placeholders for future wiring)
    redis_url: str = "redis://127.0.0.1:6380/0"
    postgres_dsn: str = "postgresql://codearena:zephyr@127.0.0.1:5432/codearena"
    # 编排器 → 执行官（business-service 内网工具）
    business_grpc_target: str = "127.0.0.1:9092"
    llm_grpc_host: str = "127.0.0.1"
    llm_grpc_port: int = 9093
    internal_tool_token: str = "codearena-internal-dev"

    # L1 checkpoint：auto|redis|memory
    checkpoint_backend: str = "auto"
    checkpoint_ttl_seconds: int = 604800  # 7d

    # Code sandbox (P0)
    sandbox_backend: str = "subprocess"  # subprocess | unshare | bwrap (Linux) | off
    sandbox_timeout_s: int = 10
    sandbox_memory_mb: int = 256
    sandbox_max_output_chars: int = 8000
    sandbox_max_concurrent_per_user: int = 1
    sandbox_max_runs_per_minute: int = 10
    sandbox_data_dir: str = "/tmp/codearena-code-runs"

    # Observability
    log_json: bool = True


@lru_cache
def get_settings() -> Settings:
    return Settings()
