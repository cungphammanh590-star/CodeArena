import axios from "axios";

const USER_PUBLIC_ID_KEY = "codearena_user_public_id";
const ACCESS_TOKEN_KEY = "codearena_access_token";

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
  if (user.public_id) setUserPublicId(user.public_id);
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
  if (user.public_id) setUserPublicId(user.public_id);
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
  if (user?.public_id) setUserPublicId(user.public_id);
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
    if (user?.public_id) setUserPublicId(user.public_id);
    notifyExtensionAuth(token, user || null);
    return true;
  } catch {
    clearAuth();
    return false;
  }
}

export async function fetchHealth() {
  const { data } = await axios.get("/health", { headers: userHeaders() });
  return data;
}

export default api;
