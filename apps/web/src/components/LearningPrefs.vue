<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { storeToRefs } from "pinia";
import { useLearningStore } from "@/stores/learning";

const learning = useLearningStore();
const {
  listMode,
  kgMode,
  activeListId,
  lists,
  progressText,
  mastered,
  message,
  messageKind,
  modesOn,
} = storeToRefs(learning);

const open = ref(false);
const rootEl = ref<HTMLElement | null>(null);

function toggle() {
  open.value = !open.value;
}

function onDocClick(e: MouseEvent) {
  if (rootEl.value && !rootEl.value.contains(e.target as Node)) {
    open.value = false;
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === "Escape") open.value = false;
}

onMounted(() => {
  document.addEventListener("click", onDocClick);
  document.addEventListener("keydown", onKey);
});
onUnmounted(() => {
  document.removeEventListener("click", onDocClick);
  document.removeEventListener("keydown", onKey);
});
</script>

<template>
  <div ref="rootEl" class="prefs-root">
    <button
      type="button"
      class="prefs-trigger"
      :aria-expanded="open"
      aria-controls="prefs-panel"
      @click.stop="toggle"
    >
      <span class="prefs-dot" :class="{ on: modesOn }" aria-hidden="true" />
      <span>学习偏好</span>
      <span class="prefs-chevron" aria-hidden="true">▾</span>
    </button>
    <div
      id="prefs-panel"
      class="prefs-panel"
      :class="{ open }"
      role="dialog"
      aria-label="学习偏好"
    >
      <h3>学习偏好</h3>
      <p class="prefs-hint">
        题单负责主路径推荐与复习；知识图谱补充相近知识点。导入自定义题单请到维护台。
      </p>
      <div class="prefs-row">
        <label class="prefs-toggle">
          <span>题单模式</span>
          <input v-model="listMode" type="checkbox" />
        </label>
        <label class="prefs-toggle">
          <span>知识图谱模式</span>
          <input v-model="kgMode" type="checkbox" />
        </label>
      </div>
      <div class="prefs-field">
        <label for="learn-active-list">活跃题单</label>
        <select id="learn-active-list" v-model="activeListId">
          <option v-for="item in lists" :key="item.id" :value="item.id">
            {{ item.name }} · {{ item.total }} 题
          </option>
        </select>
      </div>
      <p class="prefs-progress">{{ progressText }}</p>
      <div class="prefs-footer">
        <button type="button" class="btn-primary" @click="learning.save()">
          保存
        </button>
      </div>
      <div class="prefs-msg" :class="messageKind">{{ message }}</div>
      <div class="prefs-divider" />
      <details class="prefs-mastered">
        <summary>
          <span>已掌握</span>
          <span class="hint">{{ mastered.length ? mastered.length + " 题" : "0" }}</span>
        </summary>
        <div v-if="mastered.length" class="prefs-mastered-list">
          <div
            v-for="c in mastered"
            :key="c.problem_id"
            class="prefs-mastered-item"
          >
            <RouterLink
              class="title"
              :to="`/problems/${c.problem_id}`"
            >
              {{ c.problem_id }}. {{ c.title || "#" + c.problem_id }}
            </RouterLink>
            <button
              type="button"
              class="action-btn"
              @click.stop="learning.unmaster(c.problem_id)"
            >
              取消掌握
            </button>
          </div>
        </div>
        <p v-else class="prefs-mastered-empty">
          暂无 · 可在题目详情页标记
        </p>
      </details>
    </div>
  </div>
</template>

<style scoped>
.prefs-root {
  position: relative;
}
.prefs-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--ink);
  border-radius: 999px;
  padding: 7px 12px 7px 10px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 1px 0 rgba(28, 25, 23, 0.04);
}
.prefs-trigger:hover {
  border-color: #e2b6be;
  color: var(--accent);
}
.prefs-trigger[aria-expanded="true"] {
  border-color: var(--accent);
  color: var(--accent);
}
.prefs-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
  flex-shrink: 0;
}
.prefs-dot.on {
  background: var(--accent);
}
.prefs-chevron {
  color: var(--muted);
  font-size: 10px;
  line-height: 1;
}
.prefs-panel {
  display: none;
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: min(340px, calc(100vw - 32px));
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: 0 18px 40px rgba(28, 25, 23, 0.12);
  padding: 14px;
  z-index: 40;
}
.prefs-panel.open {
  display: block;
}
.prefs-panel h3 {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 650;
}
.prefs-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.45;
}
.prefs-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.prefs-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}
.prefs-toggle:hover {
  border-color: #e2b6be;
}
.prefs-toggle input {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
  margin: 0;
}
.prefs-toggle span:first-child {
  font-weight: 500;
}
.prefs-field label {
  display: block;
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 6px;
}
.prefs-field select {
  width: 100%;
  border: 1px solid var(--line);
  background: #fff;
  border-radius: 10px;
  padding: 8px 10px;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
}
.prefs-progress {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--accent-soft);
  color: #9a5563;
  font-size: 12px;
  line-height: 1.45;
}
.prefs-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 14px;
}
.prefs-msg {
  min-height: 16px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--muted);
  text-align: center;
}
.prefs-msg.ok {
  color: var(--ok);
}
.prefs-msg.err {
  color: var(--warn);
}
.prefs-divider {
  height: 1px;
  background: var(--line);
  margin: 14px 0 12px;
}
.prefs-mastered > summary {
  cursor: pointer;
  list-style: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.prefs-mastered > summary::-webkit-details-marker {
  display: none;
}
.prefs-mastered[open] > summary {
  margin-bottom: 8px;
}
.prefs-mastered > summary .hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--muted);
}
.prefs-mastered-list {
  max-height: 180px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.prefs-mastered-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  font-size: 12px;
}
.prefs-mastered-item .title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.prefs-mastered-empty {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
</style>
