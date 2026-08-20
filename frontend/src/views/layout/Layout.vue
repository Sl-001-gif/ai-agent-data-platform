<template>
  <div class="app-layout">
    <el-container style="min-height: 100vh;">
      <el-aside width="220px" style="background: #304156;">
        <div style="position: sticky; top: 0; height: 100vh; overflow-y: auto;">
        <h3 style="color: #fff; text-align: center; padding: 20px 0; border-bottom: 1px solid rgba(255,255,255,0.1);">
          AI 数据分析平台
        </h3>
        <el-menu background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff" :default-active="activeMenu" router>
          <el-menu-item index="/home">系统首页</el-menu-item>
          <el-menu-item v-if="userStore.isAdmin" index="/user">用户管理</el-menu-item>
          <el-sub-menu v-if="userStore.isAdmin" index="meta">
            <template #title>数据元配置</template>
            <el-menu-item index="/datasource">数据源管理</el-menu-item>
            <el-menu-item index="/data-browse">数据浏览</el-menu-item>
            <el-menu-item index="/dataset">数据集管理</el-menu-item>
            <el-menu-item index="/data-table">数据表管理</el-menu-item>
            <el-menu-item index="/field-semantic">字段语义管理</el-menu-item>
            <el-menu-item index="/metric">指标口径管理</el-menu-item>
            <el-menu-item index="/stat-category">统计指标分类</el-menu-item>
          </el-sub-menu>
          <el-sub-menu v-if="userStore.isAdmin" index="aiconf">
            <template #title>AI 能力配置</template>
            <el-menu-item index="/analysis-config">分析配置</el-menu-item>
            <el-menu-item index="/ai-model">AI 模型配置</el-menu-item>
            <el-menu-item index="/prompt-template">Prompt 模板管理</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="analysis">
            <template #title>智能分析</template>
            <el-menu-item index="/chat-session">多轮分析会话</el-menu-item>
            <el-menu-item index="/ai-analysis">AI 数据分析</el-menu-item>
            <el-menu-item index="/agent-plan">Agent 分析计划</el-menu-item>
            <el-menu-item index="/agent-track">Agent 执行追踪</el-menu-item>
            <el-menu-item index="/report">分析报告</el-menu-item>
          </el-sub-menu>
        </el-menu>
              </div>
      </el-aside>
      <el-container>
        <el-header style="background: #fff; border-bottom: 1px solid #e4e7ed; display: flex; justify-content: space-between; align-items: center; position: sticky; top: 0; z-index: 10;">
          <span>{{ pageTitle }}</span>
          <el-dropdown @command="handleCommand">
            <span style="cursor: pointer; display: inline-flex; align-items: center; gap: 4px;">
              {{ userStore.userInfo?.nickname || userStore.userInfo?.username || "用户" }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人信息</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-header>
        <el-main style="background: #f0f2f5; overflow: visible;">
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowDown } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/stores/user";

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const activeMenu = computed(() => route.path);
const pageTitle = computed(() => route.meta.title || "AI 数据分析平台");

function handleCommand(command) {
  if (command === "profile") {
    router.push("/profile");
  } else if (command === "logout") {
    userStore.logout();
    ElMessage.success("已退出登录");
    router.push("/login");
  }
}
</script>