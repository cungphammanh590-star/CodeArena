import { createRouter, createWebHistory } from "vue-router";

const router = createRouter({
  history: createWebHistory(),
  routes: [
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

export default router;
