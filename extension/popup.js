let bridgeBase = "http://127.0.0.1:8763";
let bridgeOnline = false;
let coachHint = null;

const bridgeEl = document.getElementById("bridge");
const countEl = document.getElementById("count");
const lastEl = document.getElementById("last");
const dashboardBtn = document.getElementById("open-dashboard");
const coachBtn = document.getElementById("open-coach");
const problemBtn = document.getElementById("open-problem");
const dailyReviewBtn = document.getElementById("daily-review");
const recommendBtn = document.getElementById("recommend-next");
const coachTitleEl = document.getElementById("coach-title");
const coachSuggestionEl = document.getElementById("coach-suggestion");
const coachMetaEl = document.getElementById("coach-meta");

function setOnline(online) {
  bridgeOnline = online;
  dashboardBtn.disabled = !online;
  dailyReviewBtn.disabled = !online;
  recommendBtn.disabled = !online;
  const canCoach = Boolean(
    coachHint?.problem_id || coachHint?.latest_submission_id
  );
  coachBtn.disabled = !online || !canCoach;
  problemBtn.disabled = !online || !coachHint?.problem_id;
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
  const res = await fetch(`${bridgeBase}/api/coach/hint?${params.toString()}`);
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || `HTTP ${res.status}`);
  return data;
}

function renderCoachHint(hint, contextLabel) {
  coachHint = hint;
  coachTitleEl.textContent = contextLabel;
  coachSuggestionEl.textContent = hint.suggestion || "暂无建议";
  const bits = [];
  if (hint.kg_short) bits.push(`图谱：${hint.kg_short}`);
  if (hint.latest_status) bits.push(`最近：${hint.latest_status}`);
  coachMetaEl.textContent = bits.join(" · ");
  setOnline(bridgeOnline);
}

function renderCoachUnavailable(message, partial = null) {
  coachHint = partial;
  coachTitleEl.textContent = "本题陪练";
  coachSuggestionEl.textContent = message;
  coachMetaEl.textContent = "";
  setOnline(bridgeOnline);
}

async function loadCoachForCurrentProblem() {
  if (!bridgeOnline) {
    renderCoachUnavailable("本机服务离线，请先运行 leetcode-tracker serve");
    return;
  }
  const { tab, slug, cached } = await getActiveTabContext();
  const onProblemPage = Boolean(slug);
  if (!onProblemPage) {
    renderCoachUnavailable("请在 leetcode.cn 题目页打开本弹窗");
    return;
  }

  const problemId =
    cached?.problem_id ||
    parseProblemIdFromTitle(tab?.title) ||
    null;
  const title = cached?.title || tab?.title?.split("-")[0]?.trim() || slug;
  const contextLabel = problemId
    ? `${problemId}. ${title || slug}`
    : `${slug}（题号同步中…）`;

  const partialHint = problemId ? { problem_id: problemId, title, slug } : null;

  try {
    if (problemId) {
      const hint = await fetchCoachHint(problemId, null);
      renderCoachHint(hint, contextLabel);
      return;
    }
    const hintBySlug = await fetchCoachHint(null, slug);
    renderCoachHint(hintBySlug, contextLabel);
  } catch (err) {
    if (problemId) {
      renderCoachUnavailable(
        `${err.message || err}\n（图谱建议需本机已运行 serve 且已 kg import）`,
        partialHint
      );
    } else {
      renderCoachUnavailable(
        `已识别 slug：${slug}。题号尚未同步——请稍等页面加载，或提交一次后自动写入。`
      );
    }
  }
}

async function refresh() {
  bridgeEl.textContent = "检测中…";
  bridgeEl.className = "";
  countEl.textContent = "—";
  setOnline(false);
  coachTitleEl.textContent = "检测当前题目…";
  coachSuggestionEl.textContent = "—";

  try {
    const healthRes = await new Promise((resolve) => {
      chrome.runtime.sendMessage({ type: "get_bridge_health" }, (res) => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        resolve(res);
      });
    });
    if (healthRes?.base) bridgeBase = healthRes.base;
    if (healthRes?.ok && healthRes.health?.status === "ok") {
      bridgeEl.textContent = "在线";
      bridgeEl.className = "ok";
      countEl.textContent = String(healthRes.health.submissions_count ?? "—");
      if (healthRes.health.port) {
        bridgeBase = `http://127.0.0.1:${healthRes.health.port}`;
      }
      bridgeOnline = true;
      setOnline(true);
    } else {
      throw new Error("offline");
    }
  } catch (_err) {
    try {
      const res = await fetch(`${bridgeBase}/health`);
      const data = await res.json();
      if (!res.ok || data.status !== "ok") throw new Error("offline");
      if (data.port) bridgeBase = `http://127.0.0.1:${data.port}`;
      bridgeEl.textContent = "在线";
      bridgeEl.className = "ok";
      countEl.textContent = String(data.submissions_count ?? "—");
      bridgeOnline = true;
      setOnline(true);
    } catch (_err2) {
      bridgeEl.textContent = "离线";
      bridgeEl.className = "bad";
      bridgeOnline = false;
      setOnline(false);
      renderCoachUnavailable("本机服务离线");
    }
  }

  if (bridgeOnline) await loadCoachForCurrentProblem();

  const stored = await chrome.storage.local.get(["lastEvent"]);
  const last = stored.lastEvent;
  if (!last) {
    lastEl.textContent = "最近一次：尚无记录";
    return;
  }
  const when = last.at ? new Date(last.at).toLocaleString() : "";
  lastEl.textContent = last.ok
    ? `最近一次成功：${last.summary || ""} ${when}`
    : `最近一次失败：${last.error || "unknown"} ${when}`;
}

dashboardBtn.addEventListener("click", async () => {
  if (!bridgeOnline) return;
  await chrome.tabs.create({ url: `${bridgeBase}/` });
});

coachBtn.addEventListener("click", async () => {
  if (!bridgeOnline || !coachHint) return;
  let url = `${bridgeBase}/coach`;
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
  if (!bridgeOnline || !coachHint?.problem_id) return;
  await chrome.tabs.create({
    url: `${bridgeBase}/problems/${coachHint.problem_id}`,
  });
});

dailyReviewBtn.addEventListener("click", async () => {
  if (!bridgeOnline) return;
  await chrome.tabs.create({
    url: `${bridgeBase}/coach?mode=daily_review&action=daily_review`,
  });
});

recommendBtn.addEventListener("click", async () => {
  if (!bridgeOnline) return;
  await chrome.tabs.create({
    url: `${bridgeBase}/coach?mode=recommend&action=recommend`,
  });
});

document.getElementById("refresh").addEventListener("click", refresh);
refresh();
