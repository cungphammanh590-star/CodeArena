<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { storeToRefs } from "pinia";
import AppHeader from "@/components/AppHeader.vue";
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
} = storeToRefs(coach);

const chatEl = ref<HTMLElement | null>(null);

const ready = computed(() => Boolean(sessionId.value) && !busy.value);
const isApi = computed(() => graphMode.value === "api");

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
  return coach.sendPayload({ text: inputText.value, action: "" });
}

function onKey(e: KeyboardEvent) {
  if (e.key === "Enter") sendMessage();
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
  <AppHeader title="刷题陪练" subtitle="围绕当前题目复盘与答疑" narrow />

  <main class="page-main narrow">
    <div v-if="bannerVisible" class="banner">{{ banner }}</div>

    <div ref="chatEl" class="chat">
      <div
        v-for="m in messages"
        :key="m.id"
        class="msg"
        :class="m.role"
      >
        <div class="msg-text">{{ m.text }}</div>
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

    <div class="actions">
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
        placeholder="说说你的怀疑点…"
        :disabled="!composerEnabled || busy"
        @keydown="onKey"
      />
      <button
        type="button"
        class="btn-coach"
        :disabled="!composerEnabled || busy"
        @click="sendMessage"
      >
        发送
      </button>
    </div>

    <p class="sub" style="margin-top: 12px">
      <RouterLink to="/">返回仪表盘</RouterLink>
      · 也可输入「结束」收束
    </p>
  </main>
</template>

<style scoped>
.banner {
  background: #f7ead8;
  border: 1px solid #e8c9a0;
  color: #9a6b3a;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 13px;
  margin-bottom: 12px;
}
.chat {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  min-height: 360px;
  max-height: 60vh;
  overflow: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.msg {
  max-width: 92%;
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}
.msg.coach {
  background: #ece7f6;
  color: #5d5285;
  align-self: flex-start;
}
.msg.user {
  background: var(--accent-soft);
  color: #8a5560;
  align-self: flex-end;
}
.msg.system {
  background: #f5f5f4;
  color: #57534e;
  align-self: center;
  max-width: 100%;
  font-size: 13px;
  border: 1px dashed var(--line);
}
.choice-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  white-space: normal;
}
.choice-chip {
  border: 1px solid #c4b8e0;
  background: #fff;
  color: #5d5285;
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.choice-chip:hover:not(:disabled) {
  background: #f3effa;
}
.choice-chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.composer {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.composer input {
  flex: 1;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
  font: inherit;
}
body {
  background:
    radial-gradient(1100px 480px at 8% -10%, var(--wash-mint) 0%, transparent 55%),
    radial-gradient(860px 400px at 100% 0%, #e8dff466 0%, transparent 50%),
    var(--bg);
}
</style>
