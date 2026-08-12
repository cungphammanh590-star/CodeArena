<script setup lang="ts">
defineProps<{
  title: string;
  subtitle?: string;
  narrow?: boolean;
  ops?: boolean;
}>();
</script>

<template>
  <header
    class="page-header"
    :class="{ narrow: narrow, ops: ops }"
  >
    <div class="header-main">
      <slot name="before" />
      <h1>{{ title }}</h1>
      <div v-if="subtitle || $slots.subtitle" class="sub">
        <slot name="subtitle">{{ subtitle }}</slot>
      </div>
      <slot name="after-title" />
    </div>
    <div v-if="$slots.actions" class="header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.header-main {
  min-width: 0;
  flex: 1;
}
.header-actions {
  position: relative;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 2px;
}
</style>
