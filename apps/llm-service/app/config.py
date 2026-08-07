"""Service configuration via environment variables."""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
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
    redis_url: str = "redis://127.0.0.1:6379/0"
    postgres_dsn: str = "postgresql://codearena:zephyr@127.0.0.1:5432/codearena"
    nacos_server_addr: str = "127.0.0.1:8848"

    # 编排器 → 执行官（business-service 内网工具）
    business_internal_url: str = "http://127.0.0.1:8090"
    internal_tool_token: str = "codearena-internal-dev"

    # L1 checkpoint：auto|redis|memory
    checkpoint_backend: str = "auto"
    checkpoint_ttl_seconds: int = 604800  # 7d


@lru_cache
def get_settings() -> Settings:
    return Settings()
