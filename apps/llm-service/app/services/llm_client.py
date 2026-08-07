"""Async httpx client with connection pool for upstream LLM providers."""

from __future__ import annotations

from typing import Any, Optional

import httpx

from app.config import Settings, get_settings


class LLMClient:
    """Shared async HTTP client for Ollama / OpenAI-compatible APIs."""

    def __init__(self, settings: Optional[Settings] = None) -> None:
        self.settings = settings or get_settings()
        limits = httpx.Limits(
            max_connections=self.settings.llm_max_connections,
            max_keepalive_connections=max(4, self.settings.llm_max_connections // 2),
        )
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(self.settings.llm_timeout_seconds),
            limits=limits,
        )

    @property
    def client(self) -> httpx.AsyncClient:
        return self._client

    async def aclose(self) -> None:
        await self._client.aclose()

    async def chat(
        self,
        *,
        messages: list[dict[str, str]],
        model: Optional[str] = None,
    ) -> dict[str, Any]:
        """Call upstream chat API, or return a mock completion."""
        provider = (self.settings.llm_provider or "mock").lower()
        model_name = model or self.settings.llm_coach_model

        if provider == "mock":
            last_user = next(
                (m["content"] for m in reversed(messages) if m.get("role") == "user"),
                "",
            )
            return {
                "provider": "mock",
                "model": model_name,
                "content": f"[mock] received: {last_user[:200]}",
            }

        if provider == "ollama":
            url = f"{self.settings.llm_base_url.rstrip('/')}/api/chat"
            payload = {
                "model": model_name,
                "messages": messages,
                "stream": False,
            }
            resp = await self._client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            message = data.get("message") or {}
            return {
                "provider": "ollama",
                "model": model_name,
                "content": str(message.get("content") or ""),
                "raw": data,
            }

        # OpenAI-compatible (DeepSeek etc.)
        url = f"{self.settings.llm_base_url.rstrip('/')}/v1/chat/completions"
        headers: dict[str, str] = {"Content-Type": "application/json"}
        if self.settings.llm_api_key:
            headers["Authorization"] = f"Bearer {self.settings.llm_api_key}"
        payload = {"model": model_name, "messages": messages, "stream": False}
        resp = await self._client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()
        choices = data.get("choices") or []
        content = ""
        if choices:
            content = str((choices[0].get("message") or {}).get("content") or "")
        return {
            "provider": "api",
            "model": model_name,
            "content": content,
            "raw": data,
        }

    async def probe(self) -> dict[str, Any]:
        """Lightweight connectivity check against configured provider."""
        provider = (self.settings.llm_provider or "mock").lower()
        if provider == "mock":
            return {"ok": True, "provider": "mock", "latency_ms": 0}

        try:
            result = await self.chat(
                messages=[{"role": "user", "content": "ping"}],
            )
            return {
                "ok": True,
                "provider": result.get("provider"),
                "model": result.get("model"),
                "sample": str(result.get("content") or "")[:120],
            }
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "provider": provider, "error": str(exc)}


_llm_client: Optional[LLMClient] = None


def get_llm_client() -> LLMClient:
    global _llm_client
    if _llm_client is None:
        _llm_client = LLMClient()
    return _llm_client


async def shutdown_llm_client() -> None:
    global _llm_client
    if _llm_client is not None:
        await _llm_client.aclose()
        _llm_client = None
