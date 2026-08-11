"use strict";

let webBase = WEB_BASE;
let ready = false;
let loggedIn = false;
let coachHint = null;

const statusEl = document.getElementById("status-label");
const userLabelEl = document.getElementById("user-label");
const lastEl = document.getElementById("last");
const dashboardBtn = document.getElementById("open-dashboard");
const coachBtn = document.getElementById("open-coach");
const problemBtn = document.getElementById("open-problem");
const dailyReviewBtn = document.getElementById("daily-review");
const reviewQueueBtn = document.getElementById("review-queue");
const recommendBtn = document.getElementById("recommend-next");
const coachTitleEl = document.getElementById("coach-title");
const coachSuggestionEl = document.getElementById("coach-suggestion");
const coachMetaEl = document.getElementById("coach-meta");

function setReady(online, authed) {
  ready = online;
  loggedIn = authed;
  const canUse = online && authed;
  dashboardBtn.disabled = !canUse;
  dailyReviewBtn.disabled = !canUse;
  reviewQueueBtn.disabled = !canUse;
  recommendBtn.disabled = !canUse;
  const canCoach = Boolean(
    coachHint?.problem_id || coachHint?.latest_submission_id
  );
  coachBtn.disabled = !canUse || !canCoach;
  problemBtn.disabled = !canUse || !coachHint?.problem_id;
}

function parseProblemIdFromTitle(title) {
  const m = String(title || "").match(/^(\d+)\./);
  return m ? Number(m[1]) : null;
}

function parseSlugFromUrl(url) {
  try {
    const m = String(url).match(/leetcode\.cn\/problems\/([^/?#]+)/i);
    return m ? decodeURIComponent(m[1]) : null;
  } catch {
    return null;
  }
}

async function getActiveTabContext() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  const tab = tabs[0];
  const slug = tab?.url ? parseSlugFromUrl(tab.url) : null;
  const stored = await chrome.storage.local.get(["currentProblem"]);
  const cached = stored.currentProblem;
  return { tab, slug, cached };
}

async function fetchCoachHint(problemId, slug) {
  const params = new URLSearchParams();
  if (problemId) params.set("problem_id", String(problemId));
  else if (slug) params.set("slug", slug);
  else return null;
  return apiFetch(`/api/coach/hint?${params.toString()}`);
}

function renderCoachHint(hint, contextLabel) {
  coachHint = hint;
  coachTitleEl.textContent = contextLabel;
  coachSuggestionEl.textContent = hint.suggestion || "暂无建议";
  const bits = [];
  if (hint.latest_status) bits.push(`最近：${hint.latest_status}`);
  coachMetaEl.textContent = bits.join(" · ");
  setReady(ready, loggedIn);
}

function renderCoachUnavailable(message, partial = null) {
  coachHint = partial;
  coachTitleEl.textContent = "本题陪练";
  coachSuggestionEl.textContent = message;
  coachMetaEl.textContent = "";
  setReady(ready, loggedIn);
}

async function loadCoachForCurrentProblem() {
  if (!ready) {
    renderCoachUnavailable("暂时连不上服务，请稍后再试");
    return;
  }
  if (!loggedIn) {
    renderCoachUnavailable("请先登录账号，再同步提交与打开陪练");
    return;
  }
  const { tab, slug, cached } = await getActiveTabContext();
  if (!slug) {
    renderCoachUnavailable("请在力扣题目页打开本弹窗");
    return;
  }

  const problemId =
    cached?.problem_id || parseProblemIdFromTitle(tab?.title) || null;
  const title = cached?.title || tab?.title?.split("-")[0]?.trim() || slug;
  const contextLabel = problemId
    ? `${problemId}. ${title || slug}`
    : `${slug}（题号同步中…）`;
  const partialHint = problemId ? { problem_id: problemId, title, slug } : null;

  try {
    const hint = problemId
      ? await fetchCoachHint(problemId, null)
      : await fetchCoachHint(null, slug);
    renderCoachHint(hint, contextLabel);
  } catch (err) {
    if (problemId) {
      renderCoachUnavailable(friendlyError(err), partialHint);
    } else {
      renderCoachUnavailable(
        "已识别题目，题号还在同步中。稍等片刻或先提交一次后再打开。"
      );
    }
  }
}

async function refresh() {
  statusEl.textContent = "检测中…";
  statusEl.className = "";
  userLabelEl.textContent = "—";
  setReady(false, false);
  coachTitleEl.textContent = "检测当前题目…";
  coachSuggestionEl.textContent = "—";

  const cfg = await getConfig();
  webBase = cfg.webBase;

  try {
    const healthRes = await new Promise((resolve) => {
      chrome.runtime.sendMessage({ type: "get_api_health" }, (res) => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        resolve(res);
      });
    });

    if (healthRes?.ok && healthRes.health?.status === "ok") {
      const online = true;
      const authed = Boolean(healthRes.loggedIn);
      if (!authed) {
        statusEl.textContent = "未登录";
        statusEl.className = "bad";
        userLabelEl.textContent = "点击下方登录";
      } else if (healthRes.health.coach_available === false) {
        statusEl.textContent = "已登录";
        statusEl.className = "ok";
        userLabelEl.textContent =
          healthRes.userDisplay || healthRes.userPublicId || "已登录";
      } else {
        statusEl.textContent = "已就绪";
        statusEl.className = "ok";
        userLabelEl.textContent =
          healthRes.userDisplay || healthRes.userPublicId || "已登录";
      }
      setReady(online, authed);
    } else {
      throw new Error(healthRes?.error || "offline");
    }
  } catch (_err) {
    statusEl.textContent = "不可用";
    statusEl.className = "bad";
    setReady(false, false);
    renderCoachUnavailable("暂时连不上服务，请稍后再试");
  }

  if (ready && loggedIn) await loadCoachForCurrentProblem();
  else if (ready && !loggedIn) {
    renderCoachUnavailable("请先登录账号，再同步提交与打开陪练");
  }

  const stored = await chrome.storage.local.get(["lastEvent", "pendingSubmissions"]);
  const last = stored.lastEvent;
  const pendingCount = Array.isArray(stored.pendingSubmissions)
    ? stored.pendingSubmissions.length
    : 0;
  if (!last && pendingCount === 0) {
    lastEl.textContent = "最近一次：尚无记录";
    return;
  }
  const when = last?.at ? new Date(last.at).toLocaleString() : "";
  const pendingNote =
    pendingCount > 0 ? ` · 待补传 ${pendingCount} 条（登录后自动同步）` : "";
  if (!last) {
    lastEl.textContent = `最近一次：尚无记录${pendingNote}`;
    return;
  }
  lastEl.textContent = last.ok
    ? `最近一次成功：${last.summary || ""} ${when}${pendingNote}`
    : `最近一次失败：${last.error || "请稍后再试"} ${when}${pendingNote}`;
}

dashboardBtn.addEventListener("click", async () => {
  if (!ready || !loggedIn) return;
  await chrome.tabs.create({ url: `${webBase}/` });
});

coachBtn.addEventListener("click", async () => {
  if (!ready || !loggedIn || !coachHint) return;
  let url = `${webBase}/coach`;
  if (coachHint.latest_submission_id) {
    const params = new URLSearchParams({
      submission: String(coachHint.latest_submission_id),
      problem_id: String(coachHint.problem_id),
    });
    url += `?${params.toString()}`;
  } else if (coachHint.problem_id) {
    url += `?problem_id=${encodeURIComponent(String(coachHint.problem_id))}`;
  }
  await chrome.tabs.create({ url });
});

problemBtn.addEventListener("click", async () => {
  if (!ready || !loggedIn || !coachHint?.problem_id) return;
  await chrome.tabs.create({
    url: `${webBase}/problems/${coachHint.problem_id}`,
  });
});

dailyReviewBtn.addEventListener("click", async () => {
  if (!ready || !loggedIn) return;
  await chrome.tabs.create({
    url: `${webBase}/coach?mode=daily_review&action=daily_review`,
  });
});

reviewQueueBtn.addEventListener("click", async () => {
  if (!ready || !loggedIn) return;
  await chrome.tabs.create({
    url: `${webBase}/coach?mode=review&action=review`,
  });
});

recommendBtn.addEventListener("click", async () => {
  if (!ready || !loggedIn) return;
  await chrome.tabs.create({
    url: `${webBase}/coach?mode=recommend&action=recommend`,
  });
});

document.getElementById("open-options").addEventListener("click", () => {
  chrome.runtime.openOptionsPage();
});

document.getElementById("refresh").addEventListener("click", refresh);

// 选项页登录/退出后立刻刷新弹窗状态
chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local") return;
  if (changes.accessToken || changes.userDisplay || changes.userPublicId) {
    refresh();
  }
});

refresh();
