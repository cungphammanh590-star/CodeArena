import { defineStore } from "pinia";
import { ref, computed } from "vue";
import api from "@/api/client";
import { toUserMessage } from "@/utils/userMessage";

export interface LearnList {
  id: string;
  name: string;
  total: number;
  active?: boolean;
  readonly?: boolean;
}

export interface ReviewDue {
  problem_id?: number;
  id?: number;
  title: string;
  difficulty?: string;
  reason?: string;
  kind?: "plan" | "review" | "both" | string;
}

export interface MasteredItem {
  problem_id: number;
  title?: string;
}

export const useLearningStore = defineStore("learning", () => {
  const listMode = ref(false);
  const kgMode = ref(false);
  const activeListId = ref("");
  const lists = ref<LearnList[]>([]);
  const progress = ref<{
    done?: number;
    total?: number;
    mastered_count?: number;
  }>({});
  const planToday = ref<ReviewDue[]>([]);
  const reviewDue = ref<ReviewDue[]>([]);
  const mastered = ref<MasteredItem[]>([]);
  const message = ref("");
  const messageKind = ref<"ok" | "err" | "">("");

  const modesOn = computed(() => listMode.value || kgMode.value);

  const progressText = computed(() => {
    const p = progress.value;
    let prog = `进度 ${p.done || 0}/${p.total || 0}（仅计 AC）`;
    if (p.mastered_count) {
      prog += ` · 已掌握屏蔽 ${p.mastered_count} 题`;
    }
    return prog;
  });

  function setMsg(text: string, kind: "ok" | "err" | "" = "") {
    message.value = text;
    messageKind.value = kind;
  }

  async function load() {
    try {
      const [learnRes, reviewRes, masteredRes] = await Promise.all([
        api.get("/learning"),
        api.get("/review/today"),
        api.get("/mastered"),
      ]);
      const learn = learnRes.data;
      const review = reviewRes.data;
      const masteredData = masteredRes.data;

      if (learn.status === "ok") {
        const L = learn.learning || {};
        listMode.value = !!L.list_mode;
        kgMode.value = !!L.kg_mode;
        lists.value = learn.lists || [];
        const active = lists.value.find((x) => x.active);
        activeListId.value =
          L.active_list_id || active?.id || lists.value[0]?.id || "";
        const raw = learn.progress || {};
        progress.value = {
          done: Number(raw.done ?? raw.list_done ?? 0),
          total: Number(raw.total ?? raw.list_total ?? 0),
          mastered_count: Number(
            raw.mastered_count ?? raw.list_mastered ?? 0,
          ),
        };
      }

      planToday.value =
        (review && (review.plan_items || review.plan?.items)) || [];
      reviewDue.value =
        (review && (review.review_items || review.due)) || [];
      mastered.value = (masteredData && masteredData.items) || [];
    } catch (err) {
      setMsg(toUserMessage(err, "加载失败，请稍后再试"), "err");
    }
  }

  async function save() {
    setMsg("");
    try {
      const { data } = await api.post("/learning", {
        list_mode: listMode.value,
        kg_mode: kgMode.value,
        active_list_id: activeListId.value,
      });
      if (data.status !== "ok") {
        throw new Error(data.message || "保存失败");
      }
      setMsg("已保存", "ok");
      await load();
    } catch (err) {
      setMsg(toUserMessage(err, "保存失败，请稍后再试"), "err");
    }
  }

  async function unmaster(problemId: number | string) {
    await api.delete(`/problems/${problemId}/mastered`);
    await load();
  }

  return {
    listMode,
    kgMode,
    activeListId,
    lists,
    progress,
    planToday,
    reviewDue,
    mastered,
    message,
    messageKind,
    modesOn,
    progressText,
    setMsg,
    load,
    save,
    unmaster,
  };
});
