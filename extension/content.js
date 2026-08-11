(function () {
  const EXT_CONTENT_VER = "0.5.3";
  console.info("[leetcode-tracker] content ready", EXT_CONTENT_VER);

  const recentRelay = new Map();
  let orphanWarned = false;
  let lastContextKey = "";
  let lastContextAt = 0;

  function runtimeAlive() {
    try {
      return Boolean(chrome?.runtime?.id);
    } catch {
      return false;
    }
  }

  function warnOrphanOnce() {
    if (orphanWarned) return;
    orphanWarned = true;
    console.warn(
      "[leetcode-tracker] 扩展上下文已失效（通常是刚点了「重新加载」）。请刷新本力扣页面后再提交。"
    );
  }

  function relay(data) {
    if (!runtimeAlive()) {
      warnOrphanOnce();
      return false;
    }
    try {
      chrome.runtime.sendMessage(data, (response) => {
        if (!runtimeAlive()) {
          warnOrphanOnce();
          return;
        }
        if (chrome.runtime.lastError) {
          const msg = chrome.runtime.lastError.message || "";
          if (/extension context invalidated|message port closed/i.test(msg)) {
            warnOrphanOnce();
            return;
          }
          console.warn("[leetcode-tracker] relay lastError:", msg);
          return;
        }
        if (response && response.ok === false) {
          console.warn(
            "[leetcode-tracker] relay nack:",
            response.error || response
          );
          return;
        }
        if (response && response.ok) {
          console.info("[leetcode-tracker] relay ack", {
            submission_id: data?.payload?.submission_id,
          });
        }
      });
      return true;
    } catch (err) {
      const msg = String(err || "");
      if (/extension context invalidated|Cannot read properties of undefined/i.test(msg)) {
        warnOrphanOnce();
        return false;
      }
      console.warn("[leetcode-tracker] relay exception:", err);
      return false;
    }
  }

  window.addEventListener("message", (event) => {
    if (event.source !== window) return;
    const data = event.data;
    if (!data || data.source !== "leetcode-tracker") return;

    if (data.type === "submission" && data.payload) {
      const sid = String(data.payload.submission_id || "");
      const now = Date.now();
      if (sid && now - (recentRelay.get(sid) || 0) < 8000) return;
      if (sid) recentRelay.set(sid, now);
      console.info("[leetcode-tracker] content relay submission", {
        submission_id: data.payload.submission_id,
        problem_id: data.payload.problem_id,
      });
      const ok = relay({ type: "submission", payload: data.payload });
      if (!ok) {
        console.warn(
          "[leetcode-tracker] 提交未能送达后台：请刷新页面后重交一次"
        );
      }
      return;
    }

    if (data.type === "problem_context") {
      const payload = data.payload || {};
      const key = `${payload.problem_id || ""}|${payload.slug || ""}|${payload.difficulty || ""}`;
      const now = Date.now();
      if (key === lastContextKey && now - lastContextAt < 2000) return;
      lastContextKey = key;
      lastContextAt = now;
      relay({ type: "problem_context", payload });
    }
  });
})();
