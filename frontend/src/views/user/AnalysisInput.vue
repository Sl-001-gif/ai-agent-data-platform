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
      <el-main style="background: #f5f7fa; display: flex; justify-content: center; padding-top: 24px;">
        <div style="width: 900px;">
          <el-card style="margin-bottom: 16px;">
            <template #header>
              <span>开始数据分析</span>
            </template>
            <el-input
              v-model="analysisGoal"
              type="textarea"
              :rows="5"
              placeholder="请输入您的分析目标，例如：分析最近30天的销售趋势"
            />
            <div style="margin-top: 16px; text-align: center;">
              <el-button type="primary" size="large" :loading="loading" @click="startAnalysis" style="width: 200px;">
                开始分析
              </el-button>
            </div>
          </el-card>

          <el-card v-if="result" shadow="never">
            <template #header>
              <span>识别结果</span>
            </template>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="意图名称">{{ result.intent.intentName }}</el-descriptions-item>
              <el-descriptions-item label="置信度">{{ (result.intent.confidence * 100).toFixed(0) }}%</el-descriptions-item>
              <el-descriptions-item label="意图编码">{{ result.intent.intentType }}</el-descriptions-item>
              <el-descriptions-item label="命中关键词">{{ result.intent.matchedKeywords.join("、") }}</el-descriptions-item>
              <el-descriptions-item label="目标表">{{ result.plan.targetTable }}（{{ result.plan.tableComment }}）</el-descriptions-item>
              <el-descriptions-item label="图表类型">{{ result.plan.chartType }}</el-descriptions-item>
              <el-descriptions-item label="指标">{{ result.plan.metrics.join("、") }}</el-descriptions-item>
              <el-descriptions-item label="维度">{{ result.plan.dimensions.join("、") }}</el-descriptions-item>
              <el-descriptions-item label="时间范围">{{ result.plan.timeRange }}</el-descriptions-item>
            </el-descriptions>
            <div style="margin-top: 12px;">
              <span style="font-weight: bold;">执行步骤：</span>
              <el-tag
                v-for="(step, index) in result.plan.steps"
                :key="step"
                size="small"
                style="margin-right: 6px;"
                :type="index === 0 ? 'primary' : 'info'"
              >
                {{ step }}
              </el-tag>
            </div>
          </el-card>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/stores/user";
import { parseAnalysis } from "@/api/analysis";

const router = useRouter();
const userStore = useUserStore();
const analysisGoal = ref("");
const loading = ref(false);
const result = ref(null);

function handleLogout() {
  userStore.logout();
  ElMessage.success("已退出");
  router.push("/login");
}

async function startAnalysis() {
  if (!analysisGoal.value.trim()) {
    ElMessage.warning("请输入分析目标");
    return;
  }
  loading.value = true;
  try {
    const res = await parseAnalysis({ text: analysisGoal.value });
    result.value = res.data;
    ElMessage.success("解析成功");
  } catch (error) {
    // 错误提示已由 request.js 拦截器统一处理
  } finally {
    loading.value = false;
  }
}
</script>