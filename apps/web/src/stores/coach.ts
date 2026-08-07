import { defineStore } from "pinia";
import { ref } from "vue";
import { fetchHealth, userHeaders } from "@/api/client";
import { readSse } from "@/composables/useSse";

export interface ConfirmChoice {
  id: string;
  label: string;
  text: string;
}

export interface ChatMessage {
  id: number;
  role: "coach" | "user" | "system";
  text: string;
  choices?: ConfirmChoice[];
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

  function showBanner(text: string) {
    banner.value = text;
    bannerVisible.value = true;
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
  }

  async function loadCachedSession(
    submissionId: string | null,
    pid: string | null,
  ) {
    const query = submissionId
      ? `submission_id=${encodeURIComponent(submissionId)}`
      : `problem_id=${encodeURIComponent(pid || "")}`;
    const res = await fetch(`/api/coach/session?${query}`);
    if (!res.ok) return null;
    return res.json();
  }

  let preparePromise: Promise<Record<string, unknown>> | null = null;

  async function prepare(
    submissionId: string,
    pid: string | null,
    mode: string,
  ) {
    if (preparePromise) return preparePromise;
    preparePromise = (async () => {
      showBanner("正在创建陪练会话…");
      const body: Record<string, unknown> = {
        submission_id: submissionId || "",
        problem_id: pid ? Number(pid) : null,
      };
      if (mode) body.mode = mode;
      const res = await fetch("/api/coach/prepare", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || "无法开始陪练");
      return data as Record<string, unknown>;
    })();
    try {
      return await preparePromise;
    } finally {
      preparePromise = null;
    }
  }

  async function startWithMode(mode: string, actionParam = "") {
    const data = await prepare("", null, mode);
    sessionId.value = String(data.session_id || "");
    addMsg(String(data.opening || ""), "coach");
    hideBanner();
    enableComposer();
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
      sessionId.value = String(data.session_id);
      addMsg(String(data.opening || ""), "coach");
      hideBanner();
      enableComposer();
      return;
    }
    data = await prepare(submissionId, pid, "");
    sessionId.value = String(data.session_id || "");
    addMsg(String(data.opening || ""), "coach");
    if (data.fallback_used) {
      showBanner(
        `指定提交未找到，已使用本题最近提交 ${data.resolved_submission_id} 启动陪练。`,
      );
    } else if (data.opening_source === "template") {
      showBanner("会话已就绪，发送消息后开始对话。");
    } else {
      hideBanner();
    }
    enableComposer();
  }

  async function loadHint(pid: string) {
    const res = await fetch(`/api/coach/hint/${encodeURIComponent(pid)}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || "hint failed");
    if (data.latest_submission_id) {
      await startWithSubmission(
        String(data.latest_submission_id),
        String(pid),
      );
      return;
    }
    addMsg(data.suggestion || "暂无建议", "coach");
    showBanner("尚无可用提交记录；在力扣提交后会自动关联最近结果。");
  }

  function clearChoices() {
    for (const m of messages.value) {
      if (m.choices?.length) m.choices = undefined;
    }
  }

  async function sendPayload(opts: { text?: string; action?: string }) {
    if (!sessionId.value || busy.value) return;
    const action = opts.action || "";
    const text = opts.text || "";
    const hasAction = Boolean(action);
    const hasText = Boolean(text.trim());
    if (!hasAction && !hasText) return;

    busy.value = true;
    clearChoices();

    if (hasText) addMsg(text.trim(), "user");
    else if (hasAction) {
      addMsg(`〔${ACTION_LABELS[action] || action}〕`, "user");
    }
    inputText.value = "";
    const coachMsg = addMsg("", "coach");

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
        }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        showBanner(data.message || "发送失败");
        removeMsg(coachMsg.id);
        if (data.reopen_required || data.code === "session_abandoned") {
          disableComposer();
          sessionId.value = "";
        }
        return;
      }
      let finished = false;
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
          showBanner(String(data.message || "模型暂时不可用，已切换到备用回复。"));
          if (data.reopen_required || data.session_abandoned) {
            disableComposer();
            sessionId.value = "";
          }
        } else if (event === "error" || data.type === "error") {
          showBanner(String(data.message || "对话出错"));
          if (data.reopen_required || data.code === "session_abandoned") {
            disableComposer();
            sessionId.value = "";
          }
        } else if (event === "done" || data.type === "done") {
          finished = true;
          if (data.done) {
            disableComposer();
            sessionId.value = "";
            showBanner("本轮已结束。重新打开陪练可开始新会话。");
          }
        }
      });
      const m = messages.value.find((x) => x.id === coachMsg.id);
      if (!m?.text) {
        if (!finished) updateMsg(coachMsg.id, "（无回复）");
        else removeMsg(coachMsg.id);
      }
    } catch (err) {
      removeMsg(coachMsg.id);
      showBanner(err instanceof Error ? err.message : "发送失败");
    } finally {
      busy.value = false;
    }
  }

  /** 点击确认选项：把固定文案嵌入输入并作为用户消息发出 */
  async function sendChoice(choice: ConfirmChoice) {
    const text = (choice.text || "").trim();
    if (!text) return;
    inputText.value = text;
    await sendPayload({ text });
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
        showBanner("陪练暂时不可用，请稍后再试");
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
        showBanner("学习资料仍在准备中，陪练可以先用");
      }
      if (params.session) {
        sessionId.value = params.session;
        addMsg(`继续会话 ${params.session}（直接输入即可）`, "coach");
        enableComposer();
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
      showBanner("请从题目页或仪表盘进入陪练");
    } catch {
      showBanner("暂时无法连接服务，请稍后再试");
    }
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
    showBanner,
    hideBanner,
    addMsg,
    enableComposer,
    disableComposer,
    sendPayload,
    sendChoice,
    init,
  };
});
