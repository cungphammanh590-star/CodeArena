import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { consumeExtensionTokenFromUrl } from "./api/client";
import "./assets/theme.css";

async function boot() {
  await consumeExtensionTokenFromUrl();
  // 再通知一次扩展，避免 content script 尚未挂上时丢掉首次 postMessage
  try {
    const token = localStorage.getItem("codearena_access_token");
    if (token) {
      window.postMessage(
        {
          source: "codearena",
          type: "auth_sync",
          token,
          user: {
            public_id: localStorage.getItem("codearena_user_public_id") || "",
          },
        },
        window.location.origin,
      );
    }
  } catch {
    /* ignore */
  }
  const app = createApp(App);
  app.use(createPinia());
  app.use(router);
  app.mount("#app");
}

void boot();
