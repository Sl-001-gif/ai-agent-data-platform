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
    component: () => import("@/views/layout/Layout.vue"),
    redirect: "/home",
    meta: { requiresAuth: true },
    children: [
      {
        path: "home",
        name: "Home",
        component: () => import("@/views/home/Home.vue"),
        meta: { title: "系统首页", requiresAuth: true },
      },
      {
        path: "user",
        name: "UserManage",
        component: () => import("@/views/system/UserManage.vue"),
        meta: { title: "用户管理", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "datasource",
        name: "DatasourceManage",
        component: () => import("@/views/admin/DataSource.vue"),
        meta: { title: "数据源管理", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "dataset",
        name: "DatasetManage",
        component: () => import("@/views/admin/Dataset.vue"),
        meta: { title: "数据集管理", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "data-table",
        name: "DataTableManage",
        component: () => import("@/views/admin/DataTable.vue"),
        meta: { title: "数据表管理", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "field-semantic",
        name: "FieldSemanticManage",
        component: () => import("@/views/admin/FieldSemantic.vue"),
        meta: { title: "字段语义管理", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "metric",
        name: "MetricManage",
        component: () => import("@/views/admin/Metric.vue"),
        meta: { title: "指标口径管理", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "analysis-config",
        name: "AnalysisConfig",
        component: () => import("@/views/admin/AnalysisConfig.vue"),
        meta: { title: "分析配置", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "plan-config",
        name: "PlanConfig",
        component: () => import("@/views/admin/AnalysisConfig.vue"),
        meta: { title: "计划配置", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "ai-model",
        name: "AiModelManage",
        component: () => import("@/views/admin/AiModel.vue"),
        meta: { title: "AI 模型配置", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "prompt-template",
        name: "PromptTemplateManage",
        component: () => import("@/views/admin/PromptTemplate.vue"),
        meta: { title: "Prompt 模板管理", requiresAuth: true, role: "ADMIN" },
      },
      {
        path: "chat-session",
        name: "ChatSession",
        component: () => import("@/views/analysis/ChatSession.vue"),
        meta: { title: "多轮分析会话", requiresAuth: true },
      },
      {
        path: "agent-plan",
        name: "AgentPlan",
        component: () => import("@/views/analysis/AgentPlan.vue"),
        meta: { title: "Agent 分析计划", requiresAuth: true },
      },
      {
        path: "agent-track",
        name: "AgentTrack",
        component: () => import("@/views/analysis/AgentTrack.vue"),
        meta: { title: "Agent 执行追踪", requiresAuth: true },
      },
      {
        path: "report",
        name: "ReportHistory",
        component: () => import("@/views/analysis/Report.vue"),
        meta: { title: "分析报告", requiresAuth: true },
      },
    ],
  },
  {
    path: "/ai-analysis",
    name: "AiAnalysis",
    component: () => import("@/views/user/AnalysisInput.vue"),
    meta: { title: "AI 数据分析", requiresAuth: true },
  },
  {
    path: "/analysis",
    name: "Analysis",
    component: () => import("@/views/user/AnalysisInput.vue"),
    meta: { title: "AI 数据分析", requiresAuth: true },
  },
  {
    path: "/profile",
    name: "Profile",
    component: () => import("@/views/user/Profile.vue"),
    meta: { requiresAuth: true },
  },
  {
    path: "/admin",
    redirect: "/home",
    meta: { requiresAuth: true },
  },
  {
    path: "/:pathMatch(.*)*",
    redirect: "/home",
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
    next("/home");
  } else if (to.meta.role && userStore.userInfo?.role !== to.meta.role) {
    next("/home");
  } else {
    next();
  }
});

export default router;