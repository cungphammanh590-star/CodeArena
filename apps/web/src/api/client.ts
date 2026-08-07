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
}

/** 请求头：Bearer 优先；兼容旧的 X-User-Public-Id。 */
export function userHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = getAccessToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const uid = getUserPublicId();
  if (uid) headers["X-User-Public-Id"] = uid;
  return headers;
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
  const uid = getUserPublicId();
  if (uid) {
    config.headers["X-User-Public-Id"] = uid;
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

/**
 * 扩展登录后会带 ?ext_token=ca_… 打开 Web；写入 localStorage 并校验 /auth/me。
 */
export async function consumeExtensionTokenFromUrl(): Promise<boolean> {
  try {
    const url = new URL(window.location.href);
    const extToken = url.searchParams.get("ext_token");
    if (!extToken) return Boolean(getAccessToken());

    setAccessToken(extToken);
    url.searchParams.delete("ext_token");
    const clean = `${url.pathname}${url.search}${url.hash}`;
    window.history.replaceState({}, "", clean || "/");

    const { data } = await api.get("/auth/me");
    const user = data?.user;
    if (user?.public_id) {
      setUserPublicId(user.public_id);
    }
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
