import { defineStore } from "pinia";
import { ref, computed } from "vue";
import api from "@/api/client";
import { chinaTodayStr } from "@/utils/format";

export interface DayBar {
  date: string;
  submissions: number;
}

export interface DayItem {
  problem_id: number;
  title: string;
  difficulty?: string;
  status: string;
  runtime_ms?: number | null;
  submitted_at?: string;
}

export interface WrongItem {
  problem_id: number;
  title: string;
  difficulty?: string;
  status_counts?: Record<string, number>;
}

export interface ProblemRow {
  problem_id: number;
  title: string;
  difficulty?: string;
  total_attempts: number;
  accepted_count: number;
  struggle_score?: number;
  last_submitted_at?: string;
}

export interface AggregatedDayProblem {
  problem_id: number;
  title: string;
  difficulty?: string;
  attempts: number;
  accepted: boolean;
  bestRuntime: number | null;
}

export const useStatsStore = defineStore("stats", () => {
  const selectedDate = ref(chinaTodayStr());
  const statusText = ref("加载中…");
  const todaySubmissions = ref<number | string>("—");
  const todayAccepted = ref<string>("—");
  const streakDays = ref<string>("—");
  const acceptanceRate = ref<string>("—");
  const last7 = ref<DayBar[]>([]);
  const todayItems = ref<DayItem[]>([]);
  const todayWrong = ref<WrongItem[]>([]);
  const recent = ref<DayItem[]>([]);
  const problems = ref<ProblemRow[]>([]);
  const problemsLoaded = ref(false);

  const isViewingToday = computed(
    () => selectedDate.value === chinaTodayStr(),
  );

  const dayWord = computed(() =>
    isViewingToday.value ? "今日" : selectedDate.value,
  );

  const aggregatedDayProblems = computed((): AggregatedDayProblem[] => {
    const map = new Map<number, AggregatedDayProblem>();
    for (const item of todayItems.value) {
      const pid = item.problem_id;
      let row = map.get(pid);
      if (!row) {
        row = {
          problem_id: pid,
          title: item.title,
          difficulty: item.difficulty,
          attempts: 0,
          accepted: false,
          bestRuntime: null,
        };
        map.set(pid, row);
      }
      row.attempts += 1;
      if (item.status === "Accepted") {
        row.accepted = true;
        if (
          item.runtime_ms != null &&
          (row.bestRuntime == null || item.runtime_ms < row.bestRuntime)
        ) {
          row.bestRuntime = item.runtime_ms;
        }
      }
    }
    return Array.from(map.values());
  });

  async function loadProblems() {
    try {
      const { data } = await api.get("/problems");
      problems.value = data.problems || [];
      problemsLoaded.value = true;
    } catch {
      problems.value = [];
    }
  }

  async function refresh(opts?: { reloadProblems?: boolean }) {
    try {
      const { data } = await api.get("/stats", {
        params: { date: selectedDate.value },
      });
      if (data.date) selectedDate.value = data.date;

      todaySubmissions.value = data.today_submissions;
      todayAccepted.value = `${data.today_accepted} (${data.today_acceptance_rate}%)`;
      streakDays.value = `${data.streak_days} 天`;
      acceptanceRate.value = `${data.accepted_count}/${data.total_submissions} (${data.acceptance_rate}%)`;
      statusText.value = `已更新 ${new Date().toLocaleTimeString()}`;
      last7.value = data.last7 || [];
      todayItems.value = data.today_items || [];
      todayWrong.value = data.today_wrong || [];
      recent.value = data.recent || [];

      if (opts?.reloadProblems || !problemsLoaded.value) {
        await loadProblems();
      }
    } catch (err) {
      statusText.value = `无法读取统计：${err}`;
    }
  }

  function setSelectedDate(iso: string, opts?: { reloadProblems?: boolean }) {
    selectedDate.value = iso;
    return refresh(opts);
  }

  return {
    selectedDate,
    statusText,
    todaySubmissions,
    todayAccepted,
    streakDays,
    acceptanceRate,
    last7,
    todayItems,
    todayWrong,
    recent,
    problems,
    problemsLoaded,
    isViewingToday,
    dayWord,
    aggregatedDayProblems,
    loadProblems,
    refresh,
    setSelectedDate,
  };
});
