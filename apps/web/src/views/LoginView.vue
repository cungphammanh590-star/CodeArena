<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  loginWithPassword,
  registerWithPassword,
} from "@/api/client";

const router = useRouter();
const route = useRoute();
const username = ref("");
const password = ref("");
const busy = ref(false);
const error = ref("");
const mode = ref<"login" | "register">("login");

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
    if (mode.value === "login") {
      await loginWithPassword(u, p);
    } else {
      await registerWithPassword(u, p);
    }
    const redirect =
      typeof route.query.redirect === "string" ? route.query.redirect : "/";
    await router.replace(redirect || "/");
  } catch (e: unknown) {
    const any = e as {
      response?: { data?: { message?: string }; status?: number };
      message?: string;
    };
    const msg =
      any?.response?.data?.message ||
      (any?.response?.status === 401
        ? "用户名或密码不正确"
        : any?.message) ||
      "登录失败，请稍后再试";
    error.value = String(msg);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <section class="panel">
      <h1>CodeArena</h1>
      <p class="hint">登录后同步力扣提交，并与浏览器扩展共享同一账号。</p>

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
        <button type="button" :disabled="busy" @click="mode = 'login'; submit()">
          {{ busy && mode === "login" ? "登录中…" : "登录" }}
        </button>
        <button
          type="button"
          class="secondary"
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
    radial-gradient(ellipse 80% 60% at 20% 0%, #f3e7e1 0%, transparent 55%),
    radial-gradient(ellipse 70% 50% at 90% 100%, #e8dfd6 0%, transparent 50%),
    #f7f1ef;
  color: #3f3a38;
}
.panel {
  width: min(400px, 100%);
  background: #fffbf9;
  border: 1px solid #eadfd9;
  border-radius: 16px;
  padding: 28px 24px 24px;
  box-shadow: 0 12px 40px rgba(80, 50, 40, 0.06);
}
h1 {
  margin: 0 0 8px;
  font-size: 28px;
  letter-spacing: -0.02em;
}
.hint {
  margin: 0 0 20px;
  color: #948984;
  font-size: 13px;
  line-height: 1.5;
}
label {
  display: block;
  font-size: 12px;
  color: #948984;
  margin-bottom: 12px;
}
input {
  display: block;
  width: 100%;
  margin-top: 6px;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid #eadfd9;
  border-radius: 10px;
  font: inherit;
  background: #fff;
  color: #3f3a38;
}
.actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}
button {
  flex: 1;
  border: 0;
  border-radius: 10px;
  padding: 10px 12px;
  background: #c67a88;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
button.secondary {
  background: #eadfd9;
  color: #3f3a38;
}
button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.error {
  color: #c97878;
  font-size: 13px;
  margin: 0 0 10px;
}
</style>
