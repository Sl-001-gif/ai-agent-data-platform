<template>
  <div class="analysis-container">
    <el-container style="height: 100vh;">
      <el-header style="background: #fff; border-bottom: 1px solid #e4e7ed;">
        <div style="display: flex; justify-content: space-between; align-items: center; height: 60px;">
          <h2 style="font-size: 18px;">AI Agent 数据分析平台</h2>
          <div>
            <el-button text @click="router.push('/chat-session')">多轮分析会话</el-button>
            <el-button text @click="router.push('/profile')">个人信息</el-button>
            <el-button v-if="userStore.isAdmin" text type="primary" @click="router.push('/admin')">管理后台</el-button>
            <el-button text @click="handleLogout">退出登录</el-button>
          </div>
        </div>
      </el-header>
      <el-main style="background: #f5f7fa; display: flex; justify-content: center; padding-top: 24px;">
        <div style="width: 960px;">

          <!-- 顶部：输入与配置 -->
          <el-card style="margin-bottom: 16px;">
            <template #header>
              <span>输入与配置</span>
            </template>
            <div style="display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap;">
              <el-select v-model="selectedSessionId" placeholder="会话（不选则新建）" clearable size="small" style="width: 240px;" @change="onSessionChange">
                <el-option v-for="s in sessionOptions" :key="s.id" :label="s.title" :value="s.id" />
              </el-select>
              <el-select v-model="selectedDatasetId" placeholder="数据集（全库路由）" clearable size="small" style="width: 180px;">
                <el-option v-for="d in datasetOptions" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
              <el-select v-model="selectedModelId" placeholder="模型（自动按用途路由）" clearable size="small" style="width: 200px;">
                <el-option v-for="m in modelOptions" :key="m.id" :label="m.name + '（' + m.modelName + '）'" :value="m.id" />
              </el-select>
            </div>
            <el-input
              v-model="analysisGoal"
              type="textarea"
              :rows="4"
              placeholder="请输入本次要分析的问题，例如：分析邵阳最近3年GDP变化（同一会话内不同问题会追加为新的一轮）"
            />
            <div v-if="!selectedSessionId" style="display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap;">
              <el-input v-model="sessionTitle" placeholder="会话标题（可选，新建会话时生效，不填自动命名）" size="small" style="width: 260px;" />
              <el-input v-model="sessionGoal" placeholder="多轮分析目标（可选，整个会话的背景目标，如：理解邵阳市发展状况）" size="small" style="width: 360px;" />
            </div>
            <div style="margin-top: 16px; text-align: center;">
              <el-button type="primary" size="large" :loading="loading" @click="recognizeIntent" style="width: 160px;">
                识别意图
              </el-button>
              <el-button type="success" size="large" :loading="sqlLoading" @click="generateSql" style="width: 160px; margin-left: 12px;">
                生成SQL
              </el-button>
              <el-button type="warning" size="large" :loading="executing" @click="executeCurrentAnalysis" style="width: 160px; margin-left: 12px;">
                执行SQL
              </el-button>
            </div>
          </el-card>

          <!-- 分析结果概览 -->
          <el-card v-if="overviewVisible" shadow="never" style="margin-bottom: 16px;">
            <template #header>
              <span>分析结果概览</span>
              <el-tag v-if="executeResult?.roundNo" size="small" type="warning" effect="plain" style="margin-left: 8px;">第 {{ executeResult.roundNo }} 轮</el-tag>
              <el-tag v-if="executeResult?.sessionId" size="small" style="margin-left: 8px;">会话 #{{ executeResult.sessionId }}</el-tag>
            </template>
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="分析意图">{{ overview.intentName }}</el-descriptions-item>
              <el-descriptions-item label="指标">{{ overview.metrics }}</el-descriptions-item>
              <el-descriptions-item label="维度">{{ overview.dimensions }}</el-descriptions-item>
              <el-descriptions-item label="时间范围">{{ overview.timeRange }}</el-descriptions-item>
              <el-descriptions-item label="置信度">{{ overview.confidence }}</el-descriptions-item>
              <el-descriptions-item label="是否需要SQL">{{ overview.needSql }}</el-descriptions-item>
              <el-descriptions-item label="需要图表">{{ overview.needChart }}</el-descriptions-item>
              <el-descriptions-item label="安全状态">
                <el-tag size="small" :type="overview.safe ? 'success' : 'danger'">{{ overview.safe ? "通过" : "未通过" }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="结果条数">{{ overview.rowCount }}</el-descriptions-item>
              <el-descriptions-item label="执行耗时">{{ overview.costMs }} ms</el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 数据质量提示 -->
          <el-alert
            v-if="dataWarnings.length"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 16px;"
            :title="'数据质量提示：' + dataWarnings.join('；')"
          />

          <!-- 分析结果详细 Tab -->
          <el-card v-if="detailVisible" shadow="never" style="margin-bottom: 16px;">
            <template #header>
              <span>分析结果详情</span>
            </template>
            <el-tabs v-model="activeTab">
              <el-tab-pane label="SQL" name="sql">
                <div style="background: #f5f7fa; padding: 12px; border-radius: 4px;">
                  <pre style="margin: 0; white-space: pre-wrap; word-break: break-all; font-size: 12px; line-height: 1.6;">{{ currentSql || "尚未生成 SQL" }}</pre>
                </div>
                <div v-if="sqlValidation" style="margin-top: 10px;">
                  <span style="font-weight: 600;">安全校验：</span>
                  <el-tag size="small" :type="sqlValidation.valid ? 'success' : 'danger'">{{ sqlValidation.valid ? "通过" : "未通过" }}</el-tag>
                  <div v-if="!sqlValidation.valid && sqlValidation.errors" style="color: #f56c6c; font-size: 12px; margin-top: 4px;">{{ sqlValidation.errors.join("；") }}</div>
                </div>
                <div v-if="sqlExplanation" style="margin-top: 10px; font-size: 13px; line-height: 1.7;">
                  <span style="font-weight: 600;">SQL 说明：</span>{{ sqlExplanation }}
                </div>
              </el-tab-pane>
              <el-tab-pane label="查询结果" name="result">
                <el-table :data="tableRows" border stripe size="small">
                  <el-table-column v-for="col in executionColumns" :key="col" :prop="col" :label="col" min-width="120" />
                </el-table>
                <el-empty v-if="!tableRows.length" description="暂无查询结果" />
              </el-tab-pane>
              <el-tab-pane label="推荐图表" name="chart">
                <div v-if="showChart" ref="chartRef" style="height: 440px; width: 100%;"></div>
                <el-empty v-else description="当前结果不适合图表展示（表格视图）" />
              </el-tab-pane>
              <el-tab-pane label="数据解读" name="interpret">
                <div v-if="executeResult?.interpretation" style="white-space: pre-wrap; line-height: 1.8;">
                  {{ executeResult.interpretation.text }}
                </div>
                <div v-else style="color: #909399;">执行后自动生成解读，或查看历史记录中的「生成解读」</div>
                <template v-if="executeResult?.followups?.length">
                  <div style="margin-top: 14px; font-weight: 600; color: #606266;">推荐追问：</div>
                  <el-button
                    v-for="(q, index) in executeResult.followups"
                    :key="index"
                    type="primary"
                    plain
                    size="small"
                    style="margin: 6px 8px 0 0;"
                    @click="askFollowup(q)"
                  >
                    {{ q }}
                  </el-button>
                </template>
              </el-tab-pane>
              <el-tab-pane label="报告" name="report">
                <template v-if="reportGenerating">
                  <div style="color: #909399; padding: 20px 0;">报告生成中，请稍候…</div>
                </template>
                <template v-else-if="currentReport">
                  <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap;">
                    <span style="font-weight: 600;">{{ currentReport.title }}</span>
                    <el-tag size="small">{{ currentReport.generatorType }}</el-tag>
                    <el-button size="small" @click="copyReport">复制</el-button>
                    <el-button size="small" @click="downloadReport">下载 .md</el-button>
                    <el-button size="small" type="primary" plain @click="downloadReportHtml">下载 HTML（含图表）</el-button>
                    <el-button size="small" type="primary" plain @click="generateCurrentReport">重新生成</el-button>
                  </div>
                  <pre style="white-space: pre-wrap; word-break: break-all; background: #f5f7fa; padding: 12px; border-radius: 4px; font-size: 13px; line-height: 1.8;">{{ currentReport.content }}</pre>
                </template>
                <el-empty v-else description="执行分析后点击下方按钮生成报告" :image-size="60">
                  <el-button type="primary" size="small" :loading="reportGenerating" :disabled="!canGenerateReport" @click="generateCurrentReport">生成报告</el-button>
                </el-empty>
              </el-tab-pane>
            </el-tabs>
          </el-card>

          <!-- 底部：历史分析记录区 -->
          <el-card v-if="histories.length" shadow="never">
            <template #header>
              <span>历史分析记录</span>
              <el-button size="small" text type="primary" @click="router.push('/chat-session')">管理会话</el-button>
            </template>
            <el-table :data="histories" size="small" border stripe>
              <el-table-column prop="title" label="会话标题" min-width="150" show-overflow-tooltip />
              <el-table-column label="分析目标" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.analysisGoal || row.title }}</template>
              </el-table-column>
              <el-table-column label="数据表" width="100">
                <template #default="{ row }">{{ datasetName(row.datasetId) }}</template>
              </el-table-column>
              <el-table-column prop="createTime" label="创建时间" width="140" />
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ statusName(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="300">
                <template #default="{ row }">
                  <el-button size="small" type="primary" link @click="rerunHistory(row)">重新执行</el-button>
                  <el-button size="small" type="warning" link @click="correctHistory(row)">纠错</el-button>
                  <el-button size="small" link @click="viewHistoryChart(row)">查看图表</el-button>
                  <el-button size="small" link @click="viewHistoryInterpret(row)">生成解读</el-button>
                  <el-button size="small" type="danger" link @click="deleteHistory(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </div>
      </el-main>
    </el-container>

    <!-- 历史图表/解读弹窗 -->
    <el-dialog v-model="historyDialog" title="历史分析" width="720px" top="5vh">
      <template v-if="historyDialogType === 'chart'">
        <div ref="historyChartRef" style="height: 420px; width: 100%;"></div>
        <div v-if="historyDialogError" style="color: #f56c6c; font-size: 12px;">{{ historyDialogError }}</div>
      </template>
      <template v-else>
        <div v-if="historyInterpretText" style="white-space: pre-wrap; line-height: 1.8;">{{ historyInterpretText }}</div>
        <el-empty v-else description="该会话暂无解读" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import * as echarts from "echarts";
import { useUserStore } from "@/stores/user";
import { parseAnalysis, sqlAnalysis, executeAnalysis, generateReport } from "@/api/analysis";
import { listSessions, listSessionSteps, deleteSession, listDatasetOptions, listModelOptions } from "@/api/history";
import { buildChartOption, pickChartColumns } from "@/utils/chartOption";

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const analysisGoal = ref("");
const sessionTitle = ref("");
const sessionGoal = ref("");
const loading = ref(false);
const sqlLoading = ref(false);
const executing = ref(false);

const intentResult = ref(null);      // 识别意图结果（parse）
const sqlResult = ref(null);         // 生成 SQL 结果（sql）
const executeResult = ref(null);     // 执行结果（execute）
const executeCostMs = ref(0);
const reportGenerating = ref(false);
const currentReport = ref(null);

const sessionOptions = ref([]);
const datasetOptions = ref([]);
const modelOptions = ref([]);
const selectedSessionId = ref(null);
const selectedDatasetId = ref(null);
const selectedModelId = ref(null);
const histories = ref([]);

const activeTab = ref("sql");
const chartRef = ref(null);
const chartInstance = ref(null);
const historyChartRef = ref(null);
const historyChartInstance = ref(null);
const historyDialog = ref(false);
const historyDialogType = ref("chart");
const historyDialogError = ref("");
const historyInterpretText = ref("");

const CHART_TYPES = ["line", "bar", "pie"];

const overviewVisible = computed(() => !!(intentResult.value || sqlResult.value || executeResult.value));
const detailVisible = computed(() => !!(sqlResult.value || executeResult.value));

const currentSql = computed(() => {
  if (executeResult.value?.sql) return executeResult.value.sql;
  if (sqlResult.value?.sql) return sqlResult.value.sql;
  return "";
});
const sqlExplanation = computed(() => (executeResult.value?.sqlExplanation) || (sqlResult.value?.sqlExplanation) || "");
const dataWarnings = computed(() => executeResult.value?.dataWarnings || []);
const sqlValidation = computed(() => {
  if (executeResult.value?.validation) return executeResult.value.validation;
  if (sqlResult.value?.validation) return sqlResult.value.validation;
  return null;
});

const overview = computed(() => {
  const src = executeResult.value || sqlResult.value || intentResult.value || {};
  const plan = src.plan || {};
  const intent = src.intent || {};
  const execution = src.execution || {};
  const needSql = plan.steps && plan.steps.indexOf("SQL") >= 0 ? "是" : "否";
  const needChart = plan.chartType && plan.chartType !== "table" ? "是（" + plan.chartType + "）" : "否";
  const safe = !src.validation ? "未校验" : (src.validation.valid ? "通过" : "未通过");
  const rowCount = execution.rowCount != null ? execution.rowCount : "-";
  return {
    intentName: intent.intentName || "-",
    metrics: (plan.metrics || []).map((m) => (/增速/.test(m) ? m + "（%）" : m)).join("、") || "-",
    dimensions: (plan.dimensions || []).join("、") || "-",
    timeRange: plan.timeRange || "-",
    confidence: intent.confidence != null ? Math.round(intent.confidence * 100) + "%" : "-",
    needSql,
    needChart,
    safe,
    rowCount,
    costMs: executeCostMs.value || "-",
  };
});

const canGenerateReport = computed(() => !!(executeResult.value?.sessionId || selectedSessionId.value) && !!executeResult.value?.roundNo);

const showChart = computed(() => {
  if (!executeResult.value?.execution) return false;
  const columns = executeResult.value.execution.columns || [];
  const rows = executeResult.value.execution.rows || [];
  if (CHART_TYPES.indexOf(executeResult.value.chartType) < 0) return false;
  const pick = pickChartColumns(columns, rows);
  return !!pick.nameKey && !!pick.valueKey && rows.length > 0;
});
const executionColumns = computed(() => executeResult.value?.execution?.columns || []);
const tableRows = computed(() => executeResult.value?.execution?.rows || []);

function datasetName(id) {
  if (!id) return "全库";
  const d = datasetOptions.value.find((x) => x.id === id);
  return d ? d.name : ("数据表#" + id);
}
function statusName(status) {
  return status === "ACTIVE" ? "进行中" : (status === "ARCHIVED" ? "已完成" : (status || "-"));
}

function handleLogout() {
  userStore.logout();
  ElMessage.success("已退出");
  router.push("/login");
}

async function loadOptions() {
  const [sessionsRes, datasetsRes, modelsRes] = await Promise.allSettled([
    listSessions(undefined, undefined, 1, 1000),
    listDatasetOptions(),
    listModelOptions(),
  ]);
  if (sessionsRes.status === "fulfilled" && sessionsRes.value?.code === 200) {
    const sessionRows = sessionsRes.value.data?.rows || [];
    sessionOptions.value = sessionRows;
    histories.value = sessionRows.slice(0, 10);
  }
  if (datasetsRes.status === "fulfilled" && datasetsRes.value?.code === 200) {
    datasetOptions.value = datasetsRes.value.data || [];
  }
  if (modelsRes.status === "fulfilled" && modelsRes.value?.code === 200) {
    modelOptions.value = modelsRes.value.data || [];
  }
}

function onSessionChange(id) {
  if (!id) return;
  const s = sessionOptions.value.find((x) => x.id === id);
  if (s && s.analysisGoal) sessionGoal.value = s.analysisGoal;
}

async function recognizeIntent() {
  if (!analysisGoal.value.trim()) {
    ElMessage.warning("请输入分析问题");
    return;
  }
  loading.value = true;
  try {
    const res = await parseAnalysis({ text: analysisGoal.value, sessionId: selectedSessionId.value || undefined, datasetId: selectedDatasetId.value || undefined, modelConfigId: selectedModelId.value || undefined, title: sessionTitle.value || undefined, analysisGoal: sessionGoal.value || undefined });
    intentResult.value = res.data;
    if (res.data?.sessionId) {
      selectedSessionId.value = res.data.sessionId;
      loadOptions();
    }
    ElMessage.success("意图识别成功");
  } catch (error) {
    // 错误提示已由 request.js 拦截器统一处理
  } finally {
    loading.value = false;
  }
}

async function generateSql() {
  if (!analysisGoal.value.trim()) {
    ElMessage.warning("请输入分析问题");
    return;
  }
  sqlLoading.value = true;
  try {
    const res = await sqlAnalysis({ text: analysisGoal.value, sessionId: selectedSessionId.value || undefined, datasetId: selectedDatasetId.value || undefined, modelConfigId: selectedModelId.value || undefined, title: sessionTitle.value || undefined, analysisGoal: sessionGoal.value || undefined });
    sqlResult.value = res.data;
    if (res.data?.sessionId) {
      selectedSessionId.value = res.data.sessionId;
      loadOptions();
    }
    if (res.code === 200) {
      ElMessage.success("SQL 生成成功，校验通过");
    } else {
      ElMessage.warning(res.message || "SQL 校验未通过");
    }
  } catch (error) {
    // 错误提示已由 request.js 拦截器统一处理
  } finally {
    sqlLoading.value = false;
  }
}

async function executeCurrentAnalysis() {
  if (!analysisGoal.value.trim()) {
    ElMessage.warning("请输入分析问题");
    return;
  }
  executing.value = true;
  executeResult.value = null;
  currentReport.value = null;
  try {
    const t0 = Date.now();
    const res = await executeAnalysis({
      text: analysisGoal.value,
      sessionId: selectedSessionId.value || undefined,
      datasetId: selectedDatasetId.value || undefined,
      modelConfigId: selectedModelId.value || undefined,
      title: sessionTitle.value || undefined,
      analysisGoal: sessionGoal.value || undefined,
    });
    executeCostMs.value = Date.now() - t0;
    executeResult.value = res.data;
    if (res.data?.sessionId) {
      selectedSessionId.value = res.data.sessionId;
      if (res.data.roundNo) ElMessage.success("第 " + res.data.roundNo + " 轮执行成功");
    }
    if (res.code === 200) {
      ElMessage.success("执行成功");
    } else {
      ElMessage.warning(res.message || "执行失败");
    }
    activeTab.value = "result";
    await nextTick();
    renderChart();
    loadOptions();
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

async function generateCurrentReport() {
  const sessionId = executeResult.value?.sessionId || selectedSessionId.value;
  const roundNo = executeResult.value?.roundNo;
  if (!sessionId) {
    ElMessage.warning("请先执行分析再生成报告");
    return;
  }
  reportGenerating.value = true;
  try {
    const res = await generateReport({ sessionId, roundNo: roundNo || undefined });
    if (res.code === 200 && res.data?.report) {
      currentReport.value = res.data.report;
      activeTab.value = "report";
      ElMessage.success("第 " + (roundNo || "?") + " 轮报告生成成功");
    } else {
      ElMessage.warning(res.message || "报告生成失败");
    }
  } catch (error) {
    // 错误提示已由 request.js 拦截器统一处理
  } finally {
    reportGenerating.value = false;
  }
}

function copyReport() {
  if (!currentReport.value?.content) return;
  navigator.clipboard?.writeText(currentReport.value.content)
    .then(() => ElMessage.success("已复制"))
    .catch(() => ElMessage.warning("复制失败"));
}

function downloadReport() {
  if (!currentReport.value?.content) return;
  const blob = new Blob([currentReport.value.content], { type: "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = (currentReport.value.title || "分析报告") + ".md";
  a.click();
  URL.revokeObjectURL(url);
}

function escapeHtml(text) {
  return String(text ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** 下载自包含 HTML：报告正文 + 当前图表截图（若已渲染），可离线查看/打印。 */
function downloadReportHtml() {
  if (!currentReport.value?.content) return;
  const title = currentReport.value.title || "分析报告";
  const parts = [];
  parts.push("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>" + escapeHtml(title) + "</title><style>body{font-family:\"Microsoft YaHei\",\"PingFang SC\",sans-serif;max-width:920px;margin:28px auto;padding:0 20px;color:#333;line-height:1.8;}h1{color:#1f2d3d;border-bottom:2px solid #409eff;padding-bottom:8px;}img{max-width:100%;}pre{white-space:pre-wrap;word-break:break-all;background:#f5f7fa;padding:14px;border-radius:6px;font-size:13px;}.muted{color:#909399;font-size:12px;}</style></head><body>");
  parts.push("<h1>" + escapeHtml(title) + "</h1>");
  parts.push("<div class=\"muted\">生成方式：" + escapeHtml(currentReport.value.generatorType || "") + "</div>");
  if (chartInstance.value) {
    parts.push("<div style=\"margin:14px 0;\"><img src=\"" + chartInstance.value.getDataURL({ pixelRatio: 2, backgroundColor: "#fff" }) + "\" /></div>");
  } else {
    parts.push("<div class=\"muted\">（图表未渲染：请先切换到图表页查看）</div>");
  }
  parts.push("<pre>" + escapeHtml(currentReport.value.content) + "</pre>");
  parts.push("</body></html>");
  const blob = new Blob([parts.join("\n")], { type: "text/html;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = title + ".html";
  a.click();
  URL.revokeObjectURL(url);
}

async function fetchSteps(sessionId) {
  const res = await listSessionSteps(sessionId);
  return res.code === 200 ? res.data || [] : [];
}

function latestRoundSteps(steps) {
  let max = 0;
  for (const s of steps || []) {
    if (s.roundNo && s.roundNo > max) max = s.roundNo;
  }
  if (!max) return steps || [];
  return (steps || []).filter((s) => s.roundNo === max);
}

function parseJson(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

async function rerunHistory(row) {
  selectedSessionId.value = row.id;
  onSessionChange(row.id);
  ElMessage.success("已填入最近分析问题，开始重新执行");
  await executeCurrentAnalysis();
}

async function correctHistory(row) {
  const steps = await fetchSteps(row.id);
  const latest = latestRoundSteps(steps);
  const failed = latest.find((s) => s.status === "FAILED" || (s.status || "").indexOf("FAIL") >= 0);
  if (!failed) {
    ElMessage.success("该会话最近一轮无失败步骤，无需纠错");
    return;
  }
  ElMessage.warning("检测到失败步骤：" + failed.stepType + "，触发重新执行（链路自动纠错）");
  selectedSessionId.value = row.id;
  onSessionChange(row.id);
  await executeCurrentAnalysis();
}

function findStep(steps, type) {
  for (const s of steps || []) {
    if (s.stepType === type) return s;
  }
  return null;
}

async function viewHistoryChart(row) {
  historyDialogType.value = "chart";
  historyDialogError.value = "";
  historyDialog.value = true;
  await nextTick();
  if (historyChartInstance.value) {
    historyChartInstance.value.dispose();
    historyChartInstance.value = null;
  }
  const steps = await fetchSteps(row.id);
  const latest = latestRoundSteps(steps);
  const executeStep = findStep(latest, "EXECUTE");
  const planStep = findStep(latest, "PLAN");
  if (!executeStep || executeStep.status !== "SUCCESS") {
    historyDialogError.value = "该会话最近一轮没有成功的执行数据";
    return;
  }
  const plan = planStep ? parseJson(planStep.outputData) : null;
  const execution = parseJson(executeStep.outputData);
  if (!execution || !execution.columns || !execution.rows || !execution.rows.length) {
    historyDialogError.value = "执行数据为空";
    return;
  }
  const chartType = plan && plan.chartType ? plan.chartType : "table";
  if (CHART_TYPES.indexOf(chartType) < 0) {
    historyDialogError.value = "该结果推荐图表类型为 " + chartType + "，暂以表格展示";
    return;
  }
  await nextTick();
  if (!historyChartRef.value) return;
  const histPlan = plan || {};
  const chartTitle = [histPlan.metrics && histPlan.metrics.join ? histPlan.metrics.join("、") : "", histPlan.timeRange].filter(Boolean).join(" · ");
  const option = buildChartOption(chartType, execution.columns, execution.rows, { title: chartTitle });
  if (!option) {
    historyDialogError.value = "数据不足或维度不支持当前图表，无法渲染";
    return;
  }
  historyChartInstance.value = echarts.init(historyChartRef.value);
  historyChartInstance.value.setOption(option);
}

async function viewHistoryInterpret(row) {
  historyDialogType.value = "interpret";
  historyInterpretText.value = "";
  historyDialog.value = true;
  const steps = await fetchSteps(row.id);
  const latest = latestRoundSteps(steps);
  const interpretStep = findStep(latest, "INTERPRET");
  if (interpretStep) {
    const output = parseJson(interpretStep.outputData);
    if (output && output.interpretation) {
      historyInterpretText.value = output.interpretation.text || "";
    }
  }
}

async function deleteHistory(row) {
  try {
    await ElMessageBox.confirm("确认删除该会话？其步骤与报告将一并删除。", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteSession(row.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      if (selectedSessionId.value === row.id) selectedSessionId.value = null;
      loadOptions();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}
function renderChart() {
  if (chartInstance.value) {
    chartInstance.value.dispose();
    chartInstance.value = null;
  }
  if (!showChart.value || !chartRef.value || !executeResult.value?.execution) return;
  const rect = chartRef.value.getBoundingClientRect();
  if (!rect.width || !rect.height) return; // 容器不可见（图表 Tab 未激活）时跳过，待切到图表 Tab 再渲染
  const { columns, rows } = executeResult.value.execution;
  const chartType = executeResult.value.chartType;
  const plan = executeResult.value.plan || {};
  const chartTitle = [plan.metrics && plan.metrics.join ? plan.metrics.join("、") : "", plan.timeRange].filter(Boolean).join(" · ");
  const option = buildChartOption(chartType, columns, rows, { title: chartTitle });
  if (!option) {
    chartRef.value.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:#909399;font-size:13px;">暂无数据，无法渲染图表</div>';
    return;
  }
  chartInstance.value = echarts.init(chartRef.value);
  chartInstance.value.setOption(option);
}

watch(activeTab, (tab) => {
  if (tab !== "chart") return;
  nextTick(() => {
    if (chartInstance.value) {
      chartInstance.value.resize();
    } else {
      renderChart();
    }
  });
});
function handleResize() {
  chartInstance.value?.resize();
  historyChartInstance.value?.resize();
}

window.addEventListener("resize", handleResize);

onMounted(async () => {
  await loadOptions();
  if (route.query.sessionId) {
    selectedSessionId.value = Number(route.query.sessionId);
    onSessionChange(selectedSessionId.value);
  }
  if (route.query.goal) {
    analysisGoal.value = route.query.goal;
  } else if (route.query.title) {
    analysisGoal.value = route.query.title;
  }
  if (route.query.datasetId) {
    selectedDatasetId.value = Number(route.query.datasetId);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize);
  chartInstance.value?.dispose();
  historyChartInstance.value?.dispose();
});
</script>

