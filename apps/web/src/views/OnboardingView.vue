<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import api from "@/api/client";
import { toUserMessage } from "@/utils/userMessage";

const router = useRouter();
const goal = ref("algorithm");
const minutes = ref(30);
const startMode = ref("path");
const busy = ref(false);
const error = ref("");
const extensionState = ref<"unknown" | "missing" | "ready" | "login">("unknown");
const extensionPending = ref(0);
let probeTimer: number | undefined;

function onExtensionMessage(event: MessageEvent) {
  if (event.source !== window || event.data?.source !== "codearena-extension" || event.data?.type !== "extension_status") return;
  if (probeTimer) window.clearTimeout(probeTimer);
  extensionPending.value = Number(event.data.pending_count || 0);
  extensionState.value = event.data.logged_in ? "ready" : "login";
}

function probeExtension() {
  extensionState.value = "unknown";
  window.postMessage({ source: "codearena", type: "extension_probe" }, window.location.origin);
  probeTimer = window.setTimeout(() => {
    if (extensionState.value === "unknown") extensionState.value = "missing";
  }, 900);
}

onMounted(() => { window.addEventListener("message", onExtensionMessage); probeExtension(); });
onUnmounted(() => { window.removeEventListener("message", onExtensionMessage); if (probeTimer) window.clearTimeout(probeTimer); });

const goals = [
  ["algorithm", "算法与刷题", "从题目、错误和复习建立基础"],
  ["backend", "后端与工程", "沉淀项目知识、代码与实践"],
  ["frontend", "前端与工程", "组织技术笔记与练习路径"],
  ["course", "课程与考研", "把课程材料变成可复习知识"],
  ["system_design", "系统设计", "从概念、案例到表达训练"],
] as const;

const starts = [
  ["path", "从推荐路径开始", "先得到一个轻量学习起点"],
  ["knowledge", "先导入知识", "把已有笔记沉淀为个人资料"],
  ["sync", "连接练习记录", "后续引导连接提交同步"],
  ["sample", "先看示例空间", "用一组示例理解学习节奏"],
] as const;

async function complete() {
  if (busy.value) return;
  error.value = "";
  busy.value = true;
  try {
    await api.post("/onboarding", {
      learning_goal: goal.value,
      daily_minutes: minutes.value,
      start_mode: startMode.value,
    });
    await router.replace(startMode.value === "sample" ? "/demo" : startMode.value === "knowledge" ? "/knowledge" : "/dashboard");
  } catch (err) {
    error.value = toUserMessage(err, "暂时保存不了，请稍后再试");
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="onboarding-page">
    <section class="onboarding-panel">
      <RouterLink class="brand" to="/">CodeArena</RouterLink>
      <p class="eyebrow">建立你的学习空间</p>
      <h1>先从适合你的节奏开始。</h1>
      <p class="intro">这几项只用来安排你的首页与学习提醒，之后随时可以调整。</p>

      <fieldset>
        <legend>你现在最想学习什么？</legend>
        <div class="choice-grid">
          <button v-for="item in goals" :key="item[0]" type="button" :class="{ selected: goal === item[0] }" @click="goal = item[0]">
            <strong>{{ item[1] }}</strong><span>{{ item[2] }}</span>
          </button>
        </div>
      </fieldset>
      <section v-if="startMode === 'sync'" class="extension-card">
        <div>
          <strong v-if="extensionState === 'ready'">扩展已连接</strong>
          <strong v-else-if="extensionState === 'login'">扩展已安装，正在同步登录</strong>
          <strong v-else-if="extensionState === 'missing'">还没有检测到扩展</strong>
          <strong v-else>正在检测扩展…</strong>
          <p v-if="extensionPending">有 {{ extensionPending }} 条提交等待补传。</p>
          <p v-else>扩展使用平台内置服务地址，不需要额外配置。</p>
        </div>
        <button type="button" class="btn-secondary" @click="probeExtension">重新检测</button>
      </section>

      <fieldset>
        <legend>每天可留出多久？</legend>
        <div class="time-options">
          <button v-for="item in [15, 30, 60]" :key="item" type="button" :class="{ selected: minutes === item }" @click="minutes = item">{{ item }} 分钟</button>
        </div>
      </fieldset>

      <fieldset>
        <legend>从哪里开始？</legend>
        <div class="choice-grid">
          <button v-for="item in starts" :key="item[0]" type="button" :class="{ selected: startMode === item[0] }" @click="startMode = item[0]">
            <strong>{{ item[1] }}</strong><span>{{ item[2] }}</span>
          </button>
        </div>
      </fieldset>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="button" class="btn-primary complete" :disabled="busy" @click="complete">{{ busy ? "正在建立…" : "进入我的学习空间" }}</button>
      <p class="note">AI 陪练可在需要时配置你自己的模型 Key；不影响基础学习与复习。</p>
    </section>
  </main>
</template>

<style scoped>
.onboarding-page { min-height: 100vh; padding: 48px 24px; background: var(--bg); }
.onboarding-panel { width: min(760px, 100%); margin: 0 auto; padding: 36px; border: 1px solid var(--line); border-radius: 16px; background: var(--card); box-shadow: var(--shadow); }
.brand { color: var(--ink); text-decoration: none; font-weight: 700; letter-spacing: -.04em; }.eyebrow { margin: 32px 0 4px; color: var(--accent); font-size: 13px; font-weight: 650; }.onboarding-panel h1 { margin: 0; font-size: clamp(30px, 4vw, 42px); letter-spacing: -.05em; }.intro, .note { color: var(--muted); }.intro { margin: 10px 0 32px; }.note { margin: 12px 0 0; font-size: 13px; }
fieldset { margin: 0 0 28px; padding: 0; border: 0; } legend { margin-bottom: 12px; font-size: 16px; font-weight: 650; }.choice-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }.choice-grid button, .time-options button { min-height: 72px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: #fff; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }.choice-grid button { padding: 14px; }.choice-grid strong, .choice-grid span { display: block; }.choice-grid span { margin-top: 4px; color: var(--muted); font-size: 13px; line-height: 1.4; }.choice-grid button.selected, .time-options button.selected { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }.time-options { display: flex; gap: 10px; }.time-options button { min-height: 44px; padding: 0 16px; text-align: center; }.extension-card { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin: -10px 0 28px; padding: 14px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--soft); }.extension-card p { margin: 3px 0 0; color: var(--muted); font-size: 13px; }.complete { width: 100%; }.error { color: var(--danger); font-size: 14px; } @media (max-width: 600px) { .onboarding-page { padding: 20px 16px; }.onboarding-panel { padding: 24px 18px; }.choice-grid { grid-template-columns: 1fr; }.time-options button { flex: 1; padding: 0 6px; }.extension-card { align-items: flex-start; flex-direction: column; } }
</style>
