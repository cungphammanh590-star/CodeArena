<script setup lang="ts">
defineProps<{
  selectedDate: string;
  isToday: boolean;
  heading: string;
}>();

const emit = defineEmits<{
  prev: [];
  next: [];
  today: [];
  pick: [date: string];
}>();
</script>

<template>
  <div class="day-nav">
    <span class="day-label">{{ heading }}</span>
    <button type="button" title="上一天" @click="emit('prev')">←</button>
    <input
      type="date"
      :value="selectedDate"
      @change="
        emit(
          'pick',
          ($event.target as HTMLInputElement).value,
        )
      "
    />
    <button
      type="button"
      title="下一天"
      :disabled="isToday"
      @click="emit('next')"
    >
      →
    </button>
    <button type="button" :disabled="isToday" @click="emit('today')">
      回到今天
    </button>
  </div>
</template>

<style scoped>
.day-nav {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 12px 0 4px;
}
.day-nav button {
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--ink);
  border-radius: 8px;
  padding: 6px 12px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.day-nav button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}
.day-nav button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.day-nav input[type="date"] {
  border: 1px solid var(--line);
  background: var(--card);
  border-radius: 8px;
  padding: 6px 10px;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
}
.day-label {
  font-size: 14px;
  font-weight: 600;
  margin-right: 4px;
}
</style>
