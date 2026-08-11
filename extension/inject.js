/**
 * MAIN world @ document_start — must load before page JS caches native fetch.
 */
(function () {
  function debug(message, extra) {
    try {
      console.info("[leetcode-tracker]", message, extra || "");
      window.postMessage(
        { source: "leetcode-tracker", type: "debug", message, extra: extra || null },
        "*"
      );
    } catch {
      // ignore
    }
  }

  // If we were injected late once, still allow a forced rewrap.
  const FORCE = true;
  if (window.__leetcodeTrackerInjected && !FORCE) {
    debug("hooks already installed");
    return;
  }
  if (window.__leetcodeTrackerFetchPatched) {
    debug("fetch already patched; listening");
  }

  const PENDING_TTL_MS = 10 * 60 * 1000; // 未完成提交暂存
  const EMITTED_TTL_MS = 10 * 60 * 1000; // 已上报 id，挡住重复 check
  const CLEAN_EVERY_MS = 60 * 1000;

  const pending = new Map(); // submission_id -> { ..., createdAt }
  const emitted = new Map(); // submission_id -> emittedAt
  const problemMetaCache = new Map(); // slug -> { problem_id, title, slug, difficulty, tags }
  const problemMetaCacheById = new Map(); // problem_id -> meta
  const difficultyFetchCache = new Map(); // slug -> Promise<meta|null>

  function pruneMaps(now) {
    now = now || Date.now();
    for (const [key, item] of pending) {
      const createdAt = item && item.createdAt != null ? item.createdAt : 0;
      if (now - createdAt > PENDING_TTL_MS) pending.delete(key);
    }
    for (const [key, at] of emitted) {
      if (now - at > EMITTED_TTL_MS) emitted.delete(key);
    }
  }

  function wasEmitted(submissionId) {
    pruneMaps();
    const key = String(submissionId);
    const at = emitted.get(key);
    if (at == null) return false;
    if (Date.now() - at > EMITTED_TTL_MS) {
      emitted.delete(key);
      return false;
    }
    return true;
  }

  function markEmitted(submissionId) {
    emitted.set(String(submissionId), Date.now());
  }

  function emit(payload) {
    const sid = payload && payload.submission_id;
    if (!sid) return;
    if (wasEmitted(sid)) {
      debug("skip duplicate emit (sliding window)", { submission_id: sid });
      return;
    }
    markEmitted(sid);
    debug("emit submission", {
      submission_id: payload.submission_id,
      problem_id: payload.problem_id,
      status: payload.status,
      difficulty: payload.difficulty,
    });
    // 只走 postMessage（与旧版一致）；勿再发 CustomEvent，否则 content 会双投递
    window.postMessage(
      { source: "leetcode-tracker", type: "submission", payload },
      "*"
    );
  }

  function parseJsonSafe(text) {
    try {
      return JSON.parse(text);
    } catch {
      return null;
    }
  }

  function extractRequestBody(input, init) {
    // 严禁消费会传给 fetch 的 body 流，否则会触发 InvalidStateError 并弄坏力扣请求
    try {
      if (init && typeof init.body === "string") return init.body;
      if (init && init.body instanceof URLSearchParams) return init.body.toString();
      if (
        input &&
        typeof input !== "string" &&
        typeof Request !== "undefined" &&
        input instanceof Request
      ) {
        // 只能 clone 再读；失败则放弃 body（仍可从 check 补全）
        return null;
      }
    } catch {
      // ignore
    }
    return null;
  }

  async function readRequestBodySafe(input, init) {
    const sync = extractRequestBody(input, init);
    if (sync != null) return sync;
    try {
      if (
        input &&
        typeof input !== "string" &&
        typeof Request !== "undefined" &&
        input instanceof Request
      ) {
        return await input.clone().text();
      }
    } catch {
      // ignore
    }
    return "";
  }

  function extractSubmitIdFromResponse(resp) {
    const direct = findSubmissionId(resp, 0);
    if (direct != null) return direct;
    const data = resp && resp.data;
    if (!data || typeof data !== "object") return null;
    for (const node of Object.values(data)) {
      if (!node || typeof node !== "object") continue;
      const id = node.submissionId ?? node.submission_id ?? node.interpretId ?? node.interpret_id;
      if (id != null && String(id).trim() !== "") return id;
    }
    return null;
  }

  function mergeProblemMeta(base, fresh) {
    const out = { ...(base || {}) };
    for (const [key, value] of Object.entries(fresh || {})) {
      if (value == null || value === "") continue;
      if (Array.isArray(value) && value.length === 0) continue;
      out[key] = value;
    }
    return out;
  }

  function rememberProblemMeta(meta) {
    if (!meta) return meta;
    const mergedBySlug = meta.slug
      ? mergeProblemMeta(problemMetaCache.get(meta.slug), meta)
      : meta;
    if (meta.slug) problemMetaCache.set(meta.slug, mergedBySlug);
    if (mergedBySlug.problem_id) {
      problemMetaCacheById.set(String(mergedBySlug.problem_id), mergedBySlug);
    }
    if (mergedBySlug.problem_id || mergedBySlug.slug) {
      const fingerprint = [
        mergedBySlug.problem_id || "",
        mergedBySlug.slug || "",
        mergedBySlug.difficulty || "",
        mergedBySlug.title || "",
      ].join("|");
      const now = Date.now();
      const prev = window.__leetcodeTrackerLastContextFp || "";
      const prevAt = window.__leetcodeTrackerLastContextAt || 0;
      // 相同 meta 不重复广播；至少间隔 1.5s，避免 MutationObserver 刷屏
      if (fingerprint !== prev || now - prevAt > 1500) {
        window.__leetcodeTrackerLastContextFp = fingerprint;
        window.__leetcodeTrackerLastContextAt = now;
        try {
          window.postMessage(
            {
              source: "leetcode-tracker",
              type: "problem_context",
              payload: {
                problem_id: mergedBySlug.problem_id,
                title: mergedBySlug.title,
                slug: mergedBySlug.slug,
                difficulty: mergedBySlug.difficulty,
                href: String(location.href),
              },
            },
            "*"
          );
        } catch {
          // ignore
        }
      }
    }
    return mergedBySlug;
  }

  function getCachedProblemMeta(slug, problemId) {
    if (slug && problemMetaCache.has(slug)) return problemMetaCache.get(slug);
    if (problemId != null && problemMetaCacheById.has(String(problemId))) {
      return problemMetaCacheById.get(String(problemId));
    }
    return null;
  }

  function looksLikeRealSlug(slug) {
    return Boolean(slug) && !/^problem-\d+$/i.test(String(slug));
  }

  async function fetchProblemMetaBySlug(slug) {
    if (!looksLikeRealSlug(slug)) return null;
    if (difficultyFetchCache.has(slug)) return difficultyFetchCache.get(slug);

    const fetcher = (async () => {
      try {
        const originalFetch = window.__leetcodeTrackerOriginalFetch || window.fetch.bind(window);
        const res = await originalFetch("https://leetcode.cn/graphql/", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            query:
              "query questionData($titleSlug: String!) { question(titleSlug: $titleSlug) { difficulty questionFrontendId translatedTitle title titleSlug } }",
            variables: { titleSlug: slug },
          }),
        });
        const data = await res.json();
        const q = data?.data?.question;
        if (!q) return null;
        return rememberProblemMeta({
          problem_id: Number(q.questionFrontendId) || null,
          title: q.translatedTitle || q.title || null,
          slug: q.titleSlug || slug,
          difficulty: difficultyLabel(q.difficulty),
          tags: [],
        });
      } catch (err) {
        debug("difficulty fetch failed", { slug, error: String(err) });
        return null;
      }
    })();

    difficultyFetchCache.set(slug, fetcher);
    return fetcher;
  }

  async function ensureDifficulty(meta) {
    if (meta?.difficulty) return meta;
    const slug = meta?.slug;
    const cached = getCachedProblemMeta(slug, meta?.problem_id);
    if (cached?.difficulty) return mergeProblemMeta(meta, cached);

    if (!looksLikeRealSlug(slug)) return meta;
    const fetched = await fetchProblemMetaBySlug(slug);
    return fetched ? mergeProblemMeta(meta, fetched) : meta;
  }

  function resolveProblemMeta(baseMeta, questionObj) {
    const fresh = rememberProblemMeta(extractProblemMeta());
    const slug = fresh.slug || baseMeta?.slug;
    const problemId = fresh.problem_id || baseMeta?.problem_id;
    let meta = mergeProblemMeta(baseMeta, getCachedProblemMeta(slug, problemId));
    meta = mergeProblemMeta(meta, fresh);
    if (questionObj) {
      const fromQuestion = {
        problem_id: null,
        title: null,
        slug: null,
        difficulty: null,
        tags: [],
      };
      applyQuestionMeta(fromQuestion, questionObj);
      meta = mergeProblemMeta(meta, rememberProblemMeta(fromQuestion));
    }
    return meta;
  }

  function startProblemMetaWatch() {
    if (window.__leetcodeTrackerMetaWatchStarted) return;
    window.__leetcodeTrackerMetaWatchStarted = true;

    let scheduled = false;
    const refresh = () => {
      const meta = extractProblemMeta();
      if (
        meta?.slug &&
        !meta?.problem_id &&
        looksLikeRealSlug(meta.slug) &&
        !difficultyFetchCache.has(meta.slug)
      ) {
        void fetchProblemMetaBySlug(meta.slug);
      }
    };
    const scheduleRefresh = () => {
      if (scheduled) return;
      scheduled = true;
      setTimeout(() => {
        scheduled = false;
        refresh();
      }, 400);
    };

    const observer = new MutationObserver(scheduleRefresh);
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      // 不监听 characterData：Monaco 编辑会狂触发，无助于题号解析
    });
    setInterval(refresh, 3000);
    refresh();
  }

  function difficultyLabel(value) {
    if (value == null) return null;
    if (typeof value === "object") {
      return (
        difficultyLabel(value.level) ||
        difficultyLabel(value.difficulty) ||
        difficultyLabel(value.name) ||
        difficultyLabel(value.slug)
      );
    }
    if (typeof value === "number") {
      return { 1: "Easy", 2: "Medium", 3: "Hard" }[value] || null;
    }
    const s = String(value).trim();
    const map = {
      easy: "Easy",
      medium: "Medium",
      hard: "Hard",
      EASY: "Easy",
      MEDIUM: "Medium",
      HARD: "Hard",
      Easy: "Easy",
      Medium: "Medium",
      Hard: "Hard",
      简单: "Easy",
      中等: "Medium",
      困难: "Hard",
    };
    return map[s] || map[s.toLowerCase()] || null;
  }

  function difficultyFromDom() {
    const tagged = document.querySelector("[data-difficulty]");
    if (tagged) {
      const fromAttr = difficultyLabel(tagged.getAttribute("data-difficulty"));
      if (fromAttr) return fromAttr;
    }

    for (const el of document.querySelectorAll("[class*='text-difficulty']")) {
      const cls = String(el.className || "");
      if (/text-difficulty-easy/i.test(cls)) return "Easy";
      if (/text-difficulty-medium/i.test(cls)) return "Medium";
      if (/text-difficulty-hard/i.test(cls)) return "Hard";
      const fromText = difficultyLabel(el.textContent || "");
      if (fromText) return fromText;
    }
    return null;
  }

  const STATUS_CODE_MAP = {
    10: "Accepted",
    11: "Wrong Answer",
    12: "Memory Limit Exceeded",
    13: "Output Limit Exceeded",
    14: "Time Limit Exceeded",
    15: "Runtime Error",
    16: "Internal Error",
    20: "Compile Error",
  };

  function normalizeStatus(value) {
    if (value == null) return null;
    const s = String(value).trim();
    const map = {
      通过: "Accepted",
      Accepted: "Accepted",
      "Wrong Answer": "Wrong Answer",
      解答错误: "Wrong Answer",
      "Time Limit Exceeded": "Time Limit Exceeded",
      超时: "Time Limit Exceeded",
      "Memory Limit Exceeded": "Memory Limit Exceeded",
      内存超出: "Memory Limit Exceeded",
      "Runtime Error": "Runtime Error",
      执行出错: "Runtime Error",
      "Compile Error": "Compile Error",
      编译错误: "Compile Error",
      "Output Limit Exceeded": "Output Limit Exceeded",
    };
    return map[s] || s;
  }

  function statusFromCheck(check) {
    if (check.status_code != null && STATUS_CODE_MAP[Number(check.status_code)]) {
      return STATUS_CODE_MAP[Number(check.status_code)];
    }
    return normalizeStatus(check.status_msg || check.statusMsg || check.local_status) || "Unknown";
  }

  function parseTitleText(text) {
    if (!text) return null;
    const cleaned = text.replace(/\s+/g, " ").trim();
    const tm = cleaned.match(/^(\d+)\.\s*(.+)$/);
    if (tm) return { problem_id: Number(tm[1]), title: tm[2].trim() };
    return { problem_id: null, title: cleaned };
  }

  function extractQuestionFromDehydratedState(pageProps) {
    const queries = pageProps?.dehydratedState?.queries;
    if (!Array.isArray(queries)) return null;
    for (const query of queries) {
      const q = query?.state?.data?.question;
      if (q && (q.titleSlug || q.questionFrontendId || q.title || q.translatedTitle)) {
        return q;
      }
    }
    return null;
  }

  function applyQuestionMeta(meta, q) {
    if (!q) return;
    meta.problem_id = Number(
      q.questionFrontendId || q.frontendQuestionId || q.questionId || meta.problem_id
    );
    meta.title = q.translatedTitle || q.title || meta.title;
    meta.slug = q.titleSlug || q.questionTitleSlug || meta.slug;
    meta.difficulty =
      difficultyLabel(q.difficulty) ||
      difficultyLabel(q.difficultyLevel) ||
      difficultyLabel(q.level) ||
      meta.difficulty;
    if (Array.isArray(q.topicTags)) {
      meta.tags = q.topicTags
        .map((t) => t.translatedName || t.name || t.slug)
        .filter(Boolean);
    }
  }

  function extractProblemMeta() {
    const meta = {
      problem_id: null,
      title: null,
      slug: null,
      difficulty: null,
      tags: [],
    };

    const path = location.pathname || "";
    const m = path.match(/\/problems\/([^/]+)/);
    if (m) meta.slug = decodeURIComponent(m[1]);

    const nextData = document.getElementById("__NEXT_DATA__");
    if (nextData && nextData.textContent) {
      const data = parseJsonSafe(nextData.textContent);
      const pageProps = data?.props?.pageProps;
      const q =
        pageProps?.question ||
        pageProps?.data?.question ||
        extractQuestionFromDehydratedState(pageProps);
      applyQuestionMeta(meta, q);
    }

    try {
      const pd = window.pageData;
      if (pd) {
        meta.problem_id = Number(pd.questionFrontendId || pd.questionId || meta.problem_id);
        meta.title = pd.questionTitle || pd.translateTitle || meta.title;
        meta.slug = pd.questionTitleSlug || meta.slug;
        meta.difficulty = difficultyLabel(pd.difficulty) || meta.difficulty;
      }
    } catch {
      // ignore
    }

    for (const sel of [
      "[data-cy='question-title']",
      ".text-title-large",
      "div[class*='text-title']",
    ]) {
      const el = document.querySelector(sel);
      if (!el) continue;
      const parsed = parseTitleText(el.textContent || "");
      if (parsed) {
        if (parsed.problem_id) meta.problem_id = parsed.problem_id;
        if (parsed.title) meta.title = parsed.title;
        break;
      }
    }

    if (!meta.problem_id || !meta.title) {
      const parsed = parseTitleText((document.title || "").split("-")[0] || "");
      if (parsed) {
        if (!meta.problem_id && parsed.problem_id) meta.problem_id = parsed.problem_id;
        if (!meta.title && parsed.title) meta.title = parsed.title;
      }
    }

    if (!meta.difficulty) meta.difficulty = difficultyFromDom();

    return rememberProblemMeta(meta);
  }

  function rememberSubmit(submissionId, bodyObj, meta) {
    const key = String(submissionId);
    const code =
      bodyObj?.typed_code ||
      bodyObj?.code ||
      bodyObj?.typedCode ||
      bodyObj?.variables?.typed_code ||
      bodyObj?.variables?.typedCode ||
      null;
    const language =
      bodyObj?.lang ||
      bodyObj?.langSlug ||
      bodyObj?.language ||
      bodyObj?.variables?.lang ||
      bodyObj?.variables?.langSlug ||
      null;
    pending.set(key, {
      submission_id: key,
      code,
      language,
      meta: meta || extractProblemMeta(),
      createdAt: Date.now(),
    });
    debug("remember submit", { submissionId: key, hasCode: Boolean(code), lang: language });
  }

  function findSubmissionId(obj, depth) {
    if (!obj || depth > 6) return null;
    if (typeof obj !== "object") return null;
    for (const key of [
      "submission_id",
      "submissionId",
      "interpret_id",
      "interpretId",
    ]) {
      if (obj[key] != null && String(obj[key]).trim() !== "") {
        return obj[key];
      }
    }
    for (const value of Object.values(obj)) {
      if (value && typeof value === "object") {
        const found = findSubmissionId(value, depth + 1);
        if (found != null) return found;
      }
    }
    return null;
  }

  function isFinishedCheck(check) {
    if (check.isPending === true) return false;
    const state = String(check.state || "").toUpperCase();
    if (state === "PENDING" || state === "STARTED") return false;
    if (check.finished === false) return false;
    if (state === "SUCCESS") return true;
    if (check.finished === true) return true;
    if (check.status_code != null && Number(check.status_code) > 0) return true;
    if (check.status_msg || check.statusMsg || check.statusDisplay) return true;
    return false;
  }

  async function resolveFinalProblemId(meta, check) {
    let finalProblemId = Number(meta.problem_id) || null;
    if (!finalProblemId && check.question) {
      const q = check.question;
      finalProblemId =
        Number(q.questionFrontendId || q.frontendQuestionId || q.questionId) || null;
    }
    if (!finalProblemId) {
      const qid = check.question_id || check.questionId;
      if (qid != null && String(qid).trim() !== "") finalProblemId = Number(qid);
    }
    if (!finalProblemId && meta.slug) {
      const cached = getCachedProblemMeta(meta.slug, null);
      if (cached?.problem_id) finalProblemId = Number(cached.problem_id);
    }
    if (!finalProblemId && meta.slug && looksLikeRealSlug(meta.slug)) {
      const fetched = await Promise.race([
        fetchProblemMetaBySlug(meta.slug),
        new Promise((resolve) => setTimeout(() => resolve(null), 2500)),
      ]);
      if (fetched?.problem_id) finalProblemId = Number(fetched.problem_id);
    }
    return finalProblemId;
  }

  async function finalizeFromCheck(submissionId, check) {
    const key = String(submissionId);
    if (!isFinishedCheck(check)) {
      debug("check pending", { submissionId: key, state: check.state });
      return;
    }

    if (wasEmitted(key)) {
      pending.delete(key);
      debug("skip finalize, already emitted", { submissionId: key });
      return;
    }

    const pendingItem = pending.get(key) || {
      submission_id: key,
      code: check.code || null,
      language: null,
      meta: extractProblemMeta(),
      createdAt: Date.now(),
    };

    const status = statusFromCheck(check);

    let runtimeMs = null;
    const runtime = check.status_runtime || check.runtime || check.runtime_ms;
    if (typeof runtime === "string") {
      const rm = runtime.match(/([\d.]+)/);
      if (rm) runtimeMs = Math.round(parseFloat(rm[1]));
    } else if (typeof runtime === "number") {
      runtimeMs = runtime;
    }

    let memoryMb = null;
    const memory = check.status_memory || check.memory || check.memory_mb;
    if (typeof memory === "string") {
      const mm = memory.match(/([\d.]+)/);
      if (mm) memoryMb = parseFloat(mm[1]);
    } else if (typeof memory === "number") {
      memoryMb = memory;
    }

    const meta = resolveProblemMeta(pendingItem.meta, check.question);
    const finalProblemId = await resolveFinalProblemId(meta, check);

    if (!finalProblemId) {
      debug("drop: missing problem_id", { submissionId: key, slug: meta.slug });
      return;
    }

    const payloadBase = {
      submission_id: pendingItem.submission_id,
      problem_id: finalProblemId,
      title: meta.title || `Problem ${finalProblemId}`,
      slug: meta.slug || `problem-${finalProblemId}`,
      tags: meta.tags || [],
      status,
      code: pendingItem.code || check.code || null,
      runtime_ms: runtimeMs,
      memory_mb: memoryMb,
      language:
        pendingItem.language || check.lang || check.pretty_lang || check.lang_name || null,
    };

    emit({
      ...payloadBase,
      title: meta.title || payloadBase.title,
      slug: meta.slug || payloadBase.slug,
      difficulty: meta.difficulty || null,
      tags: meta.tags || payloadBase.tags,
    });
    pending.delete(key);
    if (!meta.difficulty && meta.slug && looksLikeRealSlug(meta.slug)) {
      void ensureDifficulty(meta);
    }
  }

  function absoluteUrl(url) {
    try {
      return new URL(url, location.origin).href;
    } catch {
      return String(url || "");
    }
  }

  function isLeetCodeHost(url) {
    try {
      const host = new URL(url, location.origin).hostname;
      return /(^|\.)leetcode\.cn$/i.test(host) || /(^|\.)leetcode\.com$/i.test(host);
    } catch {
      return false;
    }
  }

  function interesting(url) {
    if (!isLeetCodeHost(url)) return false;
    return /\/(submit|submissions|graphql|judge|interpret)\b/i.test(url);
  }

  function handleSubmitUrl(url, requestBodyText, responseText) {
    const full = absoluteUrl(url);
    if (!isLeetCodeHost(full)) return;

    const bodyText = requestBodyText || "";
    const isSubmitPath = /\/problems\/[^/]+\/submit\/?/i.test(full);
    const isGraphqlSubmit =
      /\/graphql\/?/i.test(full) &&
      /submitCode|SubmitCode|submit\.code|interpretSolution|judgeSubmit|syncSubmit|typedCode|typed_code/i.test(
        bodyText
      );
    // 仅认力扣提交路径 / GraphQL 提交体，避免 GA 等第三方 URL 查询串里的 submit 误伤
    const isTypedCodePost =
      /\/problems\//i.test(full) && /typed_code|typedCode/i.test(bodyText);

    if (!isSubmitPath && !isGraphqlSubmit && !isTypedCodePost) {
      return;
    }

    debug("seen candidate submit", {
      url: full.slice(0, 180),
      bodyPreview: bodyText.slice(0, 120),
    });

    const resp = parseJsonSafe(responseText);
    const body = parseJsonSafe(bodyText) || {};
    const meta = extractProblemMeta();
    const submissionId = extractSubmitIdFromResponse(resp);

    if (submissionId != null) {
      if (/interpret/i.test(full) && !/submit/i.test(full) && !isGraphqlSubmit) {
        debug("skip interpret run", { submissionId });
        return;
      }
      rememberSubmit(submissionId, body, meta);
    } else {
      debug("submit-like response without id", {
        url: full.slice(0, 180),
        respPreview: String(responseText || "").slice(0, 200),
      });
    }
  }

  function handleCheckUrl(url, responseText) {
    const full = absoluteUrl(url);
    const resp = parseJsonSafe(responseText);
    if (!resp) return;

    const m = full.match(/\/submissions\/detail\/(\d+)\/(?:v2\/)?check\/?/i);
    if (m) {
      debug("seen check", {
        submissionId: m[1],
        state: resp.state,
        status_code: resp.status_code,
      });
      void finalizeFromCheck(m[1], resp);
      return;
    }

    if (
      resp.submission_id &&
      (resp.state || resp.status_code || resp.status_msg || resp.finished != null)
    ) {
      debug("seen check-like payload", {
        submissionId: resp.submission_id,
        state: resp.state,
      });
      void finalizeFromCheck(resp.submission_id, resp);
      return;
    }

    const detail =
      resp?.data?.submissionDetails ||
      resp?.data?.submissionDetail ||
      resp?.data?.submissionCheck ||
      resp?.data?.checkSubmission ||
      resp?.data?.syncSubmission ||
      null;
    if (detail && (detail.id || detail.submissionId)) {
      if (detail.isPending === true) return;
      void finalizeFromCheck(detail.id || detail.submissionId, {
        state: detail.statusDisplay ? "SUCCESS" : detail.state,
        status_msg: detail.statusDisplay || detail.status_msg,
        status_code: detail.statusCode || detail.status_code,
        status_runtime: detail.runtime,
        status_memory: detail.memory,
        lang: detail.langName || detail.lang?.name || detail.lang,
        code: detail.code,
        question: detail.question || null,
        question_id:
          detail.question?.questionFrontendId || detail.question?.questionId || null,
        isPending: detail.isPending,
        statusDisplay: detail.statusDisplay,
      });
    }
  }

  function observeResponse(url, method, requestBodyText, responseText) {
    try {
      const full = absoluteUrl(url);
      if (interesting(full)) {
        debug("net", { method, url: full.slice(0, 160) });
      }
      if (String(method).toUpperCase() === "POST") {
        handleSubmitUrl(url, requestBodyText || "", responseText || "");
      }
      handleCheckUrl(url, responseText || "");
    } catch (err) {
      debug("observe error", { error: String(err) });
    }
  }

  // Patch fetch once (keep deepest original).
  if (!window.__leetcodeTrackerFetchPatched) {
    const originalFetch = window.fetch.bind(window);
    window.__leetcodeTrackerOriginalFetch = originalFetch;
    window.fetch = async function patchedFetch(input, init) {
      const url = typeof input === "string" ? input : input && input.url ? input.url : "";
      const method = (
        (init && init.method) ||
        (typeof input !== "string" && input && input.method) ||
        "GET"
      ).toUpperCase();
      const watch = interesting(absoluteUrl(url));

      let requestBodyText = "";
      if (watch && method === "POST") {
        try {
          requestBodyText = await readRequestBodySafe(input, init);
        } catch {
          requestBodyText = "";
        }
      }

      const response = await originalFetch(input, init);

      // 只观察力扣 submit/check 等；其余请求绝不 clone，避免干扰页面
      if (!watch) return response;
      try {
        if (!response || response.bodyUsed) return response;
        let clone;
        try {
          clone = response.clone();
        } catch (_err) {
          return response;
        }
        clone
          .text()
          .then((text) => observeResponse(url, method, requestBodyText, text))
          .catch(() => {});
      } catch {
        // ignore
      }
      return response;
    };
    window.__leetcodeTrackerFetchPatched = true;
  }

  if (!window.__leetcodeTrackerXhrPatched) {
    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__ltMethod = method;
      this.__ltUrl = url;
      return originalOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      this.addEventListener("load", function () {
        try {
          const url = String(this.__ltUrl || "");
          if (!interesting(absoluteUrl(url))) return;
          // responseType 非 text 时读 responseText 会抛 InvalidStateError
          const rt = String(this.responseType || "");
          let text = "";
          if (!rt || rt === "text" || rt === "") {
            text = this.responseText || "";
          } else if (rt === "json") {
            text = JSON.stringify(this.response || {});
          } else {
            return;
          }
          const method = String(this.__ltMethod || "GET").toUpperCase();
          const requestBodyText = typeof body === "string" ? body : "";
          observeResponse(url, method, requestBodyText, text);
        } catch (_err) {
          // 绝不能把异常冒泡到力扣页面逻辑
        }
      });
      return originalSend.apply(this, arguments);
    };
    window.__leetcodeTrackerXhrPatched = true;
  }

  window.__leetcodeTrackerInjected = true;
  startProblemMetaWatch();
  if (!window.__leetcodeTrackerCleanerStarted) {
    window.__leetcodeTrackerCleanerStarted = true;
    setInterval(pruneMaps, CLEAN_EVERY_MS);
  }
  debug("hooks ready @ document_start", { href: String(location.href) });
})();
