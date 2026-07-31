import { createRouter, createWebHistory } from "vue-router";
import { useUserStore } from "@/stores/user";

const routes = [
  {
    path: "/login",
    name: "Login",
    component: () => import("@/views/user/Login.vue"),
    meta: { requiresAuth: false },
  },
  {
    path: "/register",
    name: "Register",
    component: () => import("@/views/user/Register.vue"),
    meta: { requiresAuth: false },
  },
  {
    path: "/",
    redirect: "/analysis",
  },
  {
    path: "/analysis",
    name: "Analysis",
    component: () => import("@/views/user/AnalysisInput.vue"),
    meta: { requiresAuth: true },
  },
  {
    path: "/profile",
    name: "Profile",
    component: () => import("@/views/user/Profile.vue"),
    meta: { requiresAuth: true },
  },
  {
    path: "/admin",
    name: "Admin",
    component: () => import("@/views/admin/Dashboard.vue"),
    meta: { requiresAuth: true, role: "ADMIN" },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to, from, next) => {
  const userStore = useUserStore();

  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    next("/login");
  } else if (to.path === "/login" && userStore.isLoggedIn) {
    next("/analysis");
  } else if (to.meta.role && userStore.userInfo?.role !== to.meta.role) {
    next("/analysis");
  } else {
    next();
  }
});

export default router;
