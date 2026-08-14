"use strict";

importScripts("config.js");

console.info("[codearena] background loaded", {
  version: chrome.runtime.getManifest().version,
});

const PENDING_SUBMISSIONS_KEY = "pendingSubmissions";
const MAX_PENDING = 20;

async function enqueuePendingSubmission(payload) {
  if (!payload || !payload.submission_id) return;
  const stored = await chrome.storage.local.get([PENDING_SUBMISSIONS_KEY]);
  const list = Array.isArray(stored[PENDING_SUBMISSIONS_KEY])
    ? stored[PENDING_SUBMISSIONS_KEY]
    : [];
  const sid = String(payload.submission_id);
  const next = list.filter((item) => String(item?.submission_id) !== sid);
  next.unshift({ ...payload, queued_at: Date.now() });
  await chrome.storage.local.set({
    [PENDING_SUBMISSIONS_KEY]: next.slice(0, MAX_PENDING),
  });
}

async function flushPendingSubmissions() {
  if (!(await ensureAuth())) return { flushed: 0, left: 0 };
  const stored = await chrome.storage.local.get([PENDING_SUBMISSIONS_KEY]);
  const list = Array.isArray(stored[PENDING_SUBMISSIONS_KEY])
    ? stored[PENDING_SUBMISSIONS_KEY]
    : [];
  if (!list.length) return { flushed: 0, left: 0 };

  const remain = [];
  let flushed = 0;
  for (let i = 0; i < list.length; i++) {
    const payload = list[i];
    try {
      await apiFetch("/submit", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      flushed += 1;
      const submissionId = String(payload.submission_id || "");
      void prepareCoach(submissionId, payload.problem_id);
    } catch (error) {
      if (error && error.status === 401) {
        remain.push(...list.slice(i));
        break;
      }
      // 业务错误（缺字段等）丢弃，避免死循环
      console.warn("[codearena] pending submit dropped", String(error));
    }
  }
  await chrome.storage.local.set({ [PENDING_SUBMISSIONS_KEY]: remain });
  if (flushed > 0) {
    setBadge("ok", "#0a7");
    clearBadgeLater();
    notify("提交已补同步", `成功补传 ${flushed} 条力扣提交`, null, null);
  }
  return { flushed, left: remain.length };
}

async function postSubmission(payload) {
  const ok = await ensureAuth();
  if (!ok) {
    await enqueuePendingSubmission(payload);
    const err = new Error("请先登录账号后再同步提交（已暂存，登录后自动补传）");
    err.status = 401;
    throw err;
  }
  try {
    return await apiFetch("/submit", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  } catch (error) {
    // 网络/网关异常：暂存，避免力扣已判题但本地丢失
    const status = error && error.status;
    if (!status || status === 0 || status >= 500) {
      await enqueuePendingSubmission(payload);
      const err = new Error(
        `${friendlyError(error)}（已暂存，服务恢复或重新登录后会自动补传）`
      );
      err.status = status || 0;
      throw err;
    }
    throw error;
  }
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
  if (alarm.name === "flush-pending") {
    void flushPendingSubmissions();
  }
});

// 每分钟尝试补传暂存提交
try {
  chrome.alarms.create("flush-pending", { periodInMinutes: 1 });
} catch (_e) {
  /* ignore */
}
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

  if (message.type === "flush_pending") {
    (async () => {
      const result = await flushPendingSubmissions();
      sendResponse({ ok: true, ...result });
    })();
    return true;
  }

  if (message.type === "auth_changed") {
    (async () => {
      const result = await flushPendingSubmissions();
      sendResponse({ ok: true, ...result });
    })();
    return true;
  }

  if (message.type === "sync_web_auth") {
    (async () => {
      const token = message.token || "";
      if (!token) {
        await saveConfig({ accessToken: "", userPublicId: "", userDisplay: "" });
        sendResponse({ ok: true, cleared: true });
        return;
      }
      const local = applyTokenLocally(token);
      if (!local) {
        sendResponse({ ok: false, error: "invalid jwt" });
        return;
      }
      const user = message.user || {};
      await saveConfig({
        accessToken: local.accessToken,
        userPublicId: user.public_id || local.userPublicId,
        userDisplay:
          user.display_name || user.username || local.userDisplay,
      });
      // 后台再校验一次，并补传登录前暂存的提交
      await ensureAuth();
      const flushed = await flushPendingSubmissions();
      sendResponse({ ok: true, ...flushed });
    })();
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

  if (message.type === "get_bridge_status") {
    (async () => {
      const cfg = await getConfig();
      const stored = await chrome.storage.local.get([PENDING_SUBMISSIONS_KEY]);
      const pending = Array.isArray(stored[PENDING_SUBMISSIONS_KEY])
        ? stored[PENDING_SUBMISSIONS_KEY].length
        : 0;
      sendResponse({ ok: true, loggedIn: Boolean(cfg.accessToken), pendingCount: pending });
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
        notify(
          "需要登录",
          "提交已暂存。请打开扩展「账号登录」；登录后会自动补传到后端",
          null,
          null
        );
        try {
          await chrome.runtime.openOptionsPage();
        } catch (_e) {
          /* ignore */
        }
      } else {
        notify("同步失败", messageText, null, null);
      }
      sendResponse({ ok: false, error: messageText });
    });

  return true;
});

// SW 唤醒时尝试补传
void flushPendingSubmissions();
