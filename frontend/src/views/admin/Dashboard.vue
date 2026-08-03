<template>
  <div class="admin-container">
    <el-container style="height: 100vh;">
      <el-aside width="220px" style="background: #304156;">
        <h3 style="color: #fff; text-align: center; padding: 20px 0; border-bottom: 1px solid rgba(255,255,255,0.1);">
          管理后台
        </h3>
        <el-menu background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff" :default-active="activeMenu" router>
          <el-menu-item index="/admin/datasource">数据源管理</el-menu-item>
          <el-sub-menu index="meta">
            <template #title>数据元配置</template>
            <el-menu-item index="/admin/dataset">数据集管理</el-menu-item>
            <el-menu-item index="/admin/data-table">数据表管理</el-menu-item>
            <el-menu-item index="/admin/field-semantic">字段语义管理</el-menu-item>
            <el-menu-item index="/admin/metric">指标口径管理</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="analysis">
            <template #title>分析配置</template>
            <el-menu-item index="/admin/analysis-config">意图规则</el-menu-item>
            <el-menu-item index="/admin/plan-config">计划配置</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="ai">
            <template #title>AI 配置</template>
            <el-menu-item index="/admin/ai-model">AI 模型配置</el-menu-item>
            <el-menu-item index="/admin/prompt-template">Prompt 模板</el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header style="background: #fff; border-bottom: 1px solid #e4e7ed; display: flex; justify-content: space-between; align-items: center;">
          <span>{{ pageTitle }}</span>
          <div>
            <el-button text @click="router.push('/analysis')">返回分析</el-button>
            <el-button text @click="handleLogout">退出登录</el-button>
          </div>
        </el-header>
        <el-main style="background: #f0f2f5;">
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/stores/user";

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const activeMenu = computed(() => route.path);
const pageTitle = computed(() => route.meta.title || "管理后台");

function handleLogout() {
  userStore.logout();
  ElMessage.success("已退出登录");
  router.push("/login");
}
</script>

<style scoped>
.el-aside .el-menu {
  border-right: none;
}
</style>
