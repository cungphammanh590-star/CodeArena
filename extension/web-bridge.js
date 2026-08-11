"use strict";

/**
 * Web（127.0.0.1:5173 / localhost:5173）登录态 → 扩展 storage。
 * 1) 读页面同源 localStorage（不依赖 postMessage 时序）
 * 2) 监听页面 postMessage({ source:'codearena', type:'auth_sync' })
 */
const PAGE_TOKEN_KEY = "codearena_access_token";
const PAGE_UID_KEY = "codearena_user_public_id";

function pushTokenToExtension(token, user) {
  chrome.runtime.sendMessage(
    {
      type: "sync_web_auth",
      token: token || "",
      user: user || null,
    },
    () => {
      void chrome.runtime.lastError;
    }
  );
}

function syncFromPageStorage() {
  try {
    const token = localStorage.getItem(PAGE_TOKEN_KEY) || "";
    if (!token) {
      // 页面已退出时，不主动清扩展（避免误伤仅在扩展登录的场景）
      return;
    }
    const publicId = localStorage.getItem(PAGE_UID_KEY) || "";
    pushTokenToExtension(token, publicId ? { public_id: publicId } : null);
  } catch (_e) {
    /* ignore */
  }
}

window.addEventListener("message", (event) => {
  if (event.source !== window) return;
  const data = event.data;
  if (!data || data.source !== "codearena") return;
  if (data.type === "auth_sync" && data.token) {
    pushTokenToExtension(data.token, data.user || null);
  }
  if (data.type === "auth_clear") {
    pushTokenToExtension("", null);
  }
});

// 启动即拉一次；再延迟一次覆盖 Vue boot 写入 token 的竞态
syncFromPageStorage();
setTimeout(syncFromPageStorage, 400);
setTimeout(syncFromPageStorage, 1500);

window.addEventListener("storage", (event) => {
  if (event.key === PAGE_TOKEN_KEY || event.key === PAGE_UID_KEY) {
    syncFromPageStorage();
  }
});
