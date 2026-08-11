"""按用户 LLM 配置构建 LangChain Chat 模型。"""

from __future__ import annotations

from typing import Any, Optional

import httpx

from app.config import Settings, get_settings

DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEEPSEEK_DEFAULT_MODEL = "deepseek-chat"
OLLAMA_BASE_URL = "http://127.0.0.1:11434"
API_TIMEOUT_SECONDS = 45.0
# 拉用户配置是轻量内网 GET，不应跟 LLM 推理共用 60s 超时
SETTINGS_FETCH_TIMEOUT_SECONDS = 8.0


def fetch_user_llm_settings(
    *,
    user_public_id: str,
    settings: Optional[Settings] = None,
) -> dict[str, Any]:
    """从 business-service 内网接口拉取该用户的 LLM 配置（含 api_key）。"""
    cfg = settings or get_settings()
    url = f"{cfg.business_internal_url.rstrip('/')}/internal/users/llm"
    headers = {
        "X-Internal-Token": cfg.internal_tool_token,
        "X-User-Public-Id": user_public_id or "",
    }
    try:
        # trust_env=False：避免本机 HTTP(S)_PROXY/ALL_PROXY 把 127.0.0.1 打进坏代理
        with httpx.Client(
            timeout=SETTINGS_FETCH_TIMEOUT_SECONDS,
            trust_env=False,
        ) as client:
            resp = client.get(url, headers=headers)
            resp.raise_for_status()
            data = resp.json()
    except httpx.TimeoutException as exc:
        raise RuntimeError(
            "暂时读不到你的模型配置，请稍后再试。"
            "若刚改过设置，可到维护台确认后重试。"
        ) from exc
    except httpx.ConnectError as exc:
        raise RuntimeError(
            "暂时连不上配置服务，请稍后再试。"
            "若问题持续，请确认本机服务已启动。"
        ) from exc
    except httpx.HTTPStatusError as exc:
        raise RuntimeError(
            "暂时读不到你的模型配置，请稍后再试。"
        ) from exc
    except httpx.HTTPError as exc:
        raise RuntimeError(
            "暂时读不到你的模型配置，请稍后再试。"
        ) from exc
    llm = data.get("llm") if isinstance(data, dict) else None
    if not isinstance(llm, dict):
        return {
            "provider": cfg.llm_provider,
            "api_key": cfg.llm_api_key,
            "base_url": cfg.llm_base_url,
            "coach_model": cfg.llm_coach_model,
            "api_provider": "deepseek" if cfg.llm_provider == "api" else "",
        }
    return {
        "provider": str(llm.get("provider") or "ollama").strip().lower(),
        "api_provider": str(llm.get("api_provider") or "").strip().lower(),
        "api_key": str(llm.get("api_key") or "").strip(),
        "base_url": str(llm.get("base_url") or "").strip(),
        "coach_model": str(llm.get("coach_model") or "").strip(),
        "has_api_key": bool(llm.get("has_api_key")),
        "user_public_id": str(llm.get("user_public_id") or user_public_id or ""),
    }


def build_chat_model(llm: dict[str, Any]):
    provider = str(llm.get("provider") or "ollama").strip().lower()
    if provider == "mock":
        raise RuntimeError("当前是演示模式，无法驱动智能教练。请到维护台选择本地模型或云端 API。")
    if provider == "ollama":
        return _build_ollama(llm)
    if provider == "api":
        return _build_api(llm)
    raise RuntimeError("模型配置无效。请到维护台选择本地模型或云端 API。")


def _build_ollama(llm: dict[str, Any]):
    try:
        from langchain_ollama import ChatOllama
    except ImportError as exc:
        raise RuntimeError(
            "本地模型组件未安装完成，暂时无法使用陪练。请联系管理员或稍后再试。"
        ) from exc
    model = str(llm.get("coach_model") or "").strip() or "qwen2.5:7b-instruct-q4_K_M"
    base = str(llm.get("base_url") or "").strip() or OLLAMA_BASE_URL
    return ChatOllama(
        model=model,
        temperature=0.4,
        base_url=base,
        client_kwargs={"timeout": API_TIMEOUT_SECONDS, "trust_env": False},
        async_client_kwargs={"timeout": API_TIMEOUT_SECONDS, "trust_env": False},
    )


def _build_api(llm: dict[str, Any]):
    api_provider = str(llm.get("api_provider") or "deepseek").strip().lower() or "deepseek"
    if api_provider != "deepseek":
        raise RuntimeError("暂仅支持 DeepSeek 云端 API，请到维护台调整配置。")
    api_key = str(llm.get("api_key") or "").strip()
    if not api_key:
        raise RuntimeError("未配置 API Key。请到维护台为当前用户填写后重试。")
    try:
        from langchain_openai import ChatOpenAI
    except ImportError as exc:
        raise RuntimeError(
            "云端模型组件未安装完成，暂时无法使用陪练。请联系管理员或稍后再试。"
        ) from exc
    base_url = str(llm.get("base_url") or "").strip() or DEEPSEEK_BASE_URL
    model = str(llm.get("coach_model") or "").strip() or DEEPSEEK_DEFAULT_MODEL
    return ChatOpenAI(
        model=model,
        api_key=api_key,
        base_url=base_url,
        temperature=0.4,
        timeout=API_TIMEOUT_SECONDS,
        max_retries=1,
    )
