<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { storeToRefs } from "pinia";
import AppHeader from "@/components/AppHeader.vue";
import { useKnowledgeStore } from "@/stores/knowledge";

const store = useKnowledgeStore();
const {
  documents,
  filteredKps,
  kpCountByDoc,
  selectedKp,
  selectedDocId,
  kpQuery,
  flashcards,
  flashDueTotal,
  flashNewCount,
  flashReviewCount,
  msg,
  msgKind,
  busy,
} = storeToRefs(store);

const title = ref("");
const content = ref("");
const sourceType = ref<"text" | "markdown">("text");
const pdfTitle = ref("");
const ingestOpen = ref(false);
const ingestTab = ref<"text" | "pdf">("text");
const mainTab = ref<"library" | "review">("library");
const cardRevealed = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
let pollTimer: number | undefined;

const pendingCount = computed(
  () =>
    documents.value.filter((d) =>
      ["uploaded", "parsing", "cleaning", "extracting", "embedding"].includes(d.status),
    ).length,
);

const selectedDocTitle = computed(() => {
  if (selectedDocId.value == null) return "全部文档";
  return documents.value.find((d) => d.id === selectedDocId.value)?.title || "文档";
});

const currentCard = computed(() => flashcards.value[0] || null);

onMounted(async () => {
  await store.refresh();
  await store.loadFlashcards();
  pollTimer = window.setInterval(() => {
    if (pendingCount.value > 0) void store.refresh();
  }, 2500);
});

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer);
});

async function switchMain(tab: "library" | "review") {
  mainTab.value = tab;
  cardRevealed.value = false;
  if (tab === "review") await store.loadFlashcards();
}

async function grade(g: string) {
  if (!currentCard.value) return;
  await store.reviewFlashcard(currentCard.value.knowledge_point_id, g);
  cardRevealed.value = false;
}
async function submitText() {
  if (!content.value.trim()) return;
  await store.createText({
    title: title.value.trim() || undefined,
    source_type: sourceType.value,
    content: content.value,
  });
  content.value = "";
  ingestOpen.value = false;
}

async function onPickPdf(ev: Event) {
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  await store.uploadPdf(file, pdfTitle.value.trim() || undefined);
  input.value = "";
  ingestOpen.value = false;
}

function statusLabel(s: string) {
  const map: Record<string, string> = {
    uploaded: "已上传",
    parsing: "解析中",
    cleaning: "清洗中",
    extracting: "抽取中",
    embedding: "向量化",
    ready: "就绪",
    failed: "失败",
  };
  return map[s] || s;
}

function statusClass(s: string) {
  if (s === "ready") return "chip-ok";
  if (s === "failed") return "chip-err";
  return "chip-busy";
}

function onDetailBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) store.clearSelected();
}
</script>

<template>
  <div class="page-shell">
    <AppHeader title="知识库" subtitle="投递笔记与八股，供 Nex 检索引用" />
    <main class="page-main knowledge-page">
      <p v-if="msg" class="banner" :class="msgKind === 'err' ? 'banner-err' : 'banner-ok'">
        {{ msg }}
      </p>

      <section class="section-card kb-toolbar">
        <div class="toolbar-left">
          <div class="seg main-seg" role="tablist" aria-label="知识库视图">
            <label :class="{ active: mainTab === 'library' }">
              <input v-model="mainTab" type="radio" value="library" @change="switchMain('library')" />
              资料库
            </label>
            <label :class="{ active: mainTab === 'review' }">
              <input v-model="mainTab" type="radio" value="review" @change="switchMain('review')" />
              闪卡学习
              <template v-if="flashDueTotal">（{{ flashDueTotal }}）</template>
            </label>
          </div>
          <button
            v-if="mainTab === 'library'"
            class="btn-primary"
            type="button"
            @click="ingestOpen = !ingestOpen"
          >
            {{ ingestOpen ? "收起入库" : "入库材料" }}
          </button>
          <button
            class="btn-secondary"
            type="button"
            :disabled="busy"
            @click="mainTab === 'review' ? store.loadFlashcards() : store.refresh()"
          >
            刷新
          </button>
          <span v-if="pendingCount" class="hint">{{ pendingCount }} 个文档处理中…</span>
        </div>
        <div class="toolbar-stats hint">
          <template v-if="mainTab === 'library'">
            文档 {{ documents.length }} · 知识点 {{ filteredKps.length
            }}<template v-if="selectedDocId != null">（已筛选）</template>
          </template>
          <template v-else>
            新学 {{ flashNewCount }} · 复习 {{ flashReviewCount }} · 队列 {{ flashcards.length }}
          </template>
        </div>
      </section>

      <section v-if="mainTab === 'review'" class="section-card kb-review">
        <p class="hint review-hint">
          入库后的知识点会进入本队列：先学新卡，评级后进入间隔复习。无需另建学习计划。
        </p>
        <p v-if="!currentCard" class="empty">
          队列为空。先在「资料库」入库或重跑文档；处理完成后回到这里即可开练。
        </p>
        <div v-else class="flashcard">
          <p class="meta">
            <span class="chip" :class="currentCard.kind === 'new' ? 'chip-busy' : 'chip-ok'">
              {{ currentCard.kind === 'new' ? '新学' : '复习' }}
            </span>
            <template v-if="currentCard.topic"> · {{ currentCard.topic }}</template>
            <template v-if="currentCard.source_title"> · {{ currentCard.source_title }}</template>
          </p>
          <h2 class="card-q">{{ currentCard.question || currentCard.title }}</h2>
          <button
            v-if="!cardRevealed"
            class="btn-primary"
            type="button"
            @click="cardRevealed = true"
          >
            显示答案
          </button>
          <div v-else class="card-a">
            <pre>{{ currentCard.answer }}</pre>
            <div class="grade-row">
              <button type="button" class="grade again" :disabled="busy" @click="grade('again')">
                Again
              </button>
              <button type="button" class="grade hard" :disabled="busy" @click="grade('hard')">
                Hard
              </button>
              <button type="button" class="grade good" :disabled="busy" @click="grade('good')">
                Good
              </button>
              <button type="button" class="grade easy" :disabled="busy" @click="grade('easy')">
                Easy
              </button>
            </div>
          </div>
        </div>
      </section>

      <template v-if="mainTab === 'library'">
      <section v-if="ingestOpen" class="section-card kb-ingest">
        <div class="seg" role="tablist" aria-label="入库方式">
          <label :class="{ active: ingestTab === 'text' }">
            <input v-model="ingestTab" type="radio" value="text" />
            粘贴文本
          </label>
          <label :class="{ active: ingestTab === 'pdf' }">
            <input v-model="ingestTab" type="radio" value="pdf" />
            上传 PDF
          </label>
        </div>

        <div v-if="ingestTab === 'text'" class="ingest-pane">
          <p class="hint">支持纯文本或 Markdown；题干行（以？结尾）会拆成独立知识点。</p>
          <div class="field-row">
            <label class="field">
              <span>标题（可选）</span>
              <input v-model="title" type="text" placeholder="例如：Java 并发笔记" />
            </label>
            <label class="field field-narrow">
              <span>类型</span>
              <select v-model="sourceType">
                <option value="text">text</option>
                <option value="markdown">markdown</option>
              </select>
            </label>
          </div>
          <label class="field">
            <span>正文</span>
            <textarea v-model="content" rows="6" placeholder="粘贴学习材料…" />
          </label>
          <button
            class="btn-primary"
            type="button"
            :disabled="busy || !content.trim()"
            @click="submitText"
          >
            提交入库
          </button>
        </div>

        <div v-else class="ingest-pane">
          <p class="hint">仅支持可复制文本层的 PDF；扫描件会失败。会自动去掉「扫码关注」等引流行。</p>
          <label class="field">
            <span>标题（可选）</span>
            <input v-model="pdfTitle" type="text" placeholder="默认用文件名" />
          </label>
          <input
            ref="fileInput"
            type="file"
            accept="application/pdf,.pdf"
            :disabled="busy"
            @change="onPickPdf"
          />
        </div>
      </section>

      <section class="kb-workspace">
        <aside class="section-card kb-docs">
          <div class="panel-head">
            <h2>文档</h2>
            <button
              class="btn-text"
              type="button"
              :class="{ active: selectedDocId == null }"
              @click="store.selectDoc(null)"
            >
              全部
            </button>
          </div>
          <p v-if="!documents.length" class="empty">还没有文档。</p>
          <ul v-else class="doc-list">
            <li
              v-for="d in documents"
              :key="d.id"
              class="doc-row"
              :class="{ active: selectedDocId === d.id }"
            >
              <button class="doc-main" type="button" @click="store.selectDoc(d.id)">
                <strong class="doc-title">{{ d.title }}</strong>
                <span class="meta">
                  <span class="chip" :class="statusClass(d.status)">{{ statusLabel(d.status) }}</span>
                  · {{ d.source_type }} · {{ kpCountByDoc.get(d.id) || 0 }} 点
                </span>
                <span v-if="d.failure_reason" class="fail">{{ d.failure_reason }}</span>
              </button>
              <div class="actions">
                <button
                  v-if="d.status === 'failed' || d.status === 'ready'"
                  class="btn-text"
                  type="button"
                  :disabled="busy"
                  title="用最新抽取规则重跑"
                  @click="store.reprocess(d.id)"
                >
                  重跑
                </button>
                <button
                  class="btn-text danger"
                  type="button"
                  :disabled="busy"
                  @click="store.removeDocument(d.id)"
                >
                  删
                </button>
              </div>
            </li>
          </ul>
        </aside>

        <div class="section-card kb-kps">
          <div class="panel-head">
            <h2>知识点 · {{ selectedDocTitle }}</h2>
            <input
              v-model="kpQuery"
              class="search"
              type="search"
              placeholder="筛选标题 / 主题…"
            />
          </div>
          <p v-if="!filteredKps.length" class="empty">
            {{ documents.length ? "暂无匹配的知识点。就绪后或点左侧文档筛选。" : "先入库一段文字或 PDF。" }}
          </p>
          <ul v-else class="kp-list">
            <li v-for="k in filteredKps" :key="k.id" class="kp-row">
              <button class="kp-main" type="button" @click="store.openKp(k.id)">
                <strong>{{ k.title }}</strong>
                <span class="meta">
                  #{{ k.id }}
                  <template v-if="k.topic"> · {{ k.topic }}</template>
                  <template v-if="k.source_title && selectedDocId == null">
                    · {{ k.source_title }}
                  </template>
                </span>
                <span v-if="k.body_preview" class="preview">{{ k.body_preview }}</span>
              </button>
              <button
                class="btn-text danger"
                type="button"
                :disabled="busy"
                @click="store.removeKp(k.id)"
              >
                删
              </button>
            </li>
          </ul>
        </div>
      </section>

      <div
        class="modal-backdrop"
        :class="{ open: !!selectedKp }"
        @click="onDetailBackdrop"
      >
        <div v-if="selectedKp" class="modal" role="dialog" aria-modal="true">
          <div class="modal-head">
            <h3>{{ selectedKp.title }}</h3>
            <button class="btn-text" type="button" @click="store.clearSelected()">关闭</button>
          </div>
          <p class="meta">
            #{{ selectedKp.id }}
            <template v-if="selectedKp.topic"> · {{ selectedKp.topic }}</template>
            <template v-if="selectedKp.source_title"> · 来源 {{ selectedKp.source_title }}</template>
          </p>
          <pre class="kp-body">{{ selectedKp.answer || selectedKp.body }}</pre>
        </div>
      </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.knowledge-page {
  display: grid;
  gap: var(--space-3);
  max-width: 1100px;
  margin: 0 auto;
  padding: var(--space-3);
}
.kb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  flex-wrap: wrap;
  padding: var(--space-2) var(--space-3);
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.kb-ingest {
  padding: var(--space-3);
}
.seg {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  background: var(--soft);
  border-radius: 999px;
  margin-bottom: var(--space-2);
}
.seg label {
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 0.9rem;
  cursor: pointer;
  color: var(--muted);
}
.seg label.active {
  background: #fff;
  color: var(--accent);
  box-shadow: var(--shadow);
}
.seg input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.ingest-pane .hint {
  margin-top: 0;
}
.field-row {
  display: grid;
  grid-template-columns: 1fr 140px;
  gap: var(--space-2);
}
.field {
  display: grid;
  gap: 6px;
  margin: var(--space-2) 0;
}
.field input,
.field select,
.field textarea,
.search {
  width: 100%;
  font: inherit;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: #fff;
}
.search {
  max-width: 220px;
  padding: 8px 10px;
}
.kb-workspace {
  display: grid;
  grid-template-columns: minmax(240px, 32%) 1fr;
  gap: var(--space-3);
  align-items: start;
  min-height: 420px;
}
.kb-docs,
.kb-kps {
  padding: var(--space-2) var(--space-3) var(--space-3);
  min-height: 0;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}
.panel-head h2 {
  margin: 0;
  font-size: 1.05rem;
}
.panel-head .btn-text.active {
  color: var(--accent);
  font-weight: 600;
}
.doc-list,
.kp-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 2px;
  max-height: min(62vh, 640px);
  overflow: auto;
}
.doc-row,
.kp-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 10px 8px;
  border-radius: var(--radius-sm);
}
.doc-row:hover,
.kp-row:hover {
  background: var(--soft);
}
.doc-row.active {
  background: var(--accent-soft);
}
.doc-main,
.kp-main {
  all: unset;
  cursor: pointer;
  display: grid;
  gap: 4px;
  flex: 1;
  min-width: 0;
}
.doc-title {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.kp-main:hover strong {
  color: var(--accent);
}
.hint,
.empty,
.meta,
.preview {
  color: var(--muted);
  font-size: 0.9rem;
}
.empty {
  margin: var(--space-2) 0;
}
.preview {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.chip {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 0.78rem;
}
.chip-ok {
  background: var(--ok-soft);
  color: var(--ok);
}
.chip-err {
  background: var(--danger-soft);
  color: var(--danger);
}
.chip-busy {
  background: var(--warn-soft);
  color: var(--warn);
}
.fail {
  color: var(--danger);
  font-size: 0.85rem;
}
.banner {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
}
.banner-ok {
  background: var(--ok-soft);
  color: var(--ok);
}
.banner-err {
  background: var(--danger-soft);
  color: var(--danger);
}
.modal-backdrop {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(28, 36, 48, 0.35);
  z-index: 50;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.modal-backdrop.open {
  display: flex;
}
.modal {
  width: min(640px, 100%);
  max-height: min(80vh, 720px);
  overflow: auto;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: var(--shadow);
  padding: 16px 18px;
}
.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.modal-head h3 {
  margin: 0;
  font-size: 1.05rem;
  line-height: 1.4;
}
.kp-body {
  white-space: pre-wrap;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: var(--space-2);
  font: inherit;
  line-height: 1.55;
  margin: var(--space-2) 0 0;
}
.danger {
  color: var(--danger);
}
.kb-review {
  padding: var(--space-3);
  min-height: 320px;
}
.review-hint {
  margin: 0 0 var(--space-2);
}
.flashcard {
  display: grid;
  gap: var(--space-2);
  max-width: 720px;
  margin: 0 auto;
}
.card-q {
  margin: 0;
  font-size: 1.25rem;
  line-height: 1.45;
}
.card-a pre {
  white-space: pre-wrap;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: var(--space-2);
  font: inherit;
  line-height: 1.55;
  margin: 0 0 var(--space-2);
  max-height: 40vh;
  overflow: auto;
}
.grade-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.grade {
  flex: 1;
  min-width: 72px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  font: inherit;
  cursor: pointer;
  background: #fff;
}
.grade.again {
  color: var(--danger);
}
.grade.hard {
  color: var(--warn);
}
.grade.good {
  color: var(--ok);
}
.grade.easy {
  color: var(--accent);
}
.btn-text {
  background: none;
  border: 0;
  font: inherit;
  color: var(--muted);
  cursor: pointer;
  padding: 4px 6px;
}
.btn-text:hover {
  color: var(--accent);
}
@media (max-width: 800px) {
  .kb-workspace {
    grid-template-columns: 1fr;
  }
  .field-row {
    grid-template-columns: 1fr;
  }
  .doc-list,
  .kp-list {
    max-height: 280px;
  }
}
</style>
