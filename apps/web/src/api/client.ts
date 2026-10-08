import axios from "axios";

const USER_PUBLIC_ID_KEY = "codearena_user_public_id";
const ACCESS_TOKEN_KEY = "codearena_access_token";
const USER_DISPLAY_NAME_KEY = "codearena_user_display_name";

export function getUserPublicId(): string {
  try {
    return localStorage.getItem(USER_PUBLIC_ID_KEY) || "";
  } catch {
    return "";
  }
}

export function setUserPublicId(id: string) {
  try {
    if (id) localStorage.setItem(USER_PUBLIC_ID_KEY, id);
    else localStorage.removeItem(USER_PUBLIC_ID_KEY);
  } catch {
    /* ignore */
  }
}

export function getAccessToken(): string {
  try {
    return localStorage.getItem(ACCESS_TOKEN_KEY) || "";
  } catch {
    return "";
  }
}

export function getUserDisplayName(): string {
  try {
    return localStorage.getItem(USER_DISPLAY_NAME_KEY) || "";
  } catch {
    return "";
  }
}

function setUserDisplayName(name: string) {
  try {
    if (name) localStorage.setItem(USER_DISPLAY_NAME_KEY, name);
    else localStorage.removeItem(USER_DISPLAY_NAME_KEY);
  } catch {
    /* ignore */
  }
}

function rememberUser(user?: Record<string, unknown> | null) {
  if (!user) return;
  if (typeof user.public_id === "string" && user.public_id) {
    setUserPublicId(user.public_id);
  }
  const name = user.display_name || user.username || user.public_id;
  if (typeof name === "string" && name) setUserDisplayName(name);
}

export function setAccessToken(token: string) {
  try {
    if (token) localStorage.setItem(ACCESS_TOKEN_KEY, token);
    else localStorage.removeItem(ACCESS_TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

export function clearAuth() {
  setAccessToken("");
  setUserPublicId("");
  setUserDisplayName("");
  notifyExtensionAuthClear();
}

/** 请求头：JWT Bearer（Gateway 校验）。 */
export function userHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = getAccessToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

function notifyExtensionAuth(token: string, user?: Record<string, unknown> | null) {
  try {
    window.postMessage(
      {
        source: "codearena",
        type: "auth_sync",
        token,
        user: user || null,
      },
      window.location.origin,
    );
  } catch {
    /* ignore */
  }
}

function notifyExtensionAuthClear() {
  try {
    window.postMessage(
      { source: "codearena", type: "auth_clear" },
      window.location.origin,
    );
  } catch {
    /* ignore */
  }
}

export const api = axios.create({
  baseURL: "/api",
  headers: {
    "Content-Type": "application/json",
  },
});

api.interceptors.request.use((config) => {
  config.headers = config.headers || {};
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use((resp) => {
  const uid =
    resp.data?.user_public_id ||
    resp.data?.config?.user_public_id ||
    resp.data?.user?.public_id;
  if (typeof uid === "string" && uid) {
    setUserPublicId(uid);
  }
  return resp;
}, (error) => {
  if (error?.response?.status === 401 && getAccessToken()) {
    clearAuth();
    window.dispatchEvent(new CustomEvent("codearena:auth-expired"));
  }
  return Promise.reject(error);
});

export async function loginWithPassword(username: string, password: string) {
  const { data } = await api.post("/auth/login", {
    username,
    password,
    client: "web",
  });
  const token = data.access_token as string;
  setAccessToken(token);
  const user = data.user || {};
  rememberUser(user);
  notifyExtensionAuth(token, user);
  return data;
}

export async function registerWithPassword(
  username: string,
  password: string,
  displayName?: string,
) {
  const { data } = await api.post("/auth/register", {
    username,
    password,
    display_name: displayName || username,
  });
  const token = data.access_token as string;
  setAccessToken(token);
  const user = data.user || {};
  rememberUser(user);
  notifyExtensionAuth(token, user);
  return data;
}

export async function logoutRemote() {
  try {
    if (getAccessToken()) {
      await api.post("/auth/logout");
    }
  } catch {
    /* ignore */
  } finally {
    clearAuth();
  }
}

export async function fetchMe() {
  const { data } = await api.get("/auth/me");
  const user = data?.user;
  rememberUser(user);
  return data;
}

/**
 * 扩展登录后会带 ?ext_token=<JWT>；写入 localStorage 并同步回扩展。
 */
export async function consumeExtensionTokenFromUrl(): Promise<boolean> {
  try {
    const url = new URL(window.location.href);
    const extToken = url.searchParams.get("ext_token");
    if (extToken) {
      setAccessToken(extToken);
      url.searchParams.delete("ext_token");
      const clean = `${url.pathname}${url.search}${url.hash}`;
      window.history.replaceState({}, "", clean || "/");
    }

    const token = getAccessToken();
    if (!token) return false;

    const { data } = await api.get("/auth/me");
    const user = data?.user;
    rememberUser(user);
    notifyExtensionAuth(token, user || null);
    return true;
  } catch (error: unknown) {
    const status =
      error && typeof error === "object" && "response" in error
        ? (error as { response?: { status?: number } }).response?.status
        : undefined;
    // 只有服务端明确拒绝凭证时才清会话；网络或服务故障不能反向抹掉扩展登录态。
    if (status === 401 || status === 403) clearAuth();
    return false;
  }
}

export async function fetchHealth() {
  const { data } = await axios.get("/health", { headers: userHeaders() });
  return data;
}

export default api;
