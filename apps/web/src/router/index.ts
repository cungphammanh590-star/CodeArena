import { createRouter, createWebHistory } from "vue-router";
import { getAccessToken } from "@/api/client";

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

router.beforeEach((to) => {
  if (to.meta.public) return true;
  if (!getAccessToken()) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  return true;
});

export default router;
