<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import AppHeader from "@/components/AppHeader.vue";
import MetricCard from "@/components/MetricCard.vue";
import DayNavigator from "@/components/DayNavigator.vue";
import Pager from "@/components/Pager.vue";
import WeekBars from "@/components/WeekBars.vue";
import { useStatsStore } from "@/stores/stats";
import { useLearningStore } from "@/stores/learning";
import { api } from "@/api/client";
import {
  chinaTodayStr,
  shiftDate,
  paginate,
  formatStatusCounts,
  formatDisplayTime,
} from "@/utils/format";

const PAGE_SIZE = 8;
const stats = useStatsStore();
const learning = useLearningStore();

const {
  selectedDate,
  statusText,
  currentUsername,
  todaySubmissions,
  todayAccepted,
  streakDays,
  last7,
  todayWrong,
  recent,
  problems,
  isViewingToday,
  dayWord,
  aggregatedDayProblems,
} = storeToRefs(stats);

const { planToday, reviewDue } = storeToRefs(learning);

const problemsQuery = ref("");
const problemsPage = ref(1);
const wrongPage = ref(1);
const refreshing = ref(false);
const latestNex = ref<Record<string, any> | null>(null);

const filteredProblems = computed(() => {
  const raw = problemsQuery.value.trim().toLowerCase();
  if (!raw) return problems.value;
  return problems.value.filter((p) => {
    const idStr = String(p.problem_id);
    const title = String(p.title || "").toLowerCase();
    return idStr.includes(raw) || title.includes(raw);
  });
});

const problemsPageState = computed(() =>
  paginate(filteredProblems.value, problemsPage.value, PAGE_SIZE),
);

const wrongPageState = computed(() =>
  paginate(todayWrong.value, wrongPage.value, PAGE_SIZE),
);

watch(problemsQuery, () => {
  problemsPage.value = 1;
});

watch(selectedDate, () => {
  wrongPage.value = 1;
});

function onPrev() {
  stats.setSelectedDate(shiftDate(selectedDate.value, -1));
}

function onNext() {
  if (isViewingToday.value) return;
  const next = shiftDate(selectedDate.value, 1);
  const today = chinaTodayStr();
  stats.setSelectedDate(next > today ? today : next);
}

function onToday() {
  stats.setSelectedDate(chinaTodayStr());
}

function onPick(date: string) {
  if (date) stats.setSelectedDate(date);
}

async function onRefresh() {
  if (refreshing.value) return;
  refreshing.value = true;
  try {
    await Promise.all([
      stats.refresh({ reloadProblems: true }),
      learning.load(),
      api.get("/learning/archive").then(({ data }) => { latestNex.value = data.latest_nex || null; }),
    ]);
  } finally {
    refreshing.value = false;
  }
}

onMounted(() => {
  onRefresh();
});
</script>

<template>
  <AppHeader title="今天学什么？">
    <template #subtitle>
      <span>从一个清晰的小任务开始。</span>
      <template v-if="currentUsername">
        · {{ currentUsername }}
      </template>
    </template>
    <template #actions>
      <button
        type="button"
        class="btn-secondary"
        :disabled="refreshing"
        @click="onRefresh"
      >
        {{ refreshing ? "刷新中…" : "刷新" }}
      </button>
    </template>
  </AppHeader>

  <main class="page-main">
    <section class="hero-cta">
      <div>
        <p class="hero-kicker">今日学习入口</p>
        <h2>和 Nex 做一轮有目标的练习</h2>
        <p>从当前任务、一次提交，或你卡住的知识点开始。Nex 会把本轮收获放回你的复习节奏。</p>
      </div>
      <RouterLink class="btn-primary" to="/coach">开始 Nex 陪练</RouterLink>
    </section>

    <div class="metrics-grid">
      <MetricCard label="今日提交" :value="todaySubmissions" />
      <MetricCard label="今日通过" :value="todayAccepted" />
      <MetricCard label="连续打卡" :value="streakDays" />
    </div>

    <section v-if="latestNex" class="section-card nex-summary">
      <div class="section-head">
        <h2>最近一轮 Nex 总结</h2>
        <span class="section-meta">{{ latestNex.goal }}</span>
      </div>
      <p>{{ latestNex.mastered || latestNex.remaining || "本轮记录已保存。" }}</p>
      <RouterLink class="summary-link" to="/coach">{{ latestNex.remaining ? "继续本轮" : "开始下一轮" }}</RouterLink>
    </section>

    <section class="section-card task-card">
      <div class="section-head">
        <h2>今日计划</h2>
        <span class="section-meta">
          {{ planToday.length ? planToday.length + " 题待刷" : "" }}
        </span>
      </div>
      <table v-if="planToday.length" class="data-table">
        <thead>
          <tr>
            <th>题目</th>
            <th>难度</th>
            <th>来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in planToday" :key="'p-' + (c.problem_id || c.id)">
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${c.problem_id || c.id}`"
              >
                {{ c.problem_id || c.id }}. {{ c.title || "（无标题）" }}
              </RouterLink>
            </td>
            <td>{{ c.difficulty || "-" }}</td>
            <td class="reason-cell">{{ c.reason || "今日计划任务" }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty">还没有计划任务。告诉 Nex 你想练什么，我来帮你安排第一步。</p>
    </section>

    <section class="section-card task-card">
      <div class="section-head">
        <h2>今日复习</h2>
        <span class="section-meta">
          {{ reviewDue.length ? reviewDue.length + " 题到期" : "" }}
        </span>
      </div>
      <table v-if="reviewDue.length" class="data-table">
        <thead>
          <tr>
            <th>题目</th>
            <th>难度</th>
            <th>为什么复习</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in reviewDue" :key="'r-' + (c.problem_id || c.id)">
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${c.problem_id || c.id}`"
              >
                {{ c.problem_id || c.id }}. {{ c.title || "（无标题）" }}
              </RouterLink>
            </td>
            <td>{{ c.difficulty || "-" }}</td>
            <td class="reason-cell">{{ c.reason || "间隔复习到期" }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty">今天没有到期复习。完成一次练习后，我会在合适的时间提醒你回来巩固。</p>
    </section>

    <section class="section-card progress-card">
      <div class="section-head">
        <h2>近 7 日学习</h2>
        <span class="section-meta">{{ statusText }}</span>
      </div>
      <WeekBars
        :bars="last7"
        :selected-date="selectedDate"
        @select="(d) => stats.setSelectedDate(d)"
      />
    </section>

    <details class="activity-details">
      <summary>查看学习记录与题目画像</summary>
      <div class="activity-content">
        <DayNavigator
          :selected-date="selectedDate"
          :is-today="isViewingToday"
          :heading="isViewingToday ? '今日' : selectedDate"
          @prev="onPrev"
          @next="onNext"
          @today="onToday"
          @pick="onPick"
        />

    <section class="section-card">
      <h2>{{ dayWord }}题目</h2>
      <table class="data-table">
        <thead>
          <tr>
            <th>题目</th>
            <th>难度</th>
            <th>尝试</th>
            <th>结果</th>
            <th>用时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!aggregatedDayProblems.length">
            <td colspan="5">{{ dayWord }}暂无提交</td>
          </tr>
          <tr
            v-for="row in aggregatedDayProblems"
            :key="row.problem_id"
          >
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${row.problem_id}`"
              >
                {{ row.problem_id }}. {{ row.title }}
              </RouterLink>
            </td>
            <td>{{ row.difficulty || "-" }}</td>
            <td>{{ row.attempts }}</td>
            <td>
              <span v-if="row.accepted" class="ok">已通过</span>
              <span v-else class="fail">未通过</span>
            </td>
            <td>
              {{ row.bestRuntime != null ? row.bestRuntime + "ms" : "—" }}
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="section-card">
      <h2>题目画像</h2>
      <div class="toolbar">
        <input
          v-model="problemsQuery"
          type="search"
          placeholder="搜索题号或标题…"
          autocomplete="off"
        />
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th>题目</th>
            <th>难度</th>
            <th>尝试</th>
            <th>AC</th>
            <th>挣扎指数</th>
            <th>最近提交</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!problems.length">
            <td colspan="6">暂无题目记录</td>
          </tr>
          <tr v-else-if="!filteredProblems.length">
            <td colspan="6">无匹配题目</td>
          </tr>
          <tr
            v-for="p in problemsPageState.items"
            :key="p.problem_id"
          >
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${p.problem_id}`"
              >
                {{ p.problem_id }}. {{ p.title }}
              </RouterLink>
            </td>
            <td>{{ p.difficulty || "-" }}</td>
            <td>{{ p.total_attempts }}</td>
            <td>{{ p.accepted_count }}</td>
            <td>{{ Math.round((p.struggle_score || 0) * 100) }}%</td>
            <td class="time-cell">{{ formatDisplayTime(p.last_submitted_at) }}</td>
          </tr>
        </tbody>
      </table>
      <Pager
        :page="problemsPageState.page"
        :total-pages="problemsPageState.totalPages"
        :start="problemsPageState.start"
        :end="problemsPageState.end"
        :total="problemsPageState.total"
        @change="(p) => (problemsPage = p)"
      />
    </section>

    <section class="section-card">
      <h2>{{ dayWord }}错题</h2>
      <table class="data-table">
        <thead>
          <tr>
            <th>题目</th>
            <th>难度</th>
            <th>错误汇总</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!todayWrong.length">
            <td colspan="3">{{ dayWord }}无错题</td>
          </tr>
          <tr
            v-for="i in wrongPageState.items"
            :key="i.problem_id"
          >
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${i.problem_id}`"
              >
                {{ i.problem_id }}. {{ i.title }}
              </RouterLink>
            </td>
            <td>{{ i.difficulty || "-" }}</td>
            <td>{{ formatStatusCounts(i.status_counts) }}</td>
          </tr>
        </tbody>
      </table>
      <Pager
        :page="wrongPageState.page"
        :total-pages="wrongPageState.totalPages"
        :start="wrongPageState.start"
        :end="wrongPageState.end"
        :total="wrongPageState.total"
        @change="(p) => (wrongPage = p)"
      />
    </section>

    <section class="section-card">
      <h2>最近提交</h2>
      <table class="data-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>题目</th>
            <th>状态</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!recent.length">
            <td colspan="4">暂无提交。用扩展同步力扣提交后会出现在这里。</td>
          </tr>
          <tr v-for="(i, idx) in recent" :key="idx">
            <td class="time-cell">{{ formatDisplayTime(i.submitted_at) }}</td>
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${i.problem_id}`"
              >
                {{ i.problem_id }}. {{ i.title }}
              </RouterLink>
            </td>
            <td :class="{ ok: i.status === 'Accepted' }">{{ i.status }}</td>
            <td>
              <RouterLink
                class="link-title"
                :to="`/coach?submission=${encodeURIComponent(String(i.submission_id || ''))}&problem_id=${i.problem_id}`"
              >
                Nex
              </RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
      </div>
    </details>
  </main>
</template>

<style scoped>
.reason-cell {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.4;
  max-width: 280px;
}
.hero-cta { align-items: center; }
.hero-kicker { margin: 0 0 4px; color: var(--accent); font-size: 13px; font-weight: 650; }
.hero-cta h2 { margin: 0; font-size: 23px; letter-spacing: -.03em; }
.hero-cta p:not(.hero-kicker) { max-width: 620px; margin: 8px 0 0; color: var(--muted); }
.task-card { margin-bottom: 12px; }
.progress-card { margin-top: 16px; }
.activity-details { margin-top: 24px; }
.activity-details > summary { color: var(--muted); font-size: 14px; font-weight: 600; cursor: pointer; }
.activity-details[open] > summary { margin-bottom: 12px; color: var(--accent); }
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
}
.toolbar input[type="search"] {
  flex: 1;
  min-width: 160px;
  border: 1px solid var(--line);
  background: #fff;
  border-radius: 8px;
  padding: 10px 12px;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
  min-height: 40px;
}
.hero-cta :deep(.btn-primary),
.hero-cta.btn-primary,
a.btn-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
}
a.btn-primary:hover {
  text-decoration: none;
}
</style>
