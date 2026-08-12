import { defineStore } from "pinia";
import { ref, computed } from "vue";
import api from "@/api/client";
import type { LearnList } from "./learning";
import { toUserMessage } from "@/utils/userMessage";

export interface OpsConfig {
  host?: string;
  port?: number | string;
  autostart?: boolean;
  db_path_readonly?: string;
  llm?: {
    provider?: string;
    api_provider?: string;
    coach_model?: string;
    has_api_key?: boolean;
    api_key?: string;
    base_url?: string;
  };
}

export interface ListItem {
  id: number;
  title?: string;
}

export const useOpsStore = defineStore("ops", () => {
  const config = ref<OpsConfig>({});
  const configMsg = ref("");
  const configMsgKind = ref<"ok" | "err" | "">("");

  const listMode = ref(false);
  const kgMode = ref(false);
  const activeListId = ref("");
  const lists = ref<LearnList[]>([]);
  const learnMsg = ref("");
  const learnMsgKind = ref<"ok" | "err" | "">("");

  const llmProvider = ref<"ollama" | "api">("ollama");
  const llmModel = ref("");
  const llmApiKey = ref("");
  const llmBaseUrl = ref("");
  const llmHasKey = ref(false);
  const llmKeyMask = ref("");
  const llmMsg = ref("");
  const llmMsgKind = ref<"ok" | "err" | "">("");
  const modelTouched = ref(false);

  const logsMsg = ref("");
  const logsMsgKind = ref<"ok" | "err" | "">("");
  const rebuildMsg = ref("");
  const rebuildMsgKind = ref<"ok" | "err" | "">("");
  const kgMsg = ref("");
  const kgMsgKind = ref<"ok" | "err" | "">("");
  const kgOut = ref("");
  const kgOutVisible = ref(false);

  const importTarget = ref<"existing" | "new">("existing");
  const importMode = ref<"append" | "overwrite">("append");
  const importSetActive = ref(true);
  const importExistingList = ref("");
  const importNewName = ref("");
  const importJson = ref("");
  const existingItems = ref<ListItem[]>([]);
  const existingEmptyText = ref("暂无自定义题单，可切换到「新建题单」");

  const sampleOpen = ref(false);
  const sampleKind = ref<"list" | "single">("list");
  const sampleText = ref("");
  const sampleMsg = ref("");
  const sampleMsgKind = ref<"ok" | "err" | "">("");

  const writableLists = computed(() =>
    lists.value.filter((x) => !x.readonly),
  );

  function setMsg(
    which:
      | "config"
      | "learn"
      | "llm"
      | "logs"
      | "rebuild"
      | "kg"
      | "sample",
    text: string,
    kind: "ok" | "err" | "" = "",
  ) {
    const display =
      kind === "err" ? toUserMessage(text, "操作失败，请稍后再试") : text;
    const map = {
      config: [configMsg, configMsgKind],
      learn: [learnMsg, learnMsgKind],
      llm: [llmMsg, llmMsgKind],
      logs: [logsMsg, logsMsgKind],
      rebuild: [rebuildMsg, rebuildMsgKind],
      kg: [kgMsg, kgMsgKind],
      sample: [sampleMsg, sampleMsgKind],
    } as const;
    const [t, k] = map[which];
    t.value = display;
    k.value = kind;
  }

  function applyLlmForm(cfg: OpsConfig) {
    const llm = cfg.llm || {};
    llmProvider.value = llm.provider === "api" ? "api" : "ollama";
    llmModel.value = llm.coach_model || "";
    modelTouched.value = false;
    llmApiKey.value = "";
    llmBaseUrl.value = llm.base_url || "";
    llmHasKey.value = !!llm.has_api_key;
    llmKeyMask.value = llm.has_api_key
      ? `已保存 Key：${llm.api_key || "***"}`
      : "当前未保存 API Key";
    syncProviderUi();
  }

  function syncProviderUi() {
    if (
      llmProvider.value === "api" &&
      (!llmModel.value || llmModel.value.includes("qwen")) &&
      !modelTouched.value
    ) {
      llmModel.value = "deepseek-chat";
    }
  }

  async function loadConfig() {
    setMsg("config", "加载中…");
    try {
      const { data } = await api.get("/ops/config");
      if (data.status !== "ok") throw new Error(data.message || "failed");
      config.value = data.config || {};
      applyLlmForm(config.value);
      setMsg("config", "已更新", "ok");
    } catch (err) {
      setMsg("config", String(err), "err");
    }
  }

  async function saveLlm() {
    setMsg("llm", "保存中…");
    try {
      const provider = llmProvider.value;
      const { data } = await api.post("/ops/llm/config", {
        provider,
        api_provider: provider === "api" ? "deepseek" : "",
        coach_model: llmModel.value.trim(),
        base_url: llmBaseUrl.value.trim(),
        api_key: llmApiKey.value,
      });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      llmApiKey.value = "";
      applyLlmForm(data.config || {});
      setMsg("llm", "已保存 Nex 模型配置", "ok");
      await loadConfig();
    } catch (err) {
      setMsg("llm", String(err), "err");
    }
  }

  async function testLlm() {
    const apiMode = llmProvider.value === "api";
    const typed = llmApiKey.value.trim().length > 0;
    if (apiMode && !llmHasKey.value && !typed) {
      setMsg("llm", "请先填写并保存 API Key 后再测试", "err");
      return;
    }
    if (apiMode && typed && !llmHasKey.value) {
      setMsg("llm", "请先点击「保存」，再用已保存配置测试连接", "err");
      return;
    }
    setMsg("llm", "测试中…");
    try {
      const { data } = await api.post("/ops/llm/test", {});
      if (data.status !== "ok") throw new Error(data.message || "failed");
      setMsg(
        "llm",
        `连接成功 · ${data.provider}${data.api_provider ? "/" + data.api_provider : ""} · ${data.coach_model} · ${(data.reply_preview || "").replace(/\s+/g, " ").slice(0, 60)}`,
        "ok",
      );
    } catch (err) {
      setMsg("llm", String(err), "err");
    }
  }

  async function clearLlmKey() {
    if (!confirm("确认清除 API Key，并切回 Ollama？")) return;
    setMsg("llm", "清除中…");
    try {
      const { data } = await api.post("/ops/llm/clear-key", {
        confirm: true,
        switch_to_ollama: true,
      });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      llmApiKey.value = "";
      applyLlmForm(data.config || {});
      setMsg("llm", data.message || "已清除", "ok");
      await loadConfig();
    } catch (err) {
      setMsg("llm", String(err), "err");
    }
  }

  async function cleanLogs() {
    if (!confirm("确认清理服务日志与 Nex 调试日志？")) return;
    setMsg("logs", "清理中…");
    try {
      const { data } = await api.post("/ops/logs/clean", {
        confirm: true,
        include_coach_debug: true,
      });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      setMsg(
        "logs",
        `已删除 ${data.count} 个文件（服务 ${(data.service_logs || []).length} · Nex 调试 ${(data.coach_debug_logs || []).length}）`,
        "ok",
      );
    } catch (err) {
      setMsg("logs", String(err), "err");
    }
  }

  async function rebuildStats() {
    if (!confirm("确认从 submissions 全量重建题目汇总？提交记录不会删除。"))
      return;
    setMsg("rebuild", "重建中…");
    try {
      const { data } = await api.post("/ops/stats/rebuild", {
        confirm: true,
        from_scratch: true,
      });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      setMsg("rebuild", `已重建 ${data.problems} 题汇总`, "ok");
    } catch (err) {
      setMsg("rebuild", String(err), "err");
    }
  }

  async function kgStatus() {
    setMsg("kg", "加载中…");
    try {
      const { data } = await api.get("/ops/kg");
      if (data.status !== "ok") throw new Error(data.message || "failed");
      kgOutVisible.value = true;
      kgOut.value = JSON.stringify(data, null, 2);
      setMsg(
        "kg",
        data.imported
          ? `已就绪 · ${data.tracks} 路线 / ${data.problems} 题`
          : "尚未就绪",
        "ok",
      );
    } catch (err) {
      setMsg("kg", String(err), "err");
    }
  }

  async function kgImport() {
    if (!confirm("确认重建内嵌学习路线图？将重建 kg_* 表。")) return;
    setMsg("kg", "重建中…");
    try {
      const { data } = await api.post("/ops/kg/import", { confirm: true });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      kgOutVisible.value = true;
      kgOut.value = JSON.stringify(data, null, 2);
      setMsg(
        "kg",
        `重建完成 · ${data.tracks} tracks / ${data.problems} problems`,
        "ok",
      );
    } catch (err) {
      setMsg("kg", String(err), "err");
    }
  }

  async function loadExistingItems() {
    const listId = importExistingList.value;
    if (!listId) {
      existingItems.value = [];
      existingEmptyText.value = "暂无自定义题单，可切换到「新建题单」";
      return;
    }
    try {
      const { data } = await api.get(`/lists/${encodeURIComponent(listId)}`);
      if (data.status !== "ok") throw new Error(data.message || "failed");
      const items = data.items || [];
      if (!items.length) {
        existingItems.value = [];
        existingEmptyText.value = "这张题单还没有题目，可在下方导入";
        return;
      }
      existingItems.value = items;
      existingEmptyText.value = "";
    } catch (err) {
      setMsg("learn", String(err), "err");
    }
  }

  async function loadLearning() {
    try {
      const { data } = await api.get("/learning");
      if (data.status !== "ok") throw new Error(data.message || "failed");
      lists.value = data.lists || [];
      const L = data.learning || {};
      listMode.value = !!L.list_mode;
      kgMode.value = !!L.kg_mode;
      const active = lists.value.find((x) => x.active);
      activeListId.value = active?.id || lists.value[0]?.id || "";

      const writable = writableLists.value;
      if (writable.length) {
        const prev = importExistingList.value;
        const match = writable.find((x) => x.id === prev);
        importExistingList.value =
          match?.id || writable.find((x) => x.active)?.id || writable[0].id;
      } else {
        importExistingList.value = "";
      }
      if (importTarget.value === "existing") {
        await loadExistingItems();
      }
    } catch (err) {
      setMsg("learn", String(err), "err");
    }
  }

  async function saveLearning() {
    try {
      const { data } = await api.post("/learning", {
        list_mode: listMode.value,
        kg_mode: kgMode.value,
        active_list_id: activeListId.value,
      });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      setMsg("learn", "学习偏好已保存", "ok");
      await loadLearning();
    } catch (err) {
      setMsg("learn", String(err), "err");
    }
  }

  async function restoreHot100() {
    try {
      const { data } = await api.post("/lists/active", {
        list_id: "hot100",
        restore_default: true,
      });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      setMsg("learn", "已恢复默认 Hot100", "ok");
      await loadLearning();
    } catch (err) {
      setMsg("learn", String(err), "err");
    }
  }

  async function removeListItem(problemId: number | string) {
    if (!confirm("从题单中移除该题？")) return;
    const listId = importExistingList.value;
    try {
      const { data } = await api.delete(
        `/lists/${encodeURIComponent(listId)}/items/${encodeURIComponent(String(problemId))}`,
      );
      if (data.status !== "ok") throw new Error(data.message || "移除失败");
      setMsg("learn", `已移除题 ${problemId}`, "ok");
      await loadLearning();
    } catch (err) {
      setMsg("learn", String(err), "err");
    }
  }

  async function deleteList() {
    const listId = importExistingList.value;
    if (!listId) return;
    if (!confirm(`确认删除题单「${listId}」？题目记录会一并移除。`)) return;
    try {
      const { data } = await api.delete(
        `/lists/${encodeURIComponent(listId)}`,
      );
      if (data.status !== "ok") throw new Error(data.message || "failed");
      setMsg("learn", `已删除题单 ${listId}`, "ok");
      await loadLearning();
    } catch (err) {
      setMsg("learn", String(err), "err");
    }
  }

  async function importList() {
    setMsg("learn", "导入中…");
    try {
      let parsed: unknown;
      try {
        parsed = JSON.parse(importJson.value);
      } catch {
        throw new Error("JSON 解析失败");
      }
      if (
        importMode.value === "overwrite" &&
        !confirm("覆盖会清空该题单现有题目，确认继续？")
      ) {
        setMsg("learn", "已取消覆盖");
        return;
      }

      let listId = "";
      if (importTarget.value === "new") {
        const name = importNewName.value.trim();
        if (!name) throw new Error("请填写新建题单名称");
        const created = await api.post("/lists", {
          name,
          set_active: false,
        });
        if (created.data.status !== "ok") {
          throw new Error(created.data.message || "创建题单失败");
        }
        listId = created.data.list.id;
      } else {
        listId = importExistingList.value;
        if (!listId) throw new Error("请先选择已有题单，或改为新建");
      }

      const { data } = await api.post("/lists/import", {
        mode: importMode.value,
        list_id: listId,
        create_if_missing: false,
        set_active: importSetActive.value,
        data: parsed,
      });
      if (data.status !== "ok") throw new Error(data.message || "failed");
      setMsg(
        "learn",
        `导入成功 · ${data.list_id} 新增 ${data.added} / 跳过重复 ${data.skipped_dupes} / 合计 ${data.total}`,
        "ok",
      );
      importTarget.value = "existing";
      await loadLearning();
      importExistingList.value = data.list_id;
      await loadExistingItems();
    } catch (err) {
      setMsg("learn", toUserMessage(err, "导入失败，请检查题单内容后重试"), "err");
    }
  }

  async function loadSample(kind?: "list" | "single") {
    sampleKind.value = kind || sampleKind.value;
    setMsg("sample", "加载中…");
    try {
      const res = await fetch(
        `/api/lists/sample?kind=${encodeURIComponent(sampleKind.value)}`,
      );
      if (!res.ok) throw new Error("加载失败");
      sampleText.value = (await res.text()).trim();
      setMsg("sample", "");
    } catch (err) {
      sampleText.value = "";
      setMsg("sample", String(err), "err");
    }
  }

  async function copySample() {
    if (!sampleText.value) return;
    try {
      await navigator.clipboard.writeText(sampleText.value);
      setMsg("sample", "已复制到剪贴板", "ok");
    } catch {
      setMsg("sample", "复制失败，请手动全选复制", "err");
    }
  }

  function fillSample() {
    if (!sampleText.value) return;
    importJson.value = sampleText.value;
    setMsg("sample", "已填入导入框", "ok");
    sampleOpen.value = false;
  }

  return {
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
    modelTouched,
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
    syncProviderUi,
    loadConfig,
    saveLlm,
    testLlm,
    clearLlmKey,
    cleanLogs,
    rebuildStats,
    kgStatus,
    kgImport,
    loadExistingItems,
    loadLearning,
    saveLearning,
    restoreHot100,
    removeListItem,
    deleteList,
    importList,
    loadSample,
    copySample,
    fillSample,
  };
});
