<script setup lang="ts">
import { onMounted, ref } from "vue";
import AppHeader from "@/components/AppHeader.vue";
import MetricCard from "@/components/MetricCard.vue";
import api from "@/api/client";
import { toUserMessage } from "@/utils/userMessage";
const week = ref(""); const report = ref<Record<string, any> | null>(null); const error = ref(""); const loading = ref(false);
async function load() { loading.value = true; error.value = ""; try { report.value = (await api.get("/learning/week-report", { params: week.value ? { week: week.value } : {} })).data; if (!week.value) week.value = report.value?.week_start || ""; } catch (e) { error.value = toUserMessage(e, "周报暂时加载不了"); } finally { loading.value = false; } }
onMounted(load);
</script>
<template><AppHeader title="学习周报" subtitle="按自然周回看节奏、重复错误与下一步。"><template #actions><input v-model="week" type="date" aria-label="选择所在周" @change="load"/></template></AppHeader><main class="page-main narrow"><p v-if="loading" class="empty">正在生成周报…</p><p v-else-if="error" class="empty">{{ error }}</p><template v-else-if="report"><p class="range">{{ report.week_start }} — {{ report.week_end }}</p><div class="metrics-grid"><MetricCard label="提交" :value="report.submission_count"/><MetricCard label="独立通过" :value="report.accepted_problem_count"/><MetricCard label="新增知识点" :value="report.knowledge_added"/></div><section class="section-card"><h2>需要留意</h2><p>有 {{ report.repeated_error_problem_count }} 道题在本周重复出错；当前复习队列共 {{ report.review_queue_count }} 道题。</p></section><section class="section-card next"><h2>下周建议</h2><p>{{ report.suggestion }}</p><RouterLink class="btn-primary" to="/coach">开始 Nex 陪练</RouterLink></section></template></main></template>
<style scoped>input{min-height:40px;border:1px solid var(--line);border-radius:var(--radius-sm);padding:6px 10px;background:#fff;color:var(--ink)}.range,.section-card p{color:var(--muted)}.range{font-size:13px}.next :deep(.btn-primary){display:inline-flex;text-decoration:none}</style>
