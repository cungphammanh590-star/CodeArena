<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import api from "@/api/client";
import AppHeader from "@/components/AppHeader.vue";
import MetricCard from "@/components/MetricCard.vue";
import {
  formatStatusCounts,
  formatDuration,
  leetcodeUrl,
  diffClass,
  statusChangeLabel,
  statusChangeClass,
} from "@/utils/format";

const route = useRoute();
const problemId = computed(() => Number(route.params.id));

const title = ref("题目详情");
const metaHtml = ref("加载中…");
const difficulty = ref("");
const leetUrl = ref<string | null>(null);
const statusText = ref("");
const tags = ref<string[]>([]);
const metrics = ref<{ label: string; value: string | number }[]>([]);
const breakdown = ref("—");
const timeline = ref<{ label: string; value: string }[]>([]);
const daily = ref<Record<string, unknown>[]>([]);
const submissions = ref<Record<string, unknown>[]>([]);
const mastered = ref(false);
const masterBusy = ref(false);
const masterRowVisible = ref(false);
const errorText = ref("");

let pollTimer: ReturnType<typeof setInterval> | null = null;

const masterBtnText = computed(() =>
  mastered.value ? "取消掌握" : "标记已掌握",
);
const masterHint = computed(() =>
  mastered.value
    ? "当前已屏蔽推荐与复习"
    : "标记后不再出现在推荐与复习",
);

function renderFromData(data: {
  problem: Record<string, unknown>;
  daily?: Record<string, unknown>[];
  submissions?: Record<string, unknown>[];
}) {
  const p = data.problem;
  const url = leetcodeUrl(p.title_slug as string);
  const diff = (p.difficulty as string) || "未知";
  title.value = `${p.problem_id}. ${p.title}`;
  document.title = `${p.problem_id}. ${p.title} · CodeArena`;
  if (!masterBusy.value) {
    mastered.value = !!p.mastered;
    masterRowVisible.value = true;
  }
  difficulty.value = diff;
  leetUrl.value = url;
  statusText.value = `已更新 ${new Date().toLocaleTimeString()}`;
  tags.value = (p.topic_tags as string[]) || [];

  metrics.value = [
    { label: "总尝试", value: p.total_attempts as number },
    { label: "AC 次数", value: p.accepted_count as number },
    {
      label: "通过率",
      value: `${Math.round(((p.acceptance_rate as number) || 0) * 1000) / 10}%`,
    },
    {
      label: "挣扎指数",
      value: `${Math.round(((p.struggle_score as number) || 0) * 100)}%`,
    },
    {
      label: "最近 AC 间隔",
      value:
        p.avg_attempts_to_ac != null
          ? `${p.avg_attempts_to_ac} 次`
          : "—",
    },
    {
      label: "首次 AC 耗时",
      value: formatDuration(p.solve_time_seconds as number | null),
    },
    { label: "最近状态", value: (p.last_status as string) || "—" },
    {
      label: "最近提交",
      value: (p.last_submitted_at as string) || "—",
    },
  ];

  breakdown.value = formatStatusCounts(
    p.status_breakdown as Record<string, number>,
    false,
  );
  timeline.value = [
    { label: "首次尝试", value: (p.first_attempt_at as string) || "—" },
    { label: "最近尝试", value: (p.last_attempt_at as string) || "—" },
    {
      label: "首次 AC",
      value: (p.first_accepted_at as string) || "尚未 AC",
    },
  ];
  daily.value = data.daily || [];
  submissions.value = data.submissions || [];
  errorText.value = "";
}

async function load() {
  const pid = problemId.value;
  if (!pid) {
    errorText.value = "无效的题号";
    return;
  }
  try {
    const { data } = await api.get(`/problems/${pid}/stats`);
    renderFromData(data);
  } catch (err) {
    errorText.value = `加载失败：${err}`;
  }
}

async function toggleMastered() {
  const pid = problemId.value;
  if (!pid || masterBusy.value) return;
  masterBusy.value = true;
  try {
    const next = !mastered.value;
    if (next) {
      const { data } = await api.post(`/problems/${pid}/mastered`, {});
      if (data.status === "error") throw new Error(data.message || "操作失败");
    } else {
      const { data } = await api.delete(`/problems/${pid}/mastered`);
      if (data.status === "error") throw new Error(data.message || "操作失败");
    }
    mastered.value = next;
  } catch (err) {
    statusText.value = String(err);
  } finally {
    masterBusy.value = false;
  }
}

watch(problemId, () => load());

onMounted(() => {
  load();
  pollTimer = setInterval(load, 5000);
});

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>

<template>
  <AppHeader :title="title">
    <template #before>
      <RouterLink class="back" to="/">← 返回仪表盘</RouterLink>
    </template>
    <template #subtitle>
      <template v-if="errorText">{{ errorText }}</template>
      <template v-else>
        <span :class="diffClass(difficulty)">{{ difficulty }}</span>
        <template v-if="leetUrl">
          ·
          <a :href="leetUrl" target="_blank" rel="noopener">
            在 leetcode.cn 打开
          </a>
        </template>
        ·
        <span>{{ statusText }}</span>
      </template>
    </template>
    <template #after-title>
      <div v-if="masterRowVisible" class="master-row">
        <button
          type="button"
          class="master-btn"
          :class="{ on: mastered }"
          :disabled="masterBusy"
          :title="
            mastered
              ? '取消后，本题可再次出现在推荐与复习里'
              : '标记后不再出现在推荐与复习里'
          "
          @click="toggleMastered"
        >
          {{ masterBtnText }}
        </button>
        <span class="master-hint">{{ masterHint }}</span>
      </div>
      <div class="tags">
        <span v-if="!tags.length" class="tag">无标签</span>
        <span v-for="t in tags" :key="t" class="tag">{{ t }}</span>
      </div>
    </template>
  </AppHeader>

  <main class="page-main">
    <div class="metrics-grid">
      <MetricCard
        v-for="m in metrics"
        :key="m.label"
        :label="m.label"
        :value="m.value"
      />
    </div>

    <section class="section-card">
      <h2>终身错误分布</h2>
      <div class="breakdown">{{ breakdown }}</div>
    </section>

    <section class="section-card">
      <h2>时间线</h2>
      <table class="data-table">
        <tbody>
          <tr v-for="row in timeline" :key="row.label">
            <th>{{ row.label }}</th>
            <td>{{ row.value }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="section-card">
      <h2>每日提交情况</h2>
      <table class="data-table">
        <thead>
          <tr>
            <th>日期</th>
            <th>尝试</th>
            <th>AC</th>
            <th>错题</th>
            <th>连续天数</th>
            <th>状态变化</th>
            <th>当日分布</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!daily.length">
            <td colspan="7" class="empty">暂无每日记录</td>
          </tr>
          <tr v-for="(d, idx) in daily" :key="idx">
            <td>
              {{ d.day
              }}{{
                [
                  d.is_new_today ? "新题" : "",
                  d.is_review_today ? "复习" : "",
                ]
                  .filter(Boolean)
                  .length
                  ? "（" +
                    [
                      d.is_new_today ? "新题" : "",
                      d.is_review_today ? "复习" : "",
                    ]
                      .filter(Boolean)
                      .join("，") +
                    "）"
                  : ""
              }}
            </td>
            <td>{{ d.attempts }}</td>
            <td class="ok">{{ d.accepted_today }}</td>
            <td>{{ d.wrong_today }}</td>
            <td>{{ d.consecutive_days }}</td>
            <td>
              <span :class="statusChangeClass(d.status_change as string)">
                {{ statusChangeLabel(d.status_change as string) }}
              </span>
            </td>
            <td>
              {{
                formatStatusCounts(
                  d.status_breakdown as Record<string, number>,
                  false,
                )
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="section-card">
      <h2>提交记录</h2>
      <table class="data-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>状态</th>
            <th>用时</th>
            <th>语言</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!submissions.length">
            <td colspan="4" class="empty">暂无提交记录</td>
          </tr>
          <tr v-for="(s, idx) in submissions" :key="idx">
            <td>{{ s.submitted_at }}</td>
            <td :class="s.status === 'Accepted' ? 'ok' : 'bad'">
              {{ s.status }}
            </td>
            <td>
              {{ s.runtime_ms != null ? s.runtime_ms + "ms" : "—" }}
            </td>
            <td>{{ s.language || "—" }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </main>
</template>

<style scoped>
.back {
  font-size: 13px;
  display: inline-block;
  margin-bottom: 10px;
}
.master-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}
.master-btn {
  border: 1px solid var(--line);
  background: #fff;
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  padding: 7px 14px;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 0 1px 0 rgba(28, 25, 23, 0.04);
}
.master-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
.master-btn.on {
  border-color: #d4a0a8;
  background: var(--accent-soft);
  color: #9a5563;
}
.master-btn.on:hover {
  border-color: #d48888;
  background: #f8e8e8;
  color: #b45c5c;
}
.master-btn:disabled {
  cursor: wait;
  opacity: 0.55;
}
.master-hint {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.35;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.tag {
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 999px;
  background: #e7e5e4;
  color: #44403c;
}
:deep(.diff-easy) {
  color: #6d9f82;
}
:deep(.diff-medium) {
  color: #d4a06a;
}
:deep(.diff-hard) {
  color: #c97878;
}
.breakdown {
  font-size: 13px;
  line-height: 1.7;
  color: #44403c;
}
.pill {
  display: inline-block;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f5f5f4;
}
.pill-improved {
  background: #e0f0e6;
  color: #4f7d62;
}
.pill-stuck {
  background: #f8e4e4;
  color: #a85d5d;
}
.pill-declined {
  background: #f7ead8;
  color: #a67a45;
}
.pill-first_ac {
  background: #e5f3ee;
  color: #5a8f7c;
}
.metrics-grid :deep(.metric-card .value) {
  font-size: 22px;
}
</style>
