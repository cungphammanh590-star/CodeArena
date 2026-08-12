import { defineStore } from "pinia";
import { ref } from "vue";
import { fetchHealth, userHeaders } from "@/api/client";
import { readSse } from "@/composables/useSse";
import { toUserMessage } from "@/utils/userMessage";

export interface ConfirmChoice {
  id: string;
  label: string;
  text: string;
}

export interface AskQuestion {
  id: string;
  prompt: string;
  header?: string;
  options?: { label: string; description?: string }[];
  multi_select?: boolean;
  allow_free_text?: boolean;
  placeholder?: string;
}

export interface AskUserPayload {
  intro?: string;
  questions: AskQuestion[];
}

export interface SolveStepView {
  id: string;
  goal: string;
  done: boolean;
  summary?: string;
}

export interface SolveProgress {
  analysis?: string;
  steps: SolveStepView[];
  next?: SolveStepView | null;
  all_done?: boolean;
}

export interface CodeResultView {
  language: string;
  exit_code: number;
  timed_out: boolean;
  stdout_preview?: string;
  stderr_preview?: string;
  error?: string;
}

export interface ChatMessage {
  id: number;
  role: "coach" | "user" | "system";
  text: string;
  choices?: ConfirmChoice[];
  askUser?: AskUserPayload;
  solveProgress?: SolveProgress;
  codeResult?: CodeResultView;
}

export interface CoachSessionItem {
  session_id: string;
  title: string;
  problem_id?: number | null;
  topic?: string | null;
  session_kind?: string;
  status?: string;
  summary?: string;
  updated_at?: string | null;
}

let msgSeq = 0;

const ACTION_LABELS: Record<string, string> = {
  close: "结束本轮",
  show_skeleton: "看思路",
  diagnose: "结束并诊断",
  deep_analysis: "查看精析",
  daily_review: "今日总结",
  recommend: "推荐下一题",
  review: "今日复习",
  optimize: "优化分析",
};

const SESSION_STORAGE_KEY = "codearena.coach.last_session_id";

function rememberSessionId(id: string) {
  const sid = id.trim();
  if (!sid || typeof sessionStorage === "undefined") return;
  try {
    sessionStorage.setItem(SESSION_STORAGE_KEY, sid);
  } catch {
    /* ignore quota / private mode */
  }
}

function forgetSessionId() {
  if (typeof sessionStorage === "undefined") return;
  try {
    sessionStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    /* ignore */
  }
}

function readRememberedSessionId(): string {
  if (typeof sessionStorage === "undefined") return "";
  try {
    return String(sessionStorage.getItem(SESSION_STORAGE_KEY) || "").trim();
  } catch {
    return "";
  }
}

export const useCoachStore = defineStore("coach", () => {
  const sessionId = ref("");
  const busy = ref(false);
  const graphMode = ref<"local" | "api" | "smart" | "classic">("local");
  const exitOffered = ref(false);
  const banner = ref("");
  const bannerVisible = ref(false);
  const messages = ref<ChatMessage[]>([]);
  const composerEnabled = ref(false);
  const inputText = ref("");
  const awaitingAskUser = ref(false);
  const pendingAsk = ref<AskUserPayload | null>(null);
  const askDrafts = ref<Record<string, string>>({});
  const latestSolve = ref<SolveProgress | null>(null);
  const sessions = ref<CoachSessionItem[]>([]);
  const sessionsLoading = ref(false);

  let streamAbort: AbortController | null = null;
  let unloadGuardAttached = false;

  function onBeforeUnload(e: BeforeUnloadEvent) {
    if (!busy.value) return;
    e.preventDefault();
    e.returnValue = "";
  }

  function attachUnloadGuard() {
    if (unloadGuardAttached || typeof window === "undefined") return;
    window.addEventListener("beforeunload", onBeforeUnload);
    unloadGuardAttached = true;
  }

  function detachUnloadGuard() {
    if (!unloadGuardAttached || typeof window === "undefined") return;
    window.removeEventListener("beforeunload", onBeforeUnload);
    unloadGuardAttached = false;
  }

  function showBanner(text: string) {
    banner.value = text;
    bannerVisible.value = true;
  }

  function showErrorBanner(err: unknown, fallback = "出了点问题，请稍后再试") {
    showBanner(toUserMessage(err, fallback));
  }

  function hideBanner() {
    banner.value = "";
    bannerVisible.value = false;
  }

  function addMsg(text: string, role: ChatMessage["role"]) {
    const msg: ChatMessage = { id: ++msgSeq, role, text };
    messages.value.push(msg);
    return msg;
  }

  function updateMsg(id: number, text: string) {
    const m = messages.value.find((x) => x.id === id);
    if (m) m.text = text;
  }

  function appendMsg(id: number, text: string) {
    const m = messages.value.find((x) => x.id === id);
    if (m) m.text += text;
  }

  function removeMsg(id: number) {
    messages.value = messages.value.filter((x) => x.id !== id);
  }

  function enableComposer() {
    composerEnabled.value = true;
  }

  function disableComposer() {
    composerEnabled.value = false;
    forgetSessionId();
  }

  function clearChatUi() {
    messages.value = [];
    awaitingAskUser.value = false;
    pendingAsk.value = null;
    askDrafts.value = {};
    latestSolve.value = null;
    exitOffered.value = false;
    inputText.value = "";
  }

  function applyTurns(turns: unknown) {
    if (!Array.isArray(turns) || turns.length === 0) return false;
    clearChatUi();
    for (const raw of turns) {
      if (!raw || typeof raw !== "object") continue;
      const t = raw as Record<string, unknown>;
      const roleRaw = String(t.role || "").toLowerCase();
      const content = String(t.content || "").trim();
      if (!content) continue;
      let role: ChatMessage["role"] = "system";
      if (roleRaw === "user" || roleRaw === "human") role = "user";
      else if (
        roleRaw === "assistant" ||
        roleRaw === "coach" ||
        roleRaw === "ai" ||
        roleRaw === "model"
      ) {
        role = "coach";
      }
      addMsg(content, role);
    }
    return messages.value.length > 0;
  }

  function applySessionPayload(
    data: Record<string, unknown>,
    opts?: { fallbackOpening?: string },
  ) {
    sessionId.value = String(data.session_id || "");
    if (sessionId.value) rememberSessionId(sessionId.value);
    const hasTurns = applyTurns(data.turns);
    if (!hasTurns) {
      clearChatUi();
      const opening =
        opts?.fallbackOpening ||
        String(data.opening || "").trim() ||
        "会话已就绪，直接输入即可。";
      if (opening) addMsg(opening, "coach");
    }
    enableComposer();
    hideBanner();
  }

  async function loadSessions() {
    sessionsLoading.value = true;
    try {
      const res = await fetch("/api/coach/sessions?limit=20", {
        headers: userHeaders(),
      });
      if (!res.ok) {
        sessions.value = [];
        return;
      }
      const data = await res.json();
      sessions.value = Array.isArray(data.sessions) ? data.sessions : [];
    } catch {
      sessions.value = [];
    } finally {
      sessionsLoading.value = false;
    }
  }

  async function openSession(id: string) {
    const sid = id.trim();
    if (!sid || busy.value) return;
    showBanner("正在打开会话…");
    try {
      const res = await fetch(
        `/api/coach/session?session_id=${encodeURIComponent(sid)}`,
        { headers: userHeaders() },
      );
      const data = await res.json();
      if (!res.ok) {
        throw new Error(toUserMessage(data.message, "无法打开该会话"));
      }
      applySessionPayload(data as Record<string, unknown>);
      await loadSessions();
    } catch (err) {
      showErrorBanner(err, "无法打开该会话");
    }
  }

  async function loadCachedSession(
    submissionId: string | null,
    pid: string | null,
  ) {
    const query = submissionId
      ? `submission_id=${encodeURIComponent(submissionId)}`
      : `problem_id=${encodeURIComponent(pid || "")}`;
    const res = await fetch(`/api/coach/session?${query}`, {
      headers: userHeaders(),
    });
    if (!res.ok) return null;
    return res.json();
  }

  let preparePromise: Promise<Record<string, unknown>> | null = null;

  async function prepare(
    submissionId: string,
    pid: string | null,
    mode: string,
    opts?: { forceNew?: boolean },
  ) {
    if (preparePromise) return preparePromise;
    preparePromise = (async () => {
      showBanner(opts?.forceNew ? "正在开启新会话…" : "正在准备 Nex…");
      const body: Record<string, unknown> = {
        submission_id: submissionId || "",
        problem_id: pid ? Number(pid) : null,
      };
      if (mode) body.mode = mode;
      if (opts?.forceNew) body.force_new = true;
      const res = await fetch("/api/coach/prepare", {
        method: "POST",
        headers: userHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) {
        throw new Error(toUserMessage(data.message, "暂时无法开始 Nex，请稍后再试"));
      }
      return data as Record<string, unknown>;
    })();
    try {
      return await preparePromise;
    } finally {
      preparePromise = null;
    }
  }

  async function startWithMode(mode: string, actionParam = "", forceNew = false) {
    const data = await prepare("", null, mode, { forceNew });
    applySessionPayload(data);
    await loadSessions();
    const bootAction = actionParam || mode;
    if (
      bootAction === "daily_review" ||
      bootAction === "recommend" ||
      bootAction === "review"
    ) {
      await sendPayload({ action: bootAction });
    }
  }

  async function startWithSubmission(
    submissionId: string,
    pid: string | null,
  ) {
    let data = await loadCachedSession(submissionId, pid);
    if (data && data.session_id) {
      applySessionPayload(data as Record<string, unknown>);
      await loadSessions();
      return;
    }
    data = await prepare(submissionId, pid, "");
    applySessionPayload(data as Record<string, unknown>);
    if (data.fallback_used) {
      showBanner(
        `指定提交未找到，已使用本题最近提交 ${data.resolved_submission_id} 启动 Nex。`,
      );
    }
    await loadSessions();
  }

  async function startWithProblem(pid: string, openingHint?: string) {
    const data = await prepare("", pid, "");
    applySessionPayload(data, {
      fallbackOpening:
        openingHint ||
        String(data.opening || "") ||
        `已就题目 ${pid} 开启 Nex，可以直接提问。`,
    });
    await loadSessions();
  }

  async function loadHint(pid: string) {
    const res = await fetch(`/api/coach/hint/${encodeURIComponent(pid)}`, {
      headers: userHeaders(),
    });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(toUserMessage(data.message, "暂时无法加载题目提示，请稍后再试"));
    }
    if (data.latest_submission_id) {
      await startWithSubmission(
        String(data.latest_submission_id),
        String(pid),
      );
      return;
    }
    // 无本地提交：仍按题号开会话，允许聊天（计划/思路）
    showBanner(
      String(
        data.suggestion ||
          "本题暂无同步到的提交；仍可开聊。扩展同步成功后会自动关联代码。",
      ),
    );
    await startWithProblem(
      String(pid),
      String(data.suggestion || data.hint || ""),
    );
  }

  function clearChoices() {
    for (const m of messages.value) {
      if (m.choices?.length) m.choices = undefined;
    }
  }

  async function sendPayload(opts: {
    text?: string;
    action?: string;
    answers?: { question_id: string; text: string }[];
  }) {
    if (!sessionId.value || busy.value) return;
    const action = opts.action || "";
    const text = opts.text || "";
    const answers = opts.answers || [];
    const hasAction = Boolean(action);
    const hasText = Boolean(text.trim());
    const hasAnswers = answers.length > 0;
    if (!hasAction && !hasText && !hasAnswers) return;

    busy.value = true;
    attachUnloadGuard();
    clearChoices();

    if (hasAnswers) {
      const summary = answers.map((a) => a.text).filter(Boolean).join("；");
      addMsg(summary || "【已提交澄清回答】", "user");
    } else if (hasText) addMsg(text.trim(), "user");
    else if (hasAction) {
      addMsg(`〔${ACTION_LABELS[action] || action}〕`, "user");
    }
    inputText.value = "";
    const coachMsg = addMsg("", "coach");
    let finished = false;
    let cancelled = false;
    const ac = new AbortController();
    streamAbort = ac;

    try {
      const res = await fetch("/api/coach/stream", {
        method: "POST",
        headers: userHeaders({
          "Content-Type": "application/json",
          Accept: "text/event-stream",
        }),
        body: JSON.stringify({
          session_id: sessionId.value,
          message: hasText ? text.trim() : "",
          action: action || "",
          answers: hasAnswers ? answers : undefined,
        }),
        signal: ac.signal,
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        showErrorBanner(data.message, "发送失败，请稍后再试");
        removeMsg(coachMsg.id);
        if (data.reopen_required || data.code === "session_abandoned") {
          disableComposer();
          sessionId.value = "";
        }
        return;
      }
      await readSse(res, (event, data) => {
        if (event === "ready" || data.type === "ready") {
          if (
            data.graph === "api" ||
            data.graph === "local" ||
            data.graph === "smart" ||
            data.graph === "classic"
          ) {
            graphMode.value = data.graph;
          }
        } else if (event === "status" || data.type === "status") {
          // 阶段/意图/路由：不进聊天气泡，避免刷屏
        } else if (event === "info" || data.type === "info") {
          const content = String(data.content || "").trim();
          if (content) addMsg(content, "system");
        } else if (event === "confirm" || data.type === "confirm") {
          const prompt = String(data.prompt || "").trim();
          const rawChoices = Array.isArray(data.choices) ? data.choices : [];
          const choices: ConfirmChoice[] = rawChoices
            .map((c: Record<string, unknown>) => ({
              id: String(c.id || ""),
              label: String(c.label || ""),
              text: String(c.text || ""),
            }))
            .filter((c: ConfirmChoice) => c.text && c.label);
          if (prompt) updateMsg(coachMsg.id, prompt);
          const m = messages.value.find((x) => x.id === coachMsg.id);
          if (m) m.choices = choices;
        } else if (event === "ask_user" || data.type === "ask_user") {
          const questions = Array.isArray(data.questions) ? data.questions : [];
          const payload: AskUserPayload = {
            intro: data.intro ? String(data.intro) : undefined,
            questions: questions.map((q: Record<string, unknown>, i: number) => ({
              id: String(q.id || `q${i + 1}`),
              prompt: String(q.prompt || ""),
              header: q.header ? String(q.header) : undefined,
              options: Array.isArray(q.options)
                ? q.options.map((o: Record<string, unknown> | string) =>
                    typeof o === "string"
                      ? { label: o }
                      : {
                          label: String(o.label || ""),
                          description: o.description
                            ? String(o.description)
                            : undefined,
                        },
                  )
                : undefined,
              multi_select: Boolean(q.multi_select),
              allow_free_text: q.allow_free_text !== false,
              placeholder: q.placeholder ? String(q.placeholder) : undefined,
            })),
          };
          pendingAsk.value = payload;
          awaitingAskUser.value = true;
          askDrafts.value = {};
          const m = messages.value.find((x) => x.id === coachMsg.id);
          if (m) {
            if (payload.intro) updateMsg(coachMsg.id, payload.intro);
            m.askUser = payload;
          }
        } else if (event === "solve_progress" || data.type === "solve_progress") {
          const steps = Array.isArray(data.steps) ? data.steps : [];
          const progress: SolveProgress = {
            analysis: data.analysis ? String(data.analysis) : undefined,
            steps: steps.map((s: Record<string, unknown>) => ({
              id: String(s.id || ""),
              goal: String(s.goal || ""),
              done: Boolean(s.done),
              summary: s.summary ? String(s.summary) : undefined,
            })),
            next: (() => {
              const n = data.next as Record<string, unknown> | null | undefined;
              if (!n || typeof n !== "object") return null;
              return {
                id: String(n.id || ""),
                goal: String(n.goal || ""),
                done: Boolean(n.done),
              };
            })(),
            all_done: Boolean(data.all_done),
          };
          latestSolve.value = progress;
          const m = messages.value.find((x) => x.id === coachMsg.id);
          if (m) m.solveProgress = progress;
        } else if (event === "code_result" || data.type === "code_result") {
          const cr: CodeResultView = {
            language: String(data.language || "python"),
            exit_code: Number(data.exit_code ?? -1),
            timed_out: Boolean(data.timed_out),
            stdout_preview: data.stdout_preview
              ? String(data.stdout_preview)
              : undefined,
            stderr_preview: data.stderr_preview
              ? String(data.stderr_preview)
              : undefined,
            error: data.error ? String(data.error) : undefined,
          };
          const m = messages.value.find((x) => x.id === coachMsg.id);
          if (m) m.codeResult = cr;
        } else if (
          event === "token" ||
          data.type === "token" ||
          event === "answer_egress" ||
          data.type === "answer_egress" ||
          event === "diagnose" ||
          data.type === "diagnose" ||
          event === "deep_analysis" ||
          data.type === "deep_analysis"
        ) {
          const piece = data.text ?? data.delta;
          if (piece) {
            if (data.replace) updateMsg(coachMsg.id, String(piece));
            else appendMsg(coachMsg.id, String(piece));
          }
        } else if (event === "offer_exit" || data.type === "offer_exit") {
          exitOffered.value = true;
          showBanner(String(data.message || "可以结束或看思路了"));
        } else if (event === "fallback" || data.type === "fallback") {
          if (data.text) appendMsg(coachMsg.id, String(data.text));
          showBanner(
            toUserMessage(
              data.message,
              "模型暂时不可用，已切换到备用回复。",
            ),
          );
          if (data.reopen_required || data.session_abandoned) {
            disableComposer();
            sessionId.value = "";
          }
        } else if (event === "error" || data.type === "error") {
          showErrorBanner(data.message, "对话出了点问题，请稍后再试");
          if (data.reopen_required || data.code === "session_abandoned") {
            disableComposer();
            sessionId.value = "";
          }
        } else if (event === "done" || data.type === "done") {
          finished = true;
          if (data.cancelled) cancelled = true;
          if (data.awaiting === "ask_user") {
            awaitingAskUser.value = true;
          } else if (!data.cancelled) {
            awaitingAskUser.value = false;
            pendingAsk.value = null;
          }
          if (data.done) {
            disableComposer();
            sessionId.value = "";
            showBanner("本轮已结束。重新打开 Nex 可开始新会话。");
          } else if (data.cancelled) {
            showBanner("已停止生成");
          }
        }
      });
      const m = messages.value.find((x) => x.id === coachMsg.id);
      if (!m?.text && !m?.askUser && !m?.solveProgress && !m?.codeResult) {
        removeMsg(coachMsg.id);
      }
    } catch (err) {
      if (ac.signal.aborted) {
        cancelled = true;
        const m = messages.value.find((x) => x.id === coachMsg.id);
        if (!m?.text && !m?.askUser && !m?.solveProgress && !m?.codeResult) {
          removeMsg(coachMsg.id);
        }
        showBanner("已停止生成");
      } else {
        removeMsg(coachMsg.id);
        showErrorBanner(err, "发送失败，请稍后再试");
      }
    } finally {
      if (streamAbort === ac) streamAbort = null;
      busy.value = false;
      detachUnloadGuard();
      if (finished && !cancelled) void loadSessions();
    }
  }

  /** 停止当前流式生成（断开 SSE；后端会修 checkpoint） */
  function stopGeneration() {
    if (!busy.value) return;
    streamAbort?.abort();
    streamAbort = null;
  }

  /** 点击确认选项：把固定文案嵌入输入并作为用户消息发出 */
  async function sendChoice(choice: ConfirmChoice) {
    const text = (choice.text || "").trim();
    if (!text) return;
    inputText.value = text;
    await sendPayload({ text });
  }

  function setAskDraft(questionId: string, text: string) {
    askDrafts.value = { ...askDrafts.value, [questionId]: text };
  }

  function pickAskOption(questionId: string, label: string) {
    const q = pendingAsk.value?.questions.find((x) => x.id === questionId);
    if (q?.multi_select) {
      const cur = askDrafts.value[questionId] || "";
      const parts = cur ? cur.split("、").filter(Boolean) : [];
      if (!parts.includes(label)) parts.push(label);
      setAskDraft(questionId, parts.join("、"));
    } else {
      setAskDraft(questionId, label);
    }
  }

  async function submitAskUser() {
    if (!pendingAsk.value) return;
    const answers = pendingAsk.value.questions.map((q) => ({
      question_id: q.id,
      text: (askDrafts.value[q.id] || "").trim(),
    }));
    if (!answers.some((a) => a.text)) {
      showBanner("请至少回答一道题");
      return;
    }
    await sendPayload({ action: "submit_user_reply", answers });
  }

  async function init(params: {
    submission: string | null;
    problemId: string | null;
    mode: string;
    action: string;
    session: string | null;
  }) {
    try {
      const health = await fetchHealth();
      if (!health.coach_available) {
        showBanner("Nex 暂时不可用，请稍后再试");
        return;
      }
      graphMode.value = health.llm_provider === "api" ? "api" : "local";
      const profileMode =
        params.mode === "daily_review" ||
        params.mode === "recommend" ||
        params.mode === "review"
          ? params.mode
          : "";
      if (profileMode) {
        await startWithMode(profileMode, params.action);
        return;
      }
      if (!health.kg_imported) {
        showBanner("学习资料仍在准备中，Nex 可以先用");
      }
      if (params.session) {
        await openSession(params.session);
        return;
      }
      if (params.submission) {
        await startWithSubmission(params.submission, params.problemId);
        return;
      }
      if (params.problemId) {
        await loadHint(params.problemId);
        return;
      }
      // 无参进入：优先恢复刷新前会话，否则大厅
      const remembered = readRememberedSessionId();
      if (remembered) {
        await openSession(remembered);
        if (sessionId.value === remembered) {
          void loadSessions();
          return;
        }
        forgetSessionId();
      }
      await startWithMode("lobby");
      void loadSessions();
    } catch {
      showBanner("暂时无法连接服务，请稍后再试");
    }
  }

  async function reopenLobby() {
    stopGeneration();
    hideBanner();
    clearChatUi();
    sessionId.value = "";
    forgetSessionId();
    await startWithMode("lobby", "", true);
  }

  return {
    sessionId,
    busy,
    graphMode,
    exitOffered,
    banner,
    bannerVisible,
    messages,
    composerEnabled,
    inputText,
    awaitingAskUser,
    pendingAsk,
    askDrafts,
    latestSolve,
    sessions,
    sessionsLoading,
    showBanner,
    hideBanner,
    addMsg,
    enableComposer,
    disableComposer,
    sendPayload,
    stopGeneration,
    sendChoice,
    setAskDraft,
    pickAskOption,
    submitAskUser,
    init,
    reopenLobby,
    loadSessions,
    openSession,
  };
});
