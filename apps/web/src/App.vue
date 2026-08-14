<script setup lang="ts">
import { computed } from "vue";
import { RouterView, useRoute } from "vue-router";
import AppTopbar from "@/components/AppTopbar.vue";

const route = useRoute();
const showChrome = computed(() => !["login", "landing", "demo", "onboarding"].includes(String(route.name)));
</script>

<template>
  <div class="app-shell">
    <AppTopbar v-if="showChrome" />
    <RouterView />
  </div>
</template>
