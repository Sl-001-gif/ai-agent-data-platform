<template>
  <div class="admin-container">
    <el-container style="height: 100vh;">
      <el-aside width="220px" style="background: #304156;">
        <h3 style="color: #fff; text-align: center; padding: 20px 0; border-bottom: 1px solid rgba(255,255,255,0.1);">
          管理后台
        </h3>
        <el-menu background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff" :default-active="'1'">
          <el-menu-item index="1">仪表盘</el-menu-item>
          <el-menu-item index="2">用户管理</el-menu-item>
          <el-menu-item index="3">数据集管理</el-menu-item>
          <el-menu-item index="4">AI 模型配置</el-menu-item>
          <el-menu-item index="5">Agent 执行记录</el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header style="background: #fff; border-bottom: 1px solid #e4e7ed; display: flex; justify-content: space-between; align-items: center;">
          <span>仪表盘</span>
          <el-button text @click="handleLogout">退出登录</el-button>
        </el-header>
        <el-main style="background: #f0f2f5;">
          <el-row :gutter="16">
            <el-col :span="6" v-for="item in stats" :key="item.label">
              <el-card>
                <div style="text-align: center;">
                  <div style="font-size: 28px; font-weight: bold; color: #409eff;">{{ item.value }}</div>
                  <div style="font-size: 14px; color: #909399; margin-top: 8px;">{{ item.label }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>
          <el-card style="margin-top: 16px;">
            <template #header><span>管理功能开发中...</span></template>
            <p>用户管理、数据集管理、AI 模型配置等管理功能将在后续版本实现。</p>
          </el-card>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const userStore = useUserStore();

const stats = ref([
  { label: "用户数", value: "—" },
  { label: "数据集", value: "—" },
  { label: "分析会话", value: "—" },
  { label: "分析报告", value: "—" },
]);

function handleLogout() {
  userStore.logout();
  ElMessage.success("已退出");
  router.push("/login");
}
</script>
