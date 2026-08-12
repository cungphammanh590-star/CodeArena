<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import AuthBar from "@/components/AuthBar.vue";
import LearningPrefs from "@/components/LearningPrefs.vue";

const route = useRoute();
const showPrefs = computed(() => route.name === "dashboard");
</script>

<template>
  <header class="app-topbar">
    <RouterLink class="app-brand" to="/">
      CodeArena
      <span>Nex</span>
    </RouterLink>
    <nav class="app-nav" aria-label="主导航">
      <RouterLink to="/">仪表盘</RouterLink>
      <RouterLink to="/coach">Nex</RouterLink>
      <RouterLink to="/ops">维护</RouterLink>
    </nav>
    <div class="app-topbar-actions">
      <LearningPrefs v-if="showPrefs" />
      <AuthBar />
    </div>
  </header>
</template>
