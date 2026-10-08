import { createRouter, createWebHistory } from "vue-router";
import api, { getAccessToken } from "@/api/client";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("../views/LoginView.vue"),
      meta: { public: true },
    },
    {
      path: "/",
      name: "landing",
      component: () => import("../views/LandingView.vue"),
      meta: { public: true },
    },
    {
      path: "/demo",
      name: "demo",
      component: () => import("../views/DemoView.vue"),
      meta: { public: true },
    },
    {
      path: "/dashboard",
      name: "dashboard",
      component: () => import("../views/DashboardView.vue"),
    },
    {
      path: "/onboarding",
      name: "onboarding",
      component: () => import("../views/OnboardingView.vue"),
    },
    {
      path: "/problems/:id",
      name: "problem",
      component: () => import("../views/ProblemDetailView.vue"),
      props: true,
    },
    {
      path: "/coach",
      name: "coach",
      component: () => import("../views/CoachView.vue"),
    },
    {
      path: "/knowledge",
      name: "knowledge",
      component: () => import("../views/KnowledgeView.vue"),
    },
    {
      path: "/archive",
      name: "archive",
      component: () => import("../views/LearningArchiveView.vue"),
    },
    {
      path: "/weekly-report",
      name: "weekly-report",
      component: () => import("../views/WeeklyReportView.vue"),
    },
    {
      path: "/ops",
      name: "ops",
      component: () => import("../views/OpsView.vue"),
    },
  ],
});

router.beforeEach(async (to) => {
  if (to.name === "login" && getAccessToken()) {
    const redirect = typeof to.query.redirect === "string" ? to.query.redirect : "/dashboard";
    return redirect === "/login" ? { name: "dashboard" } : redirect;
  }
  if (to.meta.public) return true;
  if (!getAccessToken()) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.name !== "onboarding") {
    try {
      const { data } = await api.get("/onboarding");
      if (!data?.completed) {
        return { name: "onboarding", query: { redirect: to.path } };
      }
    } catch {
      // 401 由响应拦截器统一跳登录；短暂服务故障不应制造 onboarding 循环。
      return true;
    }
  }
  return true;
});

export default router;
