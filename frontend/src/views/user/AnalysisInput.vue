<template>
  <div class="analysis-container">
    <el-container style="height: 100vh;">
      <el-header style="background: #fff; border-bottom: 1px solid #e4e7ed;">
        <div style="display: flex; justify-content: space-between; align-items: center; height: 60px;">
          <h2 style="font-size: 18px;">AI Agent 数据分析平台</h2>
          <div>
            <el-button text @click="router.push('/profile')">个人信息</el-button>
            <el-button text @click="handleLogout">退出登录</el-button>
          </div>
        </div>
      </el-header>
      <el-main style="background: #f5f7fa; display: flex; justify-content: center; align-items: center;">
        <el-card style="width: 800px;">
          <template #header>
            <span>开始数据分析</span>
          </template>
          <el-input
            v-model="analysisGoal"
            type="textarea"
            :rows="6"
            placeholder="请输入您的分析目标，例如：&#10;1. 分析近30天的用户增长趋势&#10;2. 对比各产品线的销售占比&#10;3. 找出订单量下降的原因"
          />
          <div style="margin-top: 16px; text-align: center;">
            <el-button type="primary" size="large" @click="startAnalysis" style="width: 200px;">
              开始分析
            </el-button>
          </div>
        </el-card>
      </el-main>
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
const analysisGoal = ref("");

function handleLogout() {
  userStore.logout();
  ElMessage.success("已退出");
  router.push("/login");
}

function startAnalysis() {
  if (!analysisGoal.value.trim()) {
    ElMessage.warning("请输入分析目标");
    return;
  }
  ElMessage.info("分析功能开发中，敬请期待");
}
</script>
