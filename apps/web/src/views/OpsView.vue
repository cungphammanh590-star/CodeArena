<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import AppHeader from "@/components/AppHeader.vue";
import { useOpsStore } from "@/stores/ops";
import api from "@/api/client";
import { formatDisplayTime } from "@/utils/format";
import { toUserMessage } from "@/utils/userMessage";

const ops = useOpsStore();
const {
  config,
  configMsg,
  configMsgKind,
  listMode,
  kgMode,
  activeListId,
  lists,
  learnMsg,
  learnMsgKind,
  llmProvider,
  llmModel,
  llmApiKey,
  llmBaseUrl,
  llmHasKey,
  llmKeyMask,
  llmMsg,
  llmMsgKind,
  logsMsg,
  logsMsgKind,
  rebuildMsg,
  rebuildMsgKind,
  kgMsg,
  kgMsgKind,
  kgOut,
  kgOutVisible,
  importTarget,
  importMode,
  importSetActive,
  importExistingList,
  importNewName,
  importJson,
  existingItems,
  existingEmptyText,
  sampleOpen,
  sampleKind,
  sampleText,
  sampleMsg,
  sampleMsgKind,
  writableLists,
} = storeToRefs(ops);

const busy = ref<Record<string, boolean>>({});

const usageSummary = ref<{
  last_24h?: { total_tokens?: number; calls?: number };
  last_7d?: { total_tokens?: number; calls?: number };
  recent?: Array<Record<string, unknown>>;
} | null>(null);
const usageMsg = ref("");

async function loadUsage() {
  usageMsg.value = "";
  try {
    const { data } = await api.get("/users/me/llm/usage", { params: { limit: 20 } });
    usageSummary.value = data;
  } catch (e) {
    usageMsg.value = toUserMessage(e, "用量暂时加载不了");
  }
}

const configRows = computed(() => {
  const cfg = config.value;
  const llm = cfg.llm || {};
  return [
    ["host", cfg.host],
    ["port", cfg.port],
    ["autostart", String(cfg.autostart)],
    ["llm.provider", llm.provider],
    ["llm.api_provider", llm.api_provider || "—"],
    ["llm.coach_model", llm.coach_model],
    ["llm.has_api_key", String(!!llm.has_api_key)],
    ["db_path", cfg.db_path_readonly],
  ] as [string, unknown][];
});

const testDisabled = computed(() => {
  const api = llmProvider.value === "api";
  const typed = llmApiKey.value.trim().length > 0;
  return api && !llmHasKey.value && !typed;
});

async function withBusy(key: string, fn: () => Promise<void>) {
  busy.value[key] = true;
  try {
    await fn();
  } finally {
    busy.value[key] = false;
  }
}

watch(llmProvider, () => ops.syncProviderUi());
watch(importTarget, (v) => {
  if (v === "existing") ops.loadExistingItems();
});
watch(importExistingList, () => {
  if (importTarget.value === "existing") ops.loadExistingItems();
});

function openSample() {
  sampleOpen.value = true;
  ops.loadSample(sampleKind.value);
}

function closeSample() {
  sampleOpen.value = false;
}

function onSampleBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) closeSample();
}

onMounted(() => {
  ops.loadConfig();
  ops.loadLearning();
  loadUsage();
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && sampleOpen.value) closeSample();
  });
});
</script>

<template>
  <AppHeader title="维护台" ops>
    <template #subtitle>配置、图谱与 Nex 模型 · 危险操作需确认</template>
  </AppHeader>

  <main class="page-main ops">
    <!-- 学习偏好与题单 -->
    <section class="section-card ops-section">
      <h2>学习偏好与题单</h2>
      <p class="hint">首页也能改偏好；自定义题单在这里导入与管理。</p>

      <div class="learn-grid">
        <label class="toggle-card">
          <span>
            <strong>题单模式</strong>
            <small>按活跃题单推荐与复习</small>
          </span>
          <input v-model="listMode" type="checkbox" />
        </label>
        <label class="toggle-card">
          <span>
            <strong>知识图谱模式</strong>
            <small>按相近知识点补充推荐</small>
          </span>
          <input v-model="kgMode" type="checkbox" />
        </label>
      </div>

      <div class="field-block">
        <label for="learn-active-list">活跃题单</label>
        <select id="learn-active-list" v-model="activeListId">
          <option v-for="item in lists" :key="item.id" :value="item.id">
            {{ item.name }} · {{ item.total }} 题
          </option>
        </select>
        <div class="inline-actions">
          <button
            type="button"
            class="btn-primary"
            @click="ops.saveLearning()"
          >
            保存偏好
          </button>
          <button type="button" class="btn" @click="ops.restoreHot100()">
            恢复默认 Hot100
          </button>
        </div>
      </div>

      <div class="list-panel">
        <div class="list-panel-head">
          <h3>题单管理</h3>
          <div class="seg" role="radiogroup" aria-label="题单目标">
            <label :class="{ active: importTarget === 'existing' }">
              <input v-model="importTarget" type="radio" value="existing" />
              已有题单
            </label>
            <label :class="{ active: importTarget === 'new' }">
              <input v-model="importTarget" type="radio" value="new" />
              新建题单
            </label>
          </div>
        </div>

        <div v-if="importTarget === 'existing'">
          <div v-if="writableLists.length" class="field-block">
            <label for="import-existing-list">选择题单</label>
            <select id="import-existing-list" v-model="importExistingList">
              <option
                v-for="item in writableLists"
                :key="item.id"
                :value="item.id"
              >
                {{ item.name }} · {{ item.total }} 题
              </option>
            </select>
          </div>
          <div v-if="existingItems.length" class="items-box">
            <div
              v-for="c in existingItems"
              :key="c.id"
              class="item"
            >
              <span class="title">{{ c.id }}. {{ c.title || "" }}</span>
              <button
                type="button"
                class="ghost-link"
                @click="ops.removeListItem(c.id)"
              >
                移除
              </button>
            </div>
          </div>
          <p v-else class="empty-card">{{ existingEmptyText }}</p>
        </div>

        <div v-else class="field-block" style="margin-bottom: 0">
          <label for="import-new-name">题单名称</label>
          <input
            id="import-new-name"
            v-model="importNewName"
            type="text"
            placeholder="如 Blind 75"
          />
        </div>

        <div class="import-options">
          <div class="opt-group">
            <span>写入</span>
            <div class="seg" role="radiogroup" aria-label="写入方式">
              <label :class="{ active: importMode === 'append' }">
                <input v-model="importMode" type="radio" value="append" />
                追加
              </label>
              <label :class="{ active: importMode === 'overwrite' }">
                <input v-model="importMode" type="radio" value="overwrite" />
                覆盖
              </label>
            </div>
          </div>
          <label class="active-switch" for="import-set-active">
            <span>设为活跃题单</span>
            <input
              id="import-set-active"
              v-model="importSetActive"
              type="checkbox"
            />
            <span class="track" aria-hidden="true" />
          </label>
        </div>

        <div class="inline-actions" style="margin: 0 0 8px">
          <button type="button" class="ghost-link" @click="openSample">
            查看样例 JSON
          </button>
        </div>
        <textarea
          v-model="importJson"
          placeholder='{"problems":[{ "id":1, "slug":"two-sum", ... }]}'
        />
        <div class="action-bar">
          <button
            v-if="importTarget === 'existing'"
            type="button"
            class="btn"
            @click="ops.loadExistingItems()"
          >
            刷新题目
          </button>
          <button
            v-if="importTarget === 'existing'"
            type="button"
            class="btn btn-danger"
            @click="ops.deleteList()"
          >
            删除此题单
          </button>
          <button
            type="button"
            class="btn-primary"
            :disabled="busy.import"
            @click="withBusy('import', () => ops.importList())"
          >
            导入题目
          </button>
        </div>
      </div>
      <div class="msg" :class="learnMsgKind">{{ learnMsg }}</div>
    </section>

    <!-- 当前配置 -->
    <section class="section-card ops-section">
      <h2>当前配置（只读）</h2>
      <p class="hint">配置按当前用户隔离（请求头 X-User-Public-Id）；未带头时用种子用户 default。</p>
      <div class="row">
        <button type="button" class="btn" @click="ops.loadConfig()">
          刷新配置
        </button>
      </div>
      <dl class="config-dl">
        <template v-for="[k, v] in configRows" :key="k">
          <dt>{{ k }}</dt>
          <dd>{{ v == null ? "—" : v }}</dd>
        </template>
      </dl>
      <div class="msg" :class="configMsgKind">{{ configMsg }}</div>
    </section>

    <!-- Nex 模型 -->
    <section class="section-card ops-section">
      <h2>Nex 模型</h2>
      <p class="hint">
        可选 Ollama 或 DeepSeek。API Key <strong>按用户</strong>保存在 business-service；对话时 llm-service 只用当前用户的 Key。
      </p>
      <div class="radio-row">
        <label>
          <input v-model="llmProvider" type="radio" value="ollama" />
          Ollama
        </label>
        <label>
          <input v-model="llmProvider" type="radio" value="api" />
          DeepSeek API
        </label>
      </div>
      <div class="row" style="margin-bottom: 10px">
        <label class="field">
          <span>模型名</span>
          <input
            v-model="llmModel"
            type="text"
            placeholder="qwen2.5:7b-instruct-q4_K_M / deepseek-chat"
            @input="ops.modelTouched = true"
          />
        </label>
      </div>
      <div v-if="llmProvider === 'api'">
        <div class="row" style="margin-bottom: 10px">
          <label class="field">
            <span>API Key</span>
            <input
              v-model="llmApiKey"
              type="password"
              placeholder="留空则保留已保存的 Key"
              autocomplete="off"
            />
          </label>
          <label class="field">
            <span>Base URL（可选）</span>
            <input
              v-model="llmBaseUrl"
              type="text"
              placeholder="默认 https://api.deepseek.com"
            />
          </label>
        </div>
        <p class="hint">{{ llmKeyMask }}</p>
      </div>
      <div class="row">
        <button
          type="button"
          class="btn-primary"
          :disabled="busy.llmSave"
          @click="withBusy('llmSave', () => ops.saveLlm())"
        >
          保存
        </button>
        <button
          type="button"
          class="btn"
          :disabled="testDisabled || busy.llmTest"
          :title="testDisabled ? '请先填写并保存 API Key' : '使用当前已保存配置测试'"
          @click="withBusy('llmTest', () => ops.testLlm())"
        >
          测试连接
        </button>
        <button
          type="button"
          class="btn btn-danger"
          :disabled="busy.llmClear"
          @click="withBusy('llmClear', () => ops.clearLlmKey())"
        >
          一键清除 Key
        </button>
      </div>
      <div class="msg" :class="llmMsgKind">{{ llmMsg }}</div>
    </section>

    <!-- 我的模型用量 -->
    <section class="section-card ops-section">
      <div class="section-head">
        <h2>我的模型用量</h2>
        <button type="button" class="ghost-link" @click="loadUsage">刷新</button>
      </div>
      <p class="hint">
        记录本账号调用云端/本地模型的次数与 token（不展示 Key）。和 Nex 对话一轮后会出现在这里。
      </p>
      <div v-if="usageSummary" class="usage-metrics">
        <div class="usage-card">
          <div class="label">近 24 小时</div>
          <div class="value">{{ usageSummary.last_24h?.total_tokens ?? 0 }} tokens</div>
          <div class="meta">{{ usageSummary.last_24h?.calls ?? 0 }} 次调用</div>
        </div>
        <div class="usage-card">
          <div class="label">近 7 天</div>
          <div class="value">{{ usageSummary.last_7d?.total_tokens ?? 0 }} tokens</div>
          <div class="meta">{{ usageSummary.last_7d?.calls ?? 0 }} 次调用</div>
        </div>
      </div>
      <table v-if="usageSummary?.recent?.length" class="data-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>模型</th>
            <th>Tokens</th>
            <th>结果</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in usageSummary.recent" :key="String(row.id)">
            <td class="time-cell">{{ formatDisplayTime(String(row.created_at || "")) }}</td>
            <td>{{ row.model || "—" }}</td>
            <td>
              {{ row.total_tokens || 0 }}
              <span class="muted">
                （{{ row.prompt_tokens || 0 }}+{{ row.completion_tokens || 0 }}）
              </span>
            </td>
            <td>
              <span :class="row.success ? 'ok' : 'bad'">
                {{ row.success ? "成功" : row.error_code || "失败" }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!usageMsg" class="empty">还没有用量记录。去和 Nex 对话一轮后再看。</p>
      <p v-if="usageMsg" class="msg err">{{ usageMsg }}</p>
    </section>

    <!-- 清理日志 -->
    <section class="section-card ops-section">
      <h2>清理日志</h2>
      <p class="hint">
        清空 LaunchAgent 服务日志，并默认清理 Nex 调试日志（log/coach）。
      </p>
      <div class="row">
        <button
          type="button"
          class="btn btn-warn"
          :disabled="busy.logs"
          @click="withBusy('logs', () => ops.cleanLogs())"
        >
          清理服务 + Nex 调试日志
        </button>
      </div>
      <div class="msg" :class="logsMsgKind">{{ logsMsg }}</div>
    </section>

    <!-- 重建题目汇总 -->
    <section class="section-card ops-section">
      <h2>重建题目汇总</h2>
      <p class="hint">从 submissions 全量重建 problem_stats（不删提交记录）。</p>
      <div class="row">
        <button
          type="button"
          class="btn btn-warn"
          :disabled="busy.rebuild"
          @click="withBusy('rebuild', () => ops.rebuildStats())"
        >
          全量重建 stats
        </button>
      </div>
      <div class="msg" :class="rebuildMsgKind">{{ rebuildMsg }}</div>
    </section>

    <!-- 学习路线图 -->
    <section class="section-card ops-section">
      <h2>学习路线图（内嵌）</h2>
      <p class="hint">
        随包装载，启动时自动就绪；重建会刷新 kg_* 表，用户提交不受影响。
      </p>
      <div class="row">
        <button type="button" class="btn" @click="ops.kgStatus()">
          查看状态
        </button>
        <button
          type="button"
          class="btn btn-warn"
          :disabled="busy.kg"
          @click="withBusy('kg', () => ops.kgImport())"
        >
          重建内嵌路线图
        </button>
      </div>
      <div class="msg" :class="kgMsgKind">{{ kgMsg }}</div>
      <pre v-if="kgOutVisible" class="out">{{ kgOut }}</pre>
    </section>
  </main>

  <!-- 样例 JSON 弹窗 -->
  <div
    class="modal-backdrop"
    :class="{ open: sampleOpen }"
    aria-hidden="true"
    @click="onSampleBackdrop"
  >
    <div class="modal" role="dialog" aria-labelledby="sample-title">
      <div class="modal-head">
        <h3 id="sample-title">题单 JSON 样例</h3>
        <button type="button" class="btn" @click="closeSample">关闭</button>
      </div>
      <div class="modal-tabs">
        <button
          type="button"
          class="btn"
          :class="{ active: sampleKind === 'list' }"
          @click="ops.loadSample('list')"
        >
          完整题单
        </button>
        <button
          type="button"
          class="btn"
          :class="{ active: sampleKind === 'single' }"
          @click="ops.loadSample('single')"
        >
          单题
        </button>
      </div>
      <pre>{{ sampleText }}</pre>
      <div class="modal-actions">
        <button type="button" class="btn-primary" @click="ops.copySample()">
          复制
        </button>
        <button type="button" class="btn" @click="ops.fillSample()">
          填入导入框
        </button>
      </div>
      <div class="msg" :class="sampleMsgKind">{{ sampleMsg }}</div>
    </div>
  </div>
</template>

<style scoped>
.nav {
  margin-top: 10px;
  font-size: 13px;
}
.nav a {
  margin-right: 14px;
}
.ops-section {
  padding: 16px 18px;
}
.row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--muted);
  min-width: 200px;
  flex: 1;
}
.field > span {
  font-weight: 500;
  color: var(--ink);
}
.field input,
.field-block select,
.field-block input[type="text"] {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 10px;
  font-size: 13px;
  background: #fff;
  color: var(--ink);
  width: 100%;
}
.radio-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  font-size: 13px;
}
.radio-row label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.config-dl {
  margin: 12px 0 0;
  display: grid;
  grid-template-columns: 140px 1fr;
  gap: 6px 12px;
  font-size: 13px;
}
.config-dl dt {
  color: var(--muted);
}
.config-dl dd {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.learn-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 16px;
}
@media (max-width: 640px) {
  .learn-grid {
    grid-template-columns: 1fr;
  }
}
.toggle-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
}
.toggle-card:hover {
  border-color: #e2b6be;
  box-shadow: 0 1px 0 rgba(198, 122, 136, 0.08);
}
.toggle-card strong {
  display: block;
  font-weight: 600;
  margin-bottom: 2px;
}
.toggle-card small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.35;
}
.toggle-card input {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
  margin: 0;
  flex-shrink: 0;
}
.field-block {
  margin-bottom: 14px;
}
.field-block > label {
  display: block;
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 6px;
}
.inline-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.list-panel {
  margin-top: 6px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.7) 0%, transparent 48%),
    var(--soft);
}
.list-panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.list-panel-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}
.seg {
  display: inline-flex;
  padding: 3px;
  border-radius: 10px;
  background: #efe6e1;
  gap: 2px;
}
.seg label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 84px;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--muted);
  cursor: pointer;
  user-select: none;
  position: relative;
}
.seg label.active {
  background: #fff;
  color: var(--ink);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(28, 25, 23, 0.08);
}
.seg input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.import-options {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 18px;
  margin: 14px 0 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}
.opt-group {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.opt-group > span {
  font-size: 12px;
  color: var(--muted);
  white-space: nowrap;
}
.opt-group .seg {
  background: #f3e9e6;
}
.opt-group .seg label {
  min-width: 64px;
  padding: 5px 10px;
}
.active-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink);
  cursor: pointer;
  user-select: none;
  position: relative;
}
.active-switch input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.active-switch .track {
  width: 36px;
  height: 20px;
  border-radius: 999px;
  background: #d6d3d1;
  position: relative;
  flex-shrink: 0;
  transition: background 0.15s ease;
}
.active-switch .track::after {
  content: "";
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(28, 25, 23, 0.18);
  transition: transform 0.15s ease;
}
.active-switch:has(input:checked) .track {
  background: var(--accent);
}
.active-switch:has(input:checked) .track::after {
  transform: translateX(16px);
}
.empty-card {
  margin: 0;
  padding: 14px 12px;
  border: 1px dashed #d6d3d1;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.55);
  color: var(--muted);
  font-size: 13px;
  text-align: center;
  line-height: 1.45;
}
.list-panel textarea {
  width: 100%;
  min-height: 132px;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.45;
  background: #fff;
  color: var(--ink);
  resize: vertical;
}
.action-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  align-items: center;
}
.items-box {
  margin-top: 4px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  max-height: 200px;
  overflow: auto;
}
.items-box .item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.items-box .item:last-child {
  border-bottom: none;
}
.items-box .item .title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.out {
  margin: 12px 0 0;
  padding: 12px;
  background: #f5f5f4;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.45;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.modal-backdrop {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(28, 25, 23, 0.35);
  z-index: 50;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.modal-backdrop.open {
  display: flex;
}
.modal {
  width: min(520px, 100%);
  max-height: min(80vh, 640px);
  overflow: auto;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: 0 20px 48px rgba(28, 25, 23, 0.18);
  padding: 16px 18px;
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.modal-head h3 {
  margin: 0;
  font-size: 15px;
}
.modal-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
}
.modal-tabs button {
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12px;
}
.modal-tabs button.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.modal pre {
  margin: 0 0 12px;
  padding: 12px;
  background: #f5f5f4;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.45;
  max-height: 360px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.modal-actions {
  display: flex;
  gap: 8px;
}
.usage-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 0 0 14px;
}
.usage-card {
  background: color-mix(in srgb, var(--card) 70%, #fff);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
}
.usage-card .label {
  font-size: 12px;
  color: var(--muted);
}
.usage-card .value {
  margin-top: 4px;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.02em;
}
.usage-card .meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--muted);
}
.muted {
  color: var(--muted);
  font-size: 12px;
}
</style>
