"use strict";

importScripts("config.js");

console.info("[codearena] background loaded", {
  version: chrome.runtime.getManifest().version,
});

async function postSubmission(payload) {
  const ok = await ensureAuth();
  if (!ok) {
    const err = new Error("请先登录账号后再同步提交");
    err.status = 401;
    throw err;
  }
  return apiFetch("/submit", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

async function prepareCoach(submissionId, problemId) {
  if (!submissionId && !problemId) return;
  try {
    if (!(await ensureAuth())) return;
    const data = await apiFetch("/api/coach/prepare", {
      method: "POST",
      body: JSON.stringify({
        submission_id: submissionId ? String(submissionId) : "",
        problem_id: problemId == null ? null : Number(problemId),
      }),
    });
    console.info("[codearena] prepare ok", {
      session_id: data.session_id,
    });
  } catch (error) {
    console.warn("[codearena] prepare failed (ignored)", String(error));
  }
}

async function setBadge(text, color) {
  try {
    await chrome.action.setBadgeText({ text });
    await chrome.action.setBadgeBackgroundColor({ color });
  } catch (_error) {
    // ignore
  }
}

function clearBadgeLater() {
  chrome.alarms.create("clear-badge", { delayInMinutes: 0.25 });
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "clear-badge") {
    setBadge("", "#000000");
  }
});

async function remember(event) {
  await chrome.storage.local.set({
    lastEvent: { ...event, at: Date.now() },
  });
}

async function notify(title, message, submissionId, problemId) {
  try {
    const notificationId = submissionId
      ? `coach:${submissionId}:${problemId || ""}`
      : `evt:${Date.now()}`;
    await chrome.notifications.create(notificationId, {
      type: "basic",
      iconUrl: "icons/icon128.png",
      title,
      message,
      priority: 1,
    });
  } catch (_error) {
    // ignore
  }
}

async function openCoachPage(submissionId, problemId) {
  const cfg = await getConfig();
  const params = new URLSearchParams();
  if (submissionId) params.set("submission", String(submissionId));
  if (problemId != null && problemId !== "") params.set("problem_id", String(problemId));
  const query = params.toString();
  const url = query ? `${cfg.webBase}/coach?${query}` : `${cfg.webBase}/coach`;
  await chrome.tabs.create({ url });
}

chrome.notifications.onClicked.addListener((notificationId) => {
  if (notificationId.startsWith("coach:")) {
    const [, submissionId, problemId] = notificationId.split(":");
    openCoachPage(submissionId, problemId || null).catch(() => {});
  }
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || !message.type) return false;

  if (message.type === "auth_changed") {
    sendResponse({ ok: true });
    return true;
  }

  if (message.type === "get_api_health") {
    (async () => {
      const cfg = await getConfig();
      // 先看本地 token，再向服务端校验；健康检查失败时不抹掉登录态
      let loggedIn = Boolean(cfg.accessToken);
      if (loggedIn) {
        loggedIn = await ensureAuth();
      }
      const latest = await getConfig();
      try {
        const health = await apiFetch("/health");
        sendResponse({
          ok: health.status === "ok",
          loggedIn,
          health,
          userPublicId: latest.userPublicId,
          userDisplay: latest.userDisplay,
          webBase: cfg.webBase,
        });
      } catch (error) {
        sendResponse({
          ok: false,
          loggedIn,
          error: friendlyError(error),
          userPublicId: latest.userPublicId,
          userDisplay: latest.userDisplay,
          webBase: cfg.webBase,
        });
      }
    })();
    return true;
  }

  if (message.type === "get_current_problem") {
    chrome.storage.local
      .get(["currentProblem"])
      .then((stored) =>
        sendResponse({ ok: true, problem: stored.currentProblem || null })
      )
      .catch((error) => sendResponse({ ok: false, error: String(error) }));
    return true;
  }

  if (message.type === "problem_context") {
    chrome.storage.local
      .set({ currentProblem: { ...message.payload, at: Date.now() } })
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: String(error) }));
    return true;
  }

  if (message.type !== "submission") return false;

  const payload = message.payload;
  postSubmission(payload)
    .then(async (data) => {
      const isNew = data.created === true;
      const submissionId = String(
        data.submission_id || payload.submission_id || ""
      );
      const summary = `${payload.problem_id}. ${payload.title || "题目"} (${payload.status})`;
      await remember({ ok: true, summary, data });
      setBadge(isNew ? "ok" : "dup", "#0a7");
      clearBadgeLater();
      notify(
        isNew ? "提交已记录 · 点击打开陪练" : "已有相同提交 · 点击打开陪练",
        summary,
        submissionId,
        payload.problem_id
      );
      sendResponse({ ok: true, data });
      void prepareCoach(submissionId, payload.problem_id);
    })
    .catch(async (error) => {
      const messageText = friendlyError(error);
      await remember({
        ok: false,
        error: messageText,
        summary: payload?.title || "",
      });
      setBadge("!", "#c00");
      if (error.status === 401) {
        notify("需要登录", "请点击扩展图标，打开「账号登录」后再同步提交", null, null);
      } else {
        notify("同步失败", messageText, null, null);
      }
      sendResponse({ ok: false, error: messageText });
    });

  return true;
});
