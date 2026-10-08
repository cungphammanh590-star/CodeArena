// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import router from "./index";
import api from "@/api/client";

const values = new Map<string, string>();
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => values.set(key, String(value)),
  removeItem: (key: string) => values.delete(key),
  clear: () => values.clear(),
  key: (index: number) => [...values.keys()][index] ?? null,
  get length() { return values.size; },
};

beforeAll(() => vi.stubGlobal("localStorage", storage));
afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("public and protected route boundary", () => {
  it.each(["/", "/demo", "/login"])("allows public route %s", async (path) => {
    const result = await router.push(path);
    expect(result).toBeUndefined();
    expect(router.currentRoute.value.fullPath).toBe(path);
  });

  it.each(["/dashboard", "/onboarding", "/archive", "/weekly-report"])(
    "redirects anonymous access to login for %s",
    async (path) => {
      await router.push(path);
      expect(router.currentRoute.value.name).toBe("login");
      expect(router.currentRoute.value.query.redirect).toBe(path);
    },
  );

  it("allows an authenticated user to enter the dashboard", async () => {
    localStorage.setItem("codearena_access_token", "test-token");
    vi.spyOn(api, "get").mockResolvedValue({ data: { completed: true } });
    await router.push("/dashboard");
    expect(router.currentRoute.value.name).toBe("dashboard");
  });

  it("does not show the login form again when a session already exists", async () => {
    localStorage.setItem("codearena_access_token", "test-token");
    vi.spyOn(api, "get").mockResolvedValue({ data: { completed: true } });
    await router.push("/login?redirect=/coach");
    expect(router.currentRoute.value.fullPath).toBe("/coach");
  });

  it("sends an authenticated user with incomplete onboarding to onboarding", async () => {
    localStorage.setItem("codearena_access_token", "test-token");
    vi.spyOn(api, "get").mockResolvedValue({ data: { completed: false } });
    await router.push("/dashboard");
    expect(router.currentRoute.value.name).toBe("onboarding");
    expect(router.currentRoute.value.query.redirect).toBe("/dashboard");
  });

  it("does not create an onboarding loop when the status check is temporarily unavailable", async () => {
    localStorage.setItem("codearena_access_token", "test-token");
    vi.spyOn(api, "get").mockRejectedValue(new Error("network unavailable"));
    await router.push("/dashboard");
    expect(router.currentRoute.value.name).toBe("dashboard");
  });
});
