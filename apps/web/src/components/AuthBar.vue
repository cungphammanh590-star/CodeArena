<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  fetchMe,
  getAccessToken,
  logoutRemote,
} from "@/api/client";

const router = useRouter();
const label = ref("…");
const busy = ref(false);

async function load() {
  if (!getAccessToken()) {
    label.value = "未登录";
    return;
  }
  try {
    const data = await fetchMe();
    const u = data?.user || {};
    label.value = u.display_name || u.username || u.public_id || "已登录";
  } catch {
    label.value = "登录失效";
  }
}

async function onLogout() {
  if (busy.value) return;
  busy.value = true;
  try {
    await logoutRemote();
    await router.replace({ name: "login" });
  } finally {
    busy.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="auth-bar">
    <span class="who" :title="label">{{ label }}</span>
    <button type="button" class="link btn" :disabled="busy" @click="onLogout">
      退出
    </button>
  </div>
</template>

<style scoped>
.auth-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.who {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink);
  font-weight: 600;
}
.link {
  color: var(--muted);
  text-decoration: none;
  background: none;
  border: 0;
  padding: 6px 0;
  font: inherit;
  font-weight: 500;
  cursor: pointer;
  min-height: 40px;
}
.link:hover {
  color: var(--accent);
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
