"""Service configuration via environment variables."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# apps/llm-service/app/config.py → repo root
_REPO_ROOT = Path(__file__).resolve().parents[3]
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
    llm_provider: str = "mock"  # mock | ollama | api
    llm_base_url: str = "http://127.0.0.1:11434"
    llm_api_key: str = ""
    llm_coach_model: str = "qwen2.5:7b-instruct-q4_K_M"
    llm_timeout_seconds: float = 60.0
    llm_max_connections: int = 20

    # Infra (placeholders for future wiring)
    redis_url: str = "redis://127.0.0.1:6380/0"
    postgres_dsn: str = "postgresql://codearena:zephyr@127.0.0.1:5432/codearena"
    nacos_server_addr: str = "127.0.0.1:8848"

    # 编排器 → 执行官（business-service 内网工具）
    business_internal_url: str = "http://127.0.0.1:8090"
    internal_tool_token: str = "codearena-internal-dev"

    # L1 checkpoint：auto|redis|memory
    checkpoint_backend: str = "auto"
    checkpoint_ttl_seconds: int = 604800  # 7d

    # Code sandbox (P0)
    sandbox_backend: str = "subprocess"  # subprocess | off
    sandbox_timeout_s: int = 10
    sandbox_memory_mb: int = 256
    sandbox_max_output_chars: int = 8000
    sandbox_max_concurrent_per_user: int = 1
    sandbox_max_runs_per_minute: int = 10
    sandbox_data_dir: str = "/tmp/codearena-code-runs"

    # Observability
    observability_skywalking: bool = False
    skywalking_collector: str = "127.0.0.1:11800"
    skywalking_service_name: str = "llm-service"
    langfuse_tracing: bool = False
    langfuse_public_key: str = ""
    langfuse_secret_key: str = ""
    langfuse_host: str = "http://127.0.0.1:3030"
    log_json: bool = True


@lru_cache
def get_settings() -> Settings:
    return Settings()
