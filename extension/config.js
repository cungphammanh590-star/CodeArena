/**
 * 扩展共享配置。服务地址由产品内置，不对用户开放配置。
 * 鉴权：服务端签发的 Bearer 会话令牌（ca_…），存 chrome.storage.local，全扩展共享。
 */
"use strict";

// 产品内置入口（发版时改这里；用户界面不可改）
const API_BASE = "http://127.0.0.1:8080";
const WEB_BASE = "http://127.0.0.1:5173";

// 兼容旧脚本引用
const DEFAULT_API_BASE = API_BASE;
const DEFAULT_WEB_BASE = WEB_BASE;

const STORAGE_KEYS = {
  accessToken: "accessToken",
  userPublicId: "userPublicId",
  userDisplay: "userDisplay",
};

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

function friendlyError(err) {
  const status = err && err.status;
  const raw = String((err && err.message) || err || "").trim();
  const lower = raw.toLowerCase();
  if (status === 401) {
    if (/失效|请先登录|expired|invalid token|unauthorized/.test(lower) && !/password|密码/.test(lower)) {
      return "登录已失效，请重新登录";
    }
    if (/用户名或密码|invalid credentials/.test(lower)) {
      return "用户名或密码不正确";
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

/**
 * 校验已有登录态。
 * - 无 token → false
 * - 401 → 清 token，false
 * - 网络/5xx → 保留 token，仍视为已登录（避免误清导致弹窗不同步）
 */
async function ensureAuth() {
  const cfg = await getConfig();
  if (!cfg.accessToken) return false;
  try {
    const me = await apiFetch("/api/auth/me");
    const user = me.user || {};
    await saveConfig({
      userPublicId: user.public_id || cfg.userPublicId,
      userDisplay: user.display_name || user.username || cfg.userDisplay,
    });
    return true;
  } catch (e) {
    const status = e && e.status;
    if (status === 401 || status === 403) {
      await saveConfig({ accessToken: "", userPublicId: "", userDisplay: "" });
      return false;
    }
    // 短暂网络失败：保留本地登录态，扩展仍可显示已登录
    return true;
  }
}

async function isLoggedIn() {
  const cfg = await getConfig();
  return Boolean(cfg.accessToken);
}
