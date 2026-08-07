<script setup lang="ts">
defineProps<{
  label: string;
  value: string | number;
}>();
</script>

<template>
  <div class="metric-card">
    <div class="label">{{ label }}</div>
    <div class="value">{{ value }}</div>
  </div>
</template>
