"""本机 JSON 配置（不含数据库路径）。"""

from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path
from typing import Any

LLM_DEFAULTS: dict[str, Any] = {
    "provider": "ollama",  # ollama | api
    "coach_model": "qwen2.5:7b-instruct-q4_K_M",
    "api_provider": "",  # api 模式下：deepseek（当前仅此）
    "api_key": "",
    "base_url": "",  # 可选；DeepSeek 默认 https://api.deepseek.com
}

LEARNING_DEFAULTS: dict[str, Any] = {
    "list_mode": True,
    "kg_mode": True,
    "active_list_id": "hot100",
}

DEFAULTS: dict[str, Any] = {
    "host": "127.0.0.1",
    "port": 8763,
    "autostart": False,
    "llm": deepcopy(LLM_DEFAULTS),
    "learning": deepcopy(LEARNING_DEFAULTS),
}

CONFIG_KEYS = sorted(
    {
        "host",
        "port",
        "autostart",
        "llm.provider",
        "llm.coach_model",
        "llm.api_provider",
        "llm.api_key",
        "llm.base_url",
        "learning.list_mode",
        "learning.kg_mode",
        "learning.active_list_id",
    }
)


def config_dir() -> Path:
    return Path.home() / ".config" / "leetcode-tracker"


def config_path() -> Path:
    return config_dir() / "config.json"


def _merge_llm(target: dict[str, Any], raw: Any) -> None:
    if not isinstance(raw, dict):
        return
    for key in LLM_DEFAULTS:
        if key in raw:
            target[key] = raw[key]


def _merge_learning(target: dict[str, Any], raw: Any) -> None:
    if not isinstance(raw, dict):
        return
    for key in LEARNING_DEFAULTS:
        if key in raw:
            target[key] = raw[key]


def _normalize_config(data: dict[str, Any]) -> dict[str, Any]:
    data["port"] = int(data["port"])
    data["autostart"] = bool(data["autostart"])
    data["host"] = str(data["host"])
    # 忽略旧版 report_dir / report_time
    data.pop("report_dir", None)
    data.pop("report_time", None)
    llm = data.get("llm")
    if not isinstance(llm, dict):
        data["llm"] = deepcopy(LLM_DEFAULTS)
    else:
        merged = deepcopy(LLM_DEFAULTS)
        _merge_llm(merged, llm)
        data["llm"] = merged
    data["llm"]["provider"] = str(data["llm"]["provider"]).strip().lower() or "ollama"
    if data["llm"]["provider"] not in {"ollama", "api"}:
        data["llm"]["provider"] = "ollama"
    data["llm"]["coach_model"] = str(data["llm"]["coach_model"])
    data["llm"]["api_provider"] = str(data["llm"]["api_provider"]).strip().lower()
    data["llm"]["api_key"] = str(data["llm"]["api_key"])
    data["llm"]["base_url"] = str(data["llm"].get("base_url") or "")
    if data["llm"]["provider"] == "api" and not data["llm"]["api_provider"]:
        data["llm"]["api_provider"] = "deepseek"

    learning = data.get("learning")
    if not isinstance(learning, dict):
        data["learning"] = deepcopy(LEARNING_DEFAULTS)
    else:
        merged_l = deepcopy(LEARNING_DEFAULTS)
        _merge_learning(merged_l, learning)
        data["learning"] = merged_l
    data["learning"]["list_mode"] = bool(data["learning"]["list_mode"])
    data["learning"]["kg_mode"] = bool(data["learning"]["kg_mode"])
    active = str(data["learning"].get("active_list_id") or "hot100").strip()
    data["learning"]["active_list_id"] = active or "hot100"
    return data


def load_config() -> dict[str, Any]:
    path = config_path()
    data = deepcopy(DEFAULTS)
    if path.is_file():
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                for key in ("host", "port", "autostart"):
                    if key in raw:
                        data[key] = raw[key]
                if "llm" in raw:
                    _merge_llm(data["llm"], raw["llm"])
                if "learning" in raw:
                    _merge_learning(data["learning"], raw["learning"])
        except (json.JSONDecodeError, OSError):
            pass
    return _normalize_config(data)


def save_config(data: dict[str, Any]) -> Path:
    merged = _normalize_config(deepcopy(data))
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return path


def _set_nested(cfg: dict[str, Any], key: str, value: str) -> None:
    if key == "db_path":
        raise ValueError(f"不支持的配置项: {key}")
    if key.startswith("llm."):
        sub = key.split(".", 1)[1]
        if sub not in LLM_DEFAULTS:
            raise ValueError(f"不支持的配置项: {key}")
        cfg.setdefault("llm", deepcopy(LLM_DEFAULTS))
        cfg["llm"][sub] = value
        return
    if key.startswith("learning."):
        sub = key.split(".", 1)[1]
        if sub not in LEARNING_DEFAULTS:
            raise ValueError(f"不支持的配置项: {key}")
        cfg.setdefault("learning", deepcopy(LEARNING_DEFAULTS))
        if sub in {"list_mode", "kg_mode"}:
            cfg["learning"][sub] = value.strip().lower() in {"1", "true", "yes", "on"}
        else:
            cfg["learning"][sub] = value
        return
    if key not in DEFAULTS or key in {"llm", "learning"}:
        raise ValueError(f"不支持的配置项: {key}")
    if key == "port":
        cfg[key] = int(value)
    elif key == "autostart":
        cfg[key] = value.strip().lower() in {"1", "true", "yes", "on"}
    else:
        cfg[key] = value


def update_learning_config(
    *,
    list_mode: bool | None = None,
    kg_mode: bool | None = None,
    active_list_id: str | None = None,
) -> dict[str, Any]:
    cfg = load_config()
    learning = cfg.setdefault("learning", deepcopy(LEARNING_DEFAULTS))
    if list_mode is not None:
        learning["list_mode"] = bool(list_mode)
    if kg_mode is not None:
        learning["kg_mode"] = bool(kg_mode)
    if active_list_id is not None:
        aid = str(active_list_id).strip() or "hot100"
        learning["active_list_id"] = aid
    save_config(cfg)
    return load_config()


def get_learning_config() -> dict[str, Any]:
    return dict(load_config().get("learning") or LEARNING_DEFAULTS)


def set_config_value(key: str, value: str) -> dict[str, Any]:
    cfg = load_config()
    _set_nested(cfg, key, value)
    save_config(cfg)
    return load_config()


def update_llm_config(
    *,
    provider: str | None = None,
    api_provider: str | None = None,
    coach_model: str | None = None,
    api_key: str | None = None,
    base_url: str | None = None,
    clear_api_key: bool = False,
) -> dict[str, Any]:
    """更新 llm 配置。api_key=None 表示不改动；clear_api_key 清空密钥。"""
    cfg = load_config()
    llm = cfg.setdefault("llm", deepcopy(LLM_DEFAULTS))
    if provider is not None:
        llm["provider"] = provider
    if api_provider is not None:
        llm["api_provider"] = api_provider
    if coach_model is not None:
        llm["coach_model"] = coach_model
    if base_url is not None:
        llm["base_url"] = base_url
    if clear_api_key:
        llm["api_key"] = ""
    elif api_key is not None and str(api_key).strip():
        # 空字符串表示「未填写」，保留原 key
        llm["api_key"] = str(api_key).strip()
    save_config(cfg)
    return load_config()


def clear_llm_api_key(*, switch_to_ollama: bool = True) -> dict[str, Any]:
    cfg = load_config()
    llm = cfg.setdefault("llm", deepcopy(LLM_DEFAULTS))
    llm["api_key"] = ""
    if switch_to_ollama:
        llm["provider"] = "ollama"
        llm["api_provider"] = ""
        model = str(llm.get("coach_model") or "")
        if model.startswith("deepseek") or not model:
            llm["coach_model"] = LLM_DEFAULTS["coach_model"]
    save_config(cfg)
    return load_config()


def switch_to_ollama_keep_key() -> dict[str, Any]:
    """云端不可达时切回本地：保留 API Key，恢复默认本地模型名。"""
    cfg = load_config()
    llm = cfg.setdefault("llm", deepcopy(LLM_DEFAULTS))
    llm["provider"] = "ollama"
    llm["api_provider"] = ""
    model = str(llm.get("coach_model") or "")
    if model.startswith("deepseek") or not model:
        llm["coach_model"] = LLM_DEFAULTS["coach_model"]
    save_config(cfg)
    return load_config()


def mask_config_for_display(cfg: dict[str, Any]) -> dict[str, Any]:
    out = deepcopy(cfg)
    llm = out.setdefault("llm", {})
    api_key = str(llm.get("api_key") or "")
    llm["has_api_key"] = bool(api_key)
    if api_key:
        llm["api_key"] = "***" + api_key[-4:] if len(api_key) > 4 else "***"
    else:
        llm["api_key"] = ""
    return out
