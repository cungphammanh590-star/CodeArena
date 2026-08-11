<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { storeToRefs } from "pinia";
import AppHeader from "@/components/AppHeader.vue";
import MarkdownBody from "@/components/MarkdownBody.vue";
import { useCoachStore, type ConfirmChoice } from "@/stores/coach";

const route = useRoute();
const coach = useCoachStore();
const {
  sessionId,
  busy,
  graphMode,
  banner,
  bannerVisible,
  messages,
  composerEnabled,
  inputText,
  awaitingAskUser,
  pendingAsk,
  askDrafts,
  latestSolve,
} = storeToRefs(coach);

const chatEl = ref<HTMLElement | null>(null);

const ready = computed(() => Boolean(sessionId.value) && !busy.value);
const isApi = computed(() => graphMode.value === "api");
const primaryLabel = computed(() =>
  awaitingAskUser.value ? "提交回答" : "发送",
);

const endLabel = computed(() =>
  isApi.value ? "结束并诊断" : "结束本轮",
);

watch(
  messages,
  async () => {
    await nextTick();
    if (chatEl.value) {
      chatEl.value.scrollTop = chatEl.value.scrollHeight;
    }
  },
  { deep: true },
);

function q(name: string): string | null {
  const v = route.query[name];
  if (Array.isArray(v)) return v[0] ?? null;
  return v ?? null;
}

function sendMessage() {
  if (awaitingAskUser.value && pendingAsk.value) {
    return coach.submitAskUser();
  }
  return coach.sendPayload({ text: inputText.value, action: "" });
}

function onKey(e: KeyboardEvent) {
  if (e.key !== "Enter") return;
  // IME 组字确认（选词/选英文候选）时不要发送
  if (e.isComposing || e.keyCode === 229) return;
  e.preventDefault();
  sendMessage();
}

function onChoice(choice: ConfirmChoice) {
  return coach.sendChoice(choice);
}

onMounted(() => {
  coach.init({
    submission: q("submission"),
    problemId: q("problem_id"),
    mode: q("mode") || "",
    action: q("action") || "",
    session: q("session"),
  });
});
</script>

<template>
  <AppHeader title="陪练" subtitle="卡住就说，我们一起拆" narrow />

  <main class="page-main narrow coach-layout">
    <div v-if="bannerVisible" class="banner" role="status">{{ banner }}</div>

    <div ref="chatEl" class="chat" aria-live="polite">
      <p v-if="!messages.length" class="chat-empty">
        会话准备好后，消息会出现在这里。
      </p>
      <div
        v-for="m in messages"
        :key="m.id"
        class="msg"
        :class="m.role"
      >
        <MarkdownBody
          v-if="m.text"
          class="msg-text"
          :source="m.text"
          :markdown="m.role !== 'user'"
        />
        <ul v-if="m.solveProgress?.steps?.length" class="solve-steps">
          <li
            v-for="s in m.solveProgress.steps"
            :key="s.id"
            :class="{ done: s.done }"
          >
            <span class="sid">{{ s.id }}</span>
            {{ s.goal }}
            <span v-if="s.done" class="tick">完成</span>
          </li>
        </ul>
        <details
          v-if="m.codeResult"
          class="code-result"
          :class="{ ok: m.codeResult.exit_code === 0 && !m.codeResult.timed_out }"
        >
          <summary>
            运行结果 · {{ m.codeResult.language }} ·
            exit {{ m.codeResult.exit_code
            }}{{ m.codeResult.timed_out ? " · timeout" : "" }}
          </summary>
          <pre v-if="m.codeResult.stdout_preview">{{ m.codeResult.stdout_preview }}</pre>
          <pre v-if="m.codeResult.stderr_preview" class="err">{{
            m.codeResult.stderr_preview
          }}</pre>
          <pre v-if="m.codeResult.error" class="err">{{ m.codeResult.error }}</pre>
        </details>
        <div v-if="m.askUser?.questions?.length" class="ask-card">
          <p v-if="m.askUser.intro" class="ask-intro">{{ m.askUser.intro }}</p>
          <div
            v-for="aq in m.askUser.questions"
            :key="aq.id"
            class="ask-q"
          >
            <div class="ask-prompt">
              <span v-if="aq.header" class="ask-header">{{ aq.header }} · </span>
              {{ aq.prompt }}
            </div>
            <div v-if="aq.options?.length" class="choice-row">
              <button
                v-for="opt in aq.options"
                :key="opt.label"
                type="button"
                class="choice-chip"
                :disabled="busy || !sessionId"
                @click="coach.pickAskOption(aq.id, opt.label)"
              >
                {{ opt.label }}
              </button>
            </div>
            <input
              v-if="aq.allow_free_text !== false"
              class="ask-input"
              type="text"
              :placeholder="aq.placeholder || '可选补充…'"
              :value="askDrafts[aq.id] || ''"
              :disabled="busy || !sessionId"
              @input="
                coach.setAskDraft(
                  aq.id,
                  ($event.target as HTMLInputElement).value,
                )
              "
            />
          </div>
        </div>
        <div v-if="m.choices?.length" class="choice-row">
          <button
            v-for="c in m.choices"
            :key="c.id || c.text"
            type="button"
            class="choice-chip"
            :disabled="busy || !sessionId"
            :title="c.text"
            @click="onChoice(c)"
          >
            {{ c.label }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="latestSolve?.steps?.length" class="solve-rail" aria-label="解题步骤">
      <span
        v-for="s in latestSolve.steps"
        :key="s.id"
        class="solve-pill"
        :class="{ done: s.done }"
      >
        {{ s.id }}{{ s.done ? " · 完成" : "" }}
      </span>
    </div>

    <div class="actions" role="toolbar" aria-label="快捷动作">
      <button
        type="button"
        class="btn-secondary"
        :disabled="!ready"
        :title="isApi ? '输出诊断并结束本会话' : '收束本轮并结束会话'"
        @click="
          coach.sendPayload({
            action: isApi ? 'diagnose' : 'close',
          })
        "
      >
        {{ endLabel }}
      </button>
      <button
        type="button"
        class="btn-secondary"
        :disabled="!ready"
        @click="coach.sendPayload({ action: 'daily_review' })"
      >
        今日总结
      </button>
      <button
        type="button"
        class="btn-secondary"
        :disabled="!ready"
        @click="coach.sendPayload({ action: 'review' })"
      >
        今日复习
      </button>
      <button
        type="button"
        class="btn-secondary"
        :disabled="!ready"
        @click="coach.sendPayload({ action: 'recommend' })"
      >
        推荐下一题
      </button>
      <button
        v-show="!isApi"
        type="button"
        class="btn-secondary"
        :disabled="!ready"
        title="随时可看思路；失稳后会高亮提示"
        @click="coach.sendPayload({ action: 'show_skeleton' })"
      >
        看思路
      </button>
      <button
        v-show="isApi"
        type="button"
        class="btn-secondary"
        :disabled="!ready"
        @click="coach.sendPayload({ action: 'deep_analysis' })"
      >
        查看精析
      </button>
    </div>

    <div class="composer">
      <input
        v-model="inputText"
        type="text"
        :placeholder="
          awaitingAskUser ? '补充回答，或点上方选项…' : '说说你的卡点…'
        "
        :disabled="!composerEnabled || busy"
        @keydown="onKey"
      />
      <button
        v-if="!composerEnabled && !busy"
        type="button"
        class="btn-secondary"
        @click="coach.reopenLobby()"
      >
        开始新会话
      </button>
      <button
        type="button"
        class="btn-coach"
        :disabled="!composerEnabled || busy"
        @click="sendMessage"
      >
        {{ busy ? "发送中…" : primaryLabel }}
      </button>
    </div>
  </main>
</template>

<style scoped>
.coach-layout {
  display: flex;
  flex-direction: column;
  padding-bottom: 32px;
}
.banner {
  background: var(--warn-soft);
  border: 1px solid #fcd34d;
  color: var(--warn);
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  margin-bottom: 12px;
}
.chat {
  background: color-mix(in srgb, var(--card) 80%, #fff);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  min-height: 380px;
  max-height: min(62vh, 640px);
  overflow: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: none;
}
.chat-empty {
  margin: auto;
  color: var(--muted);
  font-size: 13px;
}
.msg {
  max-width: 88%;
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.55;
}
.msg.user .msg-text {
  white-space: pre-wrap;
}
.msg.coach {
  background: var(--coach-bubble);
  color: var(--ink);
  align-self: flex-start;
  border-bottom-left-radius: 4px;
}
.msg.user {
  background: var(--user-bubble);
  color: var(--ink);
  align-self: flex-end;
  border-bottom-right-radius: 4px;
}
.msg.system {
  background: var(--soft);
  color: var(--muted);
  align-self: center;
  max-width: 100%;
  font-size: 13px;
  border: 1px dashed var(--line);
}
.choice-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.choice-chip {
  border: 1px solid var(--line);
  background: #fff;
  color: var(--ink);
  border-radius: 999px;
  padding: 8px 12px;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  min-height: 40px;
}
.choice-chip:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
.choice-chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.solve-steps {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  font-size: 13px;
}
.solve-steps li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  opacity: 0.85;
  padding: 2px 0;
}
.solve-steps li.done {
  opacity: 1;
}
.solve-steps .sid {
  font-weight: 600;
  min-width: 1.6em;
  color: var(--accent);
}
.solve-steps .tick {
  color: var(--ok);
  font-size: 12px;
}
.solve-rail {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.solve-pill {
  font-size: 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 10px;
  color: var(--muted);
  background: var(--card);
}
.solve-pill.done {
  border-color: #a7f3d0;
  color: var(--ok);
  background: var(--ok-soft);
}
.code-result {
  margin-top: 8px;
  font-size: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 6px 8px;
  background: #fafafa;
}
.code-result.ok {
  border-color: #a7f3d0;
}
.code-result pre {
  margin: 6px 0 0;
  white-space: pre-wrap;
  font-size: 12px;
}
.code-result .err {
  color: var(--danger);
}
.ask-card {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ask-intro {
  margin: 0;
  font-size: 13px;
}
.ask-prompt {
  font-size: 13px;
  margin-bottom: 4px;
}
.ask-header {
  font-weight: 600;
}
.ask-input {
  width: 100%;
  margin-top: 6px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
  font: inherit;
  font-size: 13px;
  box-sizing: border-box;
  min-height: 40px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.composer {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  position: sticky;
  bottom: 0;
  padding-top: 4px;
  background: linear-gradient(180deg, transparent, var(--bg) 28%);
}
.composer input {
  flex: 1;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  background: #fff;
  font: inherit;
  min-height: 48px;
  box-shadow: var(--shadow);
}
.composer input:focus {
  outline: 2px solid color-mix(in srgb, var(--accent) 30%, transparent);
  border-color: var(--accent);
}
</style>
