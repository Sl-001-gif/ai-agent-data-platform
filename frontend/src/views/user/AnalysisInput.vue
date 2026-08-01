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
              <el-button type="primary" size="large" :loading="executing" @click="executeCurrentAnalysis" style="width: 200px; margin-left: 12px;">
                执行分析
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

          <el-card v-if="executeResult" shadow="never" style="margin-top: 16px;">
            <template #header>
              <span>执行结果</span>
            </template>

            <el-descriptions :column="2" border style="margin-bottom: 12px;">
              <el-descriptions-item label="意图名称">{{ executeResult.intent.intentName }}</el-descriptions-item>
              <el-descriptions-item label="置信度">{{ (executeResult.intent.confidence * 100).toFixed(0) }}%</el-descriptions-item>
              <el-descriptions-item label="命中关键词">{{ executeResult.intent.matchedKeywords.join("、") }}</el-descriptions-item>
              <el-descriptions-item label="目标表">{{ executeResult.plan.targetTable }}（{{ executeResult.plan.tableComment }}）</el-descriptions-item>
              <el-descriptions-item label="图表类型">{{ executeResult.plan.chartType }}</el-descriptions-item>
              <el-descriptions-item label="指标">{{ executeResult.plan.metrics.join("、") }}</el-descriptions-item>
              <el-descriptions-item label="维度">{{ executeResult.plan.dimensions.join("、") }}</el-descriptions-item>
              <el-descriptions-item label="时间范围">{{ executeResult.plan.timeRange }}</el-descriptions-item>
            </el-descriptions>

            <div style="margin-bottom: 12px;">
              <span style="font-weight: bold;">步骤进度：</span>
              <el-tag
                v-for="(step, index) in executeResult.plan.steps"
                :key="step"
                size="small"
                style="margin-right: 6px;"
                :type="stepType(index)"
              >
                {{ step }}
              </el-tag>
            </div>

            <el-alert
              v-if="executeCode !== 200"
              :title="executeMessage || 'SQL 校验未通过'"
              type="error"
              :closable="false"
              show-icon
            />
            <template v-else>
              <div v-if="showChart" ref="chartRef" style="height: 360px; width: 100%;"></div>
              <el-table v-else :data="tableRows" border stripe size="small">
                <el-table-column
                  v-for="col in executionColumns"
                  :key="col"
                  :prop="col"
                  :label="col"
                  min-width="120"
                />
              </el-table>
            </template>
          </el-card>
          <el-card v-if="executeResult?.interpretation" shadow="never" style="margin-top: 16px;">
            <template #header>
              <span>AI 解读</span>
              <el-tag v-if="executeResult.interpretation.generatorType === 'LLM'" type="success" size="small" style="margin-left: 8px;">LLM</el-tag>
              <el-tag v-else type="info" size="small" style="margin-left: 8px;">规则</el-tag>
            </template>
            <div style="white-space: pre-wrap; line-height: 1.8;">{{ executeResult.interpretation.text }}</div>
          </el-card>

          <el-card v-if="executeResult?.followups?.length" shadow="never" style="margin-top: 16px;">
            <template #header>
              <span>你可能还想问</span>
            </template>
            <el-button
              v-for="(q, index) in executeResult.followups"
              :key="index"
              type="primary"
              plain
              size="small"
              style="margin: 4px 8px 4px 0;"
              @click="askFollowup(q)"
            >
              {{ q }}
            </el-button>
          </el-card>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import * as echarts from "echarts";
import { useUserStore } from "@/stores/user";
import { parseAnalysis, executeAnalysis } from "@/api/analysis";

const router = useRouter();
const userStore = useUserStore();
const analysisGoal = ref("");
const loading = ref(false);
const result = ref(null);

const executeResult = ref(null);
const executeCode = ref(null);
const executeMessage = ref("");
const sessionId = ref(null);
const executing = ref(false);
const chartRef = ref(null);
const chartInstance = ref(null);

const CHART_TYPES = ["line", "bar", "pie"];

const showChart = computed(() => {
  if (executeCode.value !== 200 || !executeResult.value?.execution) {
    return false;
  }
  const columns = executeResult.value.execution.columns || [];
  const rows = executeResult.value.execution.rows || [];
  return (
    CHART_TYPES.includes(executeResult.value.chartType) &&
    columns.length >= 2 &&
    rows.length > 0
  );
});

const executionColumns = computed(() => executeResult.value?.execution?.columns || []);
const tableRows = computed(() => executeResult.value?.execution?.rows || []);

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

async function executeCurrentAnalysis() {
  if (!analysisGoal.value.trim()) {
    ElMessage.warning("请输入分析目标");
    return;
  }
  executing.value = true;
  executeResult.value = null;
  executeCode.value = null;
  try {
    const res = await executeAnalysis({
      text: analysisGoal.value,
      sessionId: sessionId.value,
    });
    executeCode.value = res.code;
    executeMessage.value = res.message || "";
    executeResult.value = res.data;
    if (res.data?.sessionId) {
      sessionId.value = res.data.sessionId;
    }
    await nextTick();
    renderChart();
    if (executeCode.value === 200) {
      ElMessage.success("执行成功");
    } else {
      ElMessage.warning(executeMessage.value || "执行失败");
    }
  } catch (error) {
    // 错误提示已由 request.js 拦截器统一处理
  } finally {
    executing.value = false;
  }
}

function askFollowup(q) {
  analysisGoal.value = q;
  executeCurrentAnalysis();
}

function stepType(index) {
  if (executeCode.value !== 200) {
    if (index < 3) return "success";
    if (index === 3) return "danger";
    return "info";
  }
  return index < 5 || index === 6 ? "success" : "info";
}

function renderChart() {
  if (chartInstance.value) {
    chartInstance.value.dispose();
    chartInstance.value = null;
  }
  if (!showChart.value || !chartRef.value || !executeResult.value?.execution) {
    return;
  }
  const { columns, rows } = executeResult.value.execution;
  const chartType = executeResult.value.chartType;
  const firstKey = columns[0];
  const lastKey = columns[columns.length - 1];

  let option;
  if (chartType === "pie") {
    option = {
      tooltip: { trigger: "item" },
      legend: { bottom: 0 },
      series: [
        {
          name: lastKey,
          type: "pie",
          radius: "60%",
          data: rows.map((row) => ({
            name: String(row[firstKey]),
            value: Number(row[lastKey]),
          })),
        },
      ],
    };
  } else {
    option = {
      tooltip: { trigger: "axis" },
      grid: { left: 40, right: 20, top: 30, bottom: 40 },
      xAxis: { type: "category", data: rows.map((row) => row[firstKey]) },
      yAxis: { type: "value" },
      series: [
        {
          name: lastKey,
          type: chartType,
          data: rows.map((row) => Number(row[lastKey])),
          smooth: chartType === "line",
        },
      ],
    };
  }

  chartInstance.value = echarts.init(chartRef.value);
  chartInstance.value.setOption(option);
}

function handleResize() {
  chartInstance.value?.resize();
}

window.addEventListener("resize", handleResize);

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize);
  chartInstance.value?.dispose();
});
</script>