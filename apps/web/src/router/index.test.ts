// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import router from "./index";

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
afterEach(() => localStorage.clear());

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
    await router.push("/dashboard");
    expect(router.currentRoute.value.name).toBe("dashboard");
  });
});
