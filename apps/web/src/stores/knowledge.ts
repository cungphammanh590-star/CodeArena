import { defineStore } from "pinia";
import { computed, ref } from "vue";
import api from "@/api/client";
import { toUserMessage } from "@/utils/userMessage";

export type KbDocument = {
  id: number;
  title: string;
  source_type: string;
  status: string;
  failure_reason?: string | null;
  created_at?: string;
  updated_at?: string;
  raw_text?: string;
  cleaned_text?: string;
};

export type KnowledgePoint = {
  id: number;
  document_id: number;
  title: string;
  question?: string | null;
  answer?: string | null;
  topic?: string | null;
  source_title?: string | null;
  body?: string;
  body_preview?: string;
  refined?: boolean;
  score?: number;
  snippet?: string;
};

export type Flashcard = {
  knowledge_point_id: number;
  title: string;
  question: string;
  answer?: string;
  topic?: string | null;
  source_title?: string | null;
  due_at?: string;
  key_points_json?: string | null;
  kind?: "new" | "review" | string;
};

export const useKnowledgeStore = defineStore("knowledge", () => {
  const documents = ref<KbDocument[]>([]);
  const kps = ref<KnowledgePoint[]>([]);
  const selectedKp = ref<KnowledgePoint | null>(null);
  const selectedDocId = ref<number | null>(null);
  const kpQuery = ref("");
  const flashcards = ref<Flashcard[]>([]);
  const flashDueTotal = ref(0);
  const flashNewCount = ref(0);
  const flashReviewCount = ref(0);
  const msg = ref("");
  const msgKind = ref<"ok" | "err" | "">("");
  const busy = ref(false);

  const kpCountByDoc = computed(() => {
    const map = new Map<number, number>();
    for (const k of kps.value) {
      map.set(k.document_id, (map.get(k.document_id) || 0) + 1);
    }
    return map;
  });

  const filteredKps = computed(() => {
    const q = kpQuery.value.trim().toLowerCase();
    return kps.value.filter((k) => {
      if (selectedDocId.value != null && k.document_id !== selectedDocId.value) return false;
      if (!q) return true;
      const hay = `${k.title || ""} ${k.topic || ""} ${k.body_preview || ""}`.toLowerCase();
      return hay.includes(q);
    });
  });

  function setMsg(text: string, kind: "ok" | "err" | "" = "ok") {
    msg.value = text;
    msgKind.value = kind;
  }

  function selectDoc(id: number | null) {
    selectedDocId.value = id;
    selectedKp.value = null;
  }

  async function refresh() {
    busy.value = true;
    try {
      const [docsResp, kpsResp] = await Promise.all([
        api.get("/knowledge/documents"),
        api.get("/knowledge/kps"),
      ]);
      documents.value = docsResp.data?.documents || [];
      kps.value = kpsResp.data?.knowledge_points || [];
      if (
        selectedDocId.value != null &&
        !documents.value.some((d) => d.id === selectedDocId.value)
      ) {
        selectedDocId.value = null;
      }
      setMsg("", "");
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  async function createText(payload: {
    title?: string;
    source_type?: string;
    content: string;
  }) {
    busy.value = true;
    try {
      await api.post("/knowledge/documents", {
        title: payload.title || undefined,
        source_type: payload.source_type || "text",
        content: payload.content,
      });
      setMsg("已提交，正在处理…", "ok");
      await refresh();
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  async function uploadPdf(file: File, title?: string) {
    busy.value = true;
    try {
      const form = new FormData();
      form.append("file", file);
      if (title) form.append("title", title);
      await api.post("/knowledge/documents", form, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      setMsg("PDF 已上传，正在解析…", "ok");
      await refresh();
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  async function reprocess(id: number) {
    busy.value = true;
    try {
      await api.post(`/knowledge/documents/${id}/reprocess`);
      setMsg("已重新排队处理", "ok");
      await refresh();
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  async function removeDocument(id: number) {
    busy.value = true;
    try {
      await api.delete(`/knowledge/documents/${id}`);
      if (selectedDocId.value === id) selectedDocId.value = null;
      setMsg("文档已删除", "ok");
      await refresh();
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  async function openKp(id: number) {
    busy.value = true;
    try {
      const { data } = await api.get(`/knowledge/kps/${id}`);
      selectedKp.value = data?.knowledge_point || null;
      setMsg("", "");
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  async function removeKp(id: number) {
    busy.value = true;
    try {
      await api.delete(`/knowledge/kps/${id}`);
      if (selectedKp.value?.id === id) selectedKp.value = null;
      setMsg("知识点已删除", "ok");
      await refresh();
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  function clearSelected() {
    selectedKp.value = null;
  }

  async function loadFlashcards(limit = 20) {
    busy.value = true;
    try {
      const { data } = await api.get("/knowledge/flashcards/due", { params: { limit } });
      flashcards.value = data?.items || [];
      flashDueTotal.value = data?.due_total ?? flashcards.value.length;
      flashNewCount.value = data?.new_count ?? 0;
      flashReviewCount.value = data?.review_count ?? 0;
      setMsg("", "");
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  async function reviewFlashcard(kpId: number, grade: string) {
    busy.value = true;
    try {
      await api.post(`/knowledge/flashcards/${kpId}/review`, { grade });
      flashcards.value = flashcards.value.filter((c) => c.knowledge_point_id !== kpId);
      flashDueTotal.value = Math.max(0, flashDueTotal.value - 1);
      setMsg("已记录复习", "ok");
    } catch (e) {
      setMsg(toUserMessage(e), "err");
    } finally {
      busy.value = false;
    }
  }

  return {
    documents,
    kps,
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
    selectDoc,
    refresh,
    createText,
    uploadPdf,
    reprocess,
    removeDocument,
    openKp,
    removeKp,
    clearSelected,
    loadFlashcards,
    reviewFlashcard,
  };
});
