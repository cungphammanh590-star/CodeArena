<script setup lang="ts">
import { computed } from "vue";
import { renderMarkdown } from "@/utils/markdown";

const props = withDefaults(
  defineProps<{
    source: string;
    /** When false, escape as plain text (still via markdown pipeline for consistency). */
    markdown?: boolean;
  }>(),
  { markdown: true },
);

const html = computed(() => {
  if (!props.markdown) {
    const esc = String(props.source || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
    return esc.replace(/\n/g, "<br>");
  }
  return renderMarkdown(props.source);
});
</script>

<template>
  <div class="md" v-html="html" />
</template>

<style scoped>
.md {
  line-height: 1.55;
  word-break: break-word;
}
.md :deep(p) {
  margin: 0 0 0.55em;
}
.md :deep(p:last-child) {
  margin-bottom: 0;
}
.md :deep(ul),
.md :deep(ol) {
  margin: 0.35em 0 0.55em;
  padding-left: 1.35em;
}
.md :deep(li) {
  margin: 0.15em 0;
}
.md :deep(li > p) {
  margin: 0;
}
.md :deep(strong) {
  font-weight: 650;
}
.md :deep(em) {
  font-style: italic;
}
.md :deep(a) {
  color: inherit;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.md :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
  background: rgb(0 0 0 / 6%);
  border-radius: 4px;
  padding: 0.1em 0.35em;
}
.md :deep(pre) {
  margin: 0.5em 0;
  padding: 8px 10px;
  overflow-x: auto;
  border-radius: 8px;
  background: rgb(0 0 0 / 7%);
  font-size: 12.5px;
  line-height: 1.45;
}
.md :deep(pre code) {
  background: transparent;
  padding: 0;
  border-radius: 0;
  font-size: inherit;
}
.md :deep(blockquote) {
  margin: 0.45em 0;
  padding: 0.15em 0 0.15em 0.75em;
  border-left: 3px solid rgb(0 0 0 / 15%);
  opacity: 0.92;
}
.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4) {
  margin: 0.55em 0 0.35em;
  font-size: 1.05em;
  font-weight: 650;
  line-height: 1.35;
}
.md :deep(h1:first-child),
.md :deep(h2:first-child),
.md :deep(h3:first-child),
.md :deep(h4:first-child) {
  margin-top: 0;
}
.md :deep(hr) {
  border: none;
  border-top: 1px solid rgb(0 0 0 / 12%);
  margin: 0.7em 0;
}
.md :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.5em 0;
  font-size: 0.92em;
}
.md :deep(th),
.md :deep(td) {
  border: 1px solid rgb(0 0 0 / 12%);
  padding: 4px 8px;
  text-align: left;
}
</style>
