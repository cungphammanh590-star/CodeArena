"use strict";

/**
 * Web（localhost:5173）登录后把 JWT 同步进扩展 storage。
 * 页面通过 postMessage({ source:'codearena', type:'auth_sync', token, user }) 通知。
 */
window.addEventListener("message", (event) => {
  if (event.source !== window) return;
  const data = event.data;
  if (!data || data.source !== "codearena") return;
  if (data.type === "auth_sync" && data.token) {
    chrome.runtime.sendMessage(
      {
        type: "sync_web_auth",
        token: data.token,
        user: data.user || null,
      },
      () => {
        void chrome.runtime.lastError;
      }
    );
  }
  if (data.type === "auth_clear") {
    chrome.runtime.sendMessage({ type: "sync_web_auth", token: "" }, () => {
      void chrome.runtime.lastError;
    });
  }
});

// 主动拉取：页面已有 token 时启动即同步
try {
  chrome.runtime.sendMessage({ type: "request_web_auth_pull" }, () => {
    void chrome.runtime.lastError;
  });
} catch (_e) {
  /* ignore */
}
