/**
 * 扩展共享配置。服务经 Gateway；鉴权为 JWT（Bearer）。
 */
"use strict";

const API_BASE = "http://127.0.0.1:8080";
const WEB_BASE = "http://127.0.0.1:5173";

const DEFAULT_API_BASE = API_BASE;
const DEFAULT_WEB_BASE = WEB_BASE;

const STORAGE_KEYS = {
  accessToken: "accessToken",
  userPublicId: "userPublicId",
  userDisplay: "userDisplay",
};

function parseJwtPayload(token) {
  try {
    if (!token || typeof token !== "string") return null;
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const pad = b64.length % 4 === 0 ? "" : "=".repeat(4 - (b64.length % 4));
    const json = atob(b64 + pad);
    return JSON.parse(json);
  } catch {
    return null;
  }
}

function userFromJwt(token) {
  const p = parseJwtPayload(token);
  if (!p) return null;
  const exp = Number(p.exp || 0);
  if (exp && exp * 1000 < Date.now()) return null;
  return {
    public_id: p.sub || "",
    username: p.username || "",
    display_name: p.display_name || p.username || "",
  };
}

async function getConfig() {
  const stored = await chrome.storage.local.get([
    STORAGE_KEYS.accessToken,
    STORAGE_KEYS.userPublicId,
    STORAGE_KEYS.userDisplay,
  ]);
  return {
    apiBase: API_BASE,
    webBase: WEB_BASE,
    accessToken: stored.accessToken || "",
    userPublicId: stored.userPublicId || "",
    userDisplay: stored.userDisplay || "",
  };
}

async function saveConfig(partial) {
  const payload = {};
  if (partial.accessToken != null) payload[STORAGE_KEYS.accessToken] = partial.accessToken;
  if (partial.userPublicId != null) payload[STORAGE_KEYS.userPublicId] = partial.userPublicId;
  if (partial.userDisplay != null) payload[STORAGE_KEYS.userDisplay] = partial.userDisplay;
  await chrome.storage.local.set(payload);
  try {
    chrome.runtime.sendMessage({ type: "auth_changed" }, () => {
      void chrome.runtime.lastError;
    });
  } catch (_e) {
    /* ignore */
  }
}

function applyTokenLocally(token) {
  const user = userFromJwt(token);
  if (!user) return null;
  return {
    accessToken: token,
    userPublicId: user.public_id,
    userDisplay: user.display_name || user.username || user.public_id,
  };
}

function friendlyError(err) {
  const status = err && err.status;
  const raw = String((err && err.message) || err || "").trim();
  const lower = raw.toLowerCase();
  if (status === 401) {
    if (/失效|请先登录|expired|invalid token|unauthorized/.test(lower) && !/password|密码/.test(lower)) {
      return "登录已失效，请重新登录";
    }
    return "用户名或密码不正确";
  }
  if (status === 409 || /taken|conflict|already/.test(lower)) {
    return "该用户名已被使用，换一个试试";
  }
  if (status === 400 && /password|密码/.test(lower)) {
    return "密码至少需要 6 位";
  }
  if (status === 400 && /username|用户名/.test(lower)) {
    return "用户名需为 3–32 位小写字母、数字或下划线";
  }
  if (status === 403) {
    if (!raw || /^forbidden$/i.test(raw) || /^http 403$/i.test(raw)) {
      return "暂时无法连接服务，请稍后重试；若刚更新扩展，请在 chrome://extensions 点重新加载";
    }
    return "当前账号无法使用此功能";
  }
  if (
    status === 0 ||
    /failed to fetch|networkerror|load failed|network/.test(lower)
  ) {
    return "网络异常，请稍后再试";
  }
  if (status >= 500 || /gateway|llm-service|python|flyway|sql|http \d+/.test(lower)) {
    return "服务暂时不可用，请稍后再试";
  }
  if (!raw || raw.length > 100) {
    return "操作失败，请稍后再试";
  }
  return raw;
}

async function authHeaders(extra = {}) {
  const cfg = await getConfig();
  const headers = { "Content-Type": "application/json", ...extra };
  if (cfg.accessToken) {
    headers.Authorization = `Bearer ${cfg.accessToken}`;
  }
  return headers;
}

async function apiFetch(path, options = {}) {
  const url = `${API_BASE}${path.startsWith("/") ? path : `/${path}`}`;
  const headers = await authHeaders(options.headers || {});
  let res;
  try {
    res = await fetch(url, { ...options, headers });
  } catch (e) {
    const err = new Error(friendlyError(e));
    err.status = 0;
    throw err;
  }
  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { message: text };
  }
  if (!res.ok) {
    const err = new Error(data.message || data.error || `HTTP ${res.status}`);
    err.status = res.status;
    err.data = data;
    err.message = friendlyError(err);
    throw err;
  }
  return data;
}

/** 校验 JWT：优先本地解析；在线时再打 /api/auth/me。 */
async function ensureAuth() {
  const cfg = await getConfig();
  if (!cfg.accessToken) return false;

  const local = applyTokenLocally(cfg.accessToken);
  if (!local) {
    await saveConfig({ accessToken: "", userPublicId: "", userDisplay: "" });
    return false;
  }
  // 先写入 JWT claims，保证弹窗立刻能显示用户
  if (
    local.userPublicId !== cfg.userPublicId ||
    local.userDisplay !== cfg.userDisplay
  ) {
    await saveConfig({
      userPublicId: local.userPublicId,
      userDisplay: local.userDisplay,
    });
  }

  try {
    const me = await apiFetch("/api/auth/me");
    const user = me.user || {};
    await saveConfig({
      userPublicId: user.public_id || local.userPublicId,
      userDisplay: user.display_name || user.username || local.userDisplay,
    });
    return true;
  } catch (e) {
    const status = e && e.status;
    if (status === 401 || status === 403) {
      await saveConfig({ accessToken: "", userPublicId: "", userDisplay: "" });
      return false;
    }
    // 网络异常：JWT 未过期则仍视为已登录
    return true;
  }
}

async function isLoggedIn() {
  const cfg = await getConfig();
  return Boolean(cfg.accessToken && applyTokenLocally(cfg.accessToken));
}
