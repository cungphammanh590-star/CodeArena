<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from "vue";
import { RouterView, useRoute, useRouter } from "vue-router";
import AppTopbar from "@/components/AppTopbar.vue";

const route = useRoute();
const router = useRouter();
const showChrome = computed(() => !["login", "landing", "demo", "onboarding"].includes(String(route.name)));

function onAuthExpired() {
  if (route.name === "login") return;
  void router.replace({ name: "login", query: { redirect: route.fullPath } });
}

onMounted(() => window.addEventListener("codearena:auth-expired", onAuthExpired));
onBeforeUnmount(() => window.removeEventListener("codearena:auth-expired", onAuthExpired));
</script>

<template>
  <div class="app-shell">
    <AppTopbar v-if="showChrome" />
    <RouterView />
  </div>
</template>
