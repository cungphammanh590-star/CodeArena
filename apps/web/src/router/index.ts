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
      name: "dashboard",
      component: () => import("../views/DashboardView.vue"),
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
