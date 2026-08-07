<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { storeToRefs } from "pinia";
import AppHeader from "@/components/AppHeader.vue";
import MetricCard from "@/components/MetricCard.vue";
import DayNavigator from "@/components/DayNavigator.vue";
import Pager from "@/components/Pager.vue";
import WeekBars from "@/components/WeekBars.vue";
import LearningPrefs from "@/components/LearningPrefs.vue";
import { useStatsStore } from "@/stores/stats";
import { useLearningStore } from "@/stores/learning";
import {
  chinaTodayStr,
  shiftDate,
  paginate,
  formatStatusCounts,
} from "@/utils/format";

const PAGE_SIZE = 8;
const stats = useStatsStore();
const learning = useLearningStore();

const {
  selectedDate,
  statusText,
  todaySubmissions,
  todayAccepted,
  streakDays,
  acceptanceRate,
  last7,
  todayWrong,
  recent,
  problems,
  isViewingToday,
  dayWord,
  aggregatedDayProblems,
} = storeToRefs(stats);

const { reviewDue } = storeToRefs(learning);

const problemsQuery = ref("");
const problemsPage = ref(1);
const wrongPage = ref(1);

let pollTimer: ReturnType<typeof setInterval> | null = null;

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

onMounted(() => {
  stats.refresh({ reloadProblems: true });
  learning.load();
  pollTimer = setInterval(() => {
    if (isViewingToday.value) stats.refresh();
  }, 3000);
});

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>

<template>
  <AppHeader title="CodeArena">
    <template #subtitle>
      <span>{{ statusText }}</span>
      ·
      <RouterLink to="/ops">维护台</RouterLink>
      · LeetCode 刷题追踪
    </template>
    <template #actions>
      <LearningPrefs />
    </template>
  </AppHeader>

  <main class="page-main">
    <DayNavigator
      :selected-date="selectedDate"
      :is-today="isViewingToday"
      :heading="isViewingToday ? '今日' : selectedDate"
      @prev="onPrev"
      @next="onNext"
      @today="onToday"
      @pick="onPick"
    />

    <div class="metrics-grid">
      <MetricCard :label="dayWord + '提交'" :value="todaySubmissions" />
      <MetricCard :label="dayWord + '通过'" :value="todayAccepted" />
      <MetricCard label="连续打卡" :value="streakDays" />
      <MetricCard label="累计通过率" :value="acceptanceRate" />
    </div>

    <section class="section-card">
      <h2>近 7 日</h2>
      <WeekBars
        :bars="last7"
        :selected-date="selectedDate"
        @select="(d) => stats.setSelectedDate(d)"
      />
    </section>

    <section class="section-card">
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
          <tr v-for="c in reviewDue" :key="c.problem_id || c.id">
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${c.problem_id || c.id}`"
              >
                {{ c.problem_id || c.id }}. {{ c.title }}
              </RouterLink>
            </td>
            <td>{{ c.difficulty || "-" }}</td>
            <td class="reason-cell">{{ c.reason || "" }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="sub" style="margin: 0; font-size: 13px">
        今天没有到期复习题
      </p>
    </section>

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
            <td>{{ p.last_submitted_at || "—" }}</td>
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
          </tr>
        </thead>
        <tbody>
          <tr v-for="(i, idx) in recent" :key="idx">
            <td>{{ i.submitted_at }}</td>
            <td>
              <RouterLink
                class="link-title"
                :to="`/problems/${i.problem_id}`"
              >
                {{ i.problem_id }}. {{ i.title }}
              </RouterLink>
            </td>
            <td :class="{ ok: i.status === 'Accepted' }">{{ i.status }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </main>
</template>

<style scoped>
.reason-cell {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.4;
  max-width: 280px;
}
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
  padding: 7px 10px;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
}
</style>
