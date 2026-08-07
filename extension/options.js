"use strict";

const usernameInput = document.getElementById("username");
const passwordInput = document.getElementById("password");
const authSummary = document.getElementById("auth-summary");
const statusEl = document.getElementById("status");

function setStatus(text, ok) {
  statusEl.textContent = text;
  statusEl.className = ok === true ? "ok" : ok === false ? "bad" : "muted";
}

async function refreshAuthSummary() {
  const cfg = await getConfig();
  if (cfg.accessToken && (await ensureAuth())) {
    const latest = await getConfig();
    authSummary.textContent = `已登录：${latest.userDisplay || latest.userPublicId}`;
  } else {
    authSummary.textContent = "尚未登录";
  }
}

async function openHomeAfterAuth() {
  const cfg = await getConfig();
  const base = (cfg.webBase || WEB_BASE).replace(/\/$/, "");
  // 把会话令牌交给 Web，实现扩展 ↔ 网页登录态同步
  const params = new URLSearchParams();
  if (cfg.accessToken) params.set("ext_token", cfg.accessToken);
  const q = params.toString();
  await chrome.tabs.create({ url: q ? `${base}/?${q}` : `${base}/` });
}

async function doLogin() {
  const username = usernameInput.value.trim();
  const password = passwordInput.value;
  if (!username || !password) {
    setStatus("请填写用户名和密码", false);
    return;
  }
  try {
    const data = await apiFetch("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password, client: "extension" }),
    });
    const token = data.access_token || "";
    const local = applyTokenLocally(token) || {};
    const user = data.user || {};
    await saveConfig({
      accessToken: token,
      userPublicId: user.public_id || local.userPublicId || "",
      userDisplay:
        user.display_name ||
        user.username ||
        local.userDisplay ||
        "",
    });
    setStatus("登录成功，正在打开首页…", true);
    passwordInput.value = "";
    await refreshAuthSummary();
    await openHomeAfterAuth();
  } catch (e) {
    setStatus(friendlyError(e), false);
    await refreshAuthSummary();
  }
}

async function doRegister() {
  const username = usernameInput.value.trim();
  const password = passwordInput.value;
  if (!username || !password) {
    setStatus("请填写用户名和密码", false);
    return;
  }
  try {
    const data = await apiFetch("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ username, password, display_name: username }),
    });
    const token = data.access_token || "";
    const local = applyTokenLocally(token) || {};
    const user = data.user || {};
    await saveConfig({
      accessToken: token,
      userPublicId: user.public_id || local.userPublicId || "",
      userDisplay:
        user.display_name ||
        user.username ||
        local.userDisplay ||
        "",
    });
    setStatus("注册成功，正在打开首页…", true);
    passwordInput.value = "";
    await refreshAuthSummary();
    await openHomeAfterAuth();
  } catch (e) {
    setStatus(friendlyError(e), false);
    await refreshAuthSummary();
  }
}

async function doLogout() {
  const cfg = await getConfig();
  if (cfg.accessToken) {
    try {
      await apiFetch("/api/auth/logout", { method: "POST" });
    } catch (_e) {
      /* ignore */
    }
  }
  await saveConfig({ accessToken: "", userPublicId: "", userDisplay: "" });
  setStatus("已退出登录", true);
  await refreshAuthSummary();
}

document.getElementById("login").addEventListener("click", doLogin);
document.getElementById("register").addEventListener("click", doRegister);
document.getElementById("logout").addEventListener("click", doLogout);
passwordInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") doLogin();
});

refreshAuthSummary();
