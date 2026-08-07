<script setup lang="ts">
defineProps<{
  page: number;
  totalPages: number;
  start: number;
  end: number;
  total: number;
}>();

const emit = defineEmits<{
  change: [page: number];
}>();
</script>

<template>
  <div v-if="total" class="pager">
    <span class="meta">{{ start }}–{{ end }} / {{ total }}</span>
    <button
      type="button"
      :disabled="page <= 1"
      @click="emit('change', page - 1)"
    >
      上一页
    </button>
    <span>{{ page }} / {{ totalPages }}</span>
    <button
      type="button"
      :disabled="page >= totalPages"
      @click="emit('change', page + 1)"
    >
      下一页
    </button>
  </div>
</template>

<style scoped>
.pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--muted);
}
.pager .meta {
  margin-right: auto;
}
.pager button {
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--ink);
  border-radius: 8px;
  padding: 6px 12px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.pager button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}
.pager button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
