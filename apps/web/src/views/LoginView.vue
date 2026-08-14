<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  loginWithPassword,
  registerWithPassword,
} from "@/api/client";
import { toUserMessage } from "@/utils/userMessage";

const router = useRouter();
const route = useRoute();
const username = ref("");
const password = ref("");
const busy = ref(false);
const error = ref("");
const mode = ref<"login" | "register">(
  route.query.mode === "register" ? "register" : "login",
);

async function submit() {
  error.value = "";
  const u = username.value.trim();
  const p = password.value;
  if (!u || !p) {
    error.value = "请填写用户名和密码";
    return;
  }
  busy.value = true;
  try {
    const response = mode.value === "login"
      ? await loginWithPassword(u, p)
      : await registerWithPassword(u, p);
    const completed = Boolean(response?.user?.profile?.onboarding_completed);
    const redirect =
      typeof route.query.redirect === "string" ? route.query.redirect : "/dashboard";
    await router.replace(completed ? (redirect || "/dashboard") : "/onboarding");
  } catch (e: unknown) {
    const status =
      e && typeof e === "object" && "response" in e
        ? (e as { response?: { status?: number } }).response?.status
        : undefined;
    if (status === 401) {
      error.value = "用户名或密码不正确";
    } else {
      error.value = toUserMessage(e, "登录失败，请稍后再试");
    }
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="panel">
      <RouterLink class="brand" to="/">CodeArena</RouterLink>
      <h1>{{ mode === "login" ? "登录" : "注册" }}</h1>
      <p class="hint">建立你的代码与知识学习空间，登录后即可开始。</p>

      <label>
        用户名
        <input v-model="username" autocomplete="username" @keydown.enter="submit" />
      </label>
      <label>
        密码
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          @keydown.enter="submit"
        />
      </label>

      <p v-if="error" class="error">{{ error }}</p>

      <div class="actions">
        <button
          type="button"
          class="btn-primary"
          :disabled="busy"
          @click="mode = 'login'; submit()"
        >
          {{ busy && mode === "login" ? "登录中…" : "登录" }}
        </button>
        <button
          type="button"
          class="btn-secondary"
          :disabled="busy"
          @click="mode = 'register'; submit()"
        >
          {{ busy && mode === "register" ? "注册中…" : "注册" }}
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(ellipse 70% 50% at 15% 0%, #d1fae5 0%, transparent 55%),
    radial-gradient(ellipse 60% 40% at 90% 100%, #e0f2fe 0%, transparent 50%),
    var(--bg);
}
.panel {
  width: min(400px, 100%);
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 28px 24px 24px;
  box-shadow: var(--shadow);
}
.brand {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  letter-spacing: 0.02em;
}
h1 {
  margin: 0 0 8px;
  font-size: 26px;
  letter-spacing: -0.03em;
  font-weight: 650;
}
.hint {
  margin: 0 0 20px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}
label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink);
  margin-bottom: 14px;
}
input {
  display: block;
  width: 100%;
  margin-top: 6px;
  box-sizing: border-box;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  font: inherit;
  background: #fff;
  color: var(--ink);
  min-height: 44px;
}
input:focus {
  outline: 2px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-color: var(--accent);
}
.actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}
.actions .btn-primary,
.actions .btn-secondary {
  flex: 1;
}
.error {
  color: var(--danger);
  font-size: 13px;
  margin: 0 0 10px;
}
</style>
