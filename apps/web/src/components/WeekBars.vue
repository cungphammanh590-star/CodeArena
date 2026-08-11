<script setup lang="ts">
defineProps<{
  bars: { date: string; submissions: number }[];
  selectedDate: string;
}>();

const emit = defineEmits<{
  select: [date: string];
}>();

function barHeight(
  submissions: number,
  bars: { date: string; submissions: number }[],
) {
  const max = Math.max(1, ...bars.map((d) => d.submissions));
  return Math.max(4, Math.round((submissions / max) * 80));
}
</script>

<template>
  <div class="bars">
    <div
      v-for="d in bars"
      :key="d.date"
      class="bar"
      :class="{ active: d.date === selectedDate }"
      :style="{ height: barHeight(d.submissions, bars) + 'px' }"
      :title="`${d.date} · 提交 ${d.submissions}`"
      @click="emit('select', d.date)"
    >
      <span>{{ d.date.slice(5) }}</span>
    </div>
  </div>
</template>

<style scoped>
.bars {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  height: 96px;
  margin-bottom: 4px;
  padding: 8px 4px 22px;
  border-radius: 12px;
  background: var(--soft);
}
.bar {
  flex: 1;
  background: #b7c4d4;
  border-radius: 7px 7px 3px 3px;
  min-height: 4px;
  position: relative;
  cursor: pointer;
  transition:
    background 0.15s ease,
    transform 0.15s ease;
}
.bar:hover {
  background: #8fa0b5;
  transform: translateY(-1px);
}
.bar.active {
  background: var(--accent);
}
.bar.active span {
  color: var(--ink);
  font-weight: 600;
}
.bar span {
  position: absolute;
  bottom: -20px;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 10px;
  color: var(--muted);
}
</style>
