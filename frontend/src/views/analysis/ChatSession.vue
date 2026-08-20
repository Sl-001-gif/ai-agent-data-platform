<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="9">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>会话列表</span>
              <div>
                <el-button type="primary" size="small" @click="openCreate">新建会话</el-button>
                <el-button type="danger" size="small" plain :disabled="!selectedIds.length" @click="handleBatchDelete">批量删除</el-button>
              </div>
            </div>
          </template>
          <div style="display: flex; gap: 8px; margin-bottom: 10px;">
            <el-input v-model="keyword" placeholder="搜索会话标题" clearable size="small" style="flex: 1;" @keyup.enter="searchSessions" />
            <el-select v-model="filterDatasetId" placeholder="数据集" clearable size="small" style="width: 130px;" @change="searchSessions">
              <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
          </div>
          <div v-loading="loading">
            <el-table :data="sessions" size="small" @selection-change="onSelectionChange">
              <el-table-column type="selection" width="36" />
              <el-table-column label="会话" min-width="200">
                <template #default="{ row }">
                  <div class="session-item" :class="{ active: currentId === row.id }" @click="selectSession(row)">
                    <div style="font-weight: 600;">{{ row.title }}</div>
                    <div style="font-size: 12px; color: #909399; margin-top: 2px;">
                      目标：{{ row.analysisGoal || row.title || "-" }}
                    </div>
                    <div style="font-size: 12px; color: #909399; margin-top: 2px; display: flex; justify-content: space-between;">
                      <span>{{ datasetName(row.datasetId) }}</span>
                      <span>{{ row.createTime || "-" }}</span>
                    </div>
                    <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ statusName(row.status) }}</el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120">
                <template #default="{ row }">
                  <el-button size="small" type="primary" link @click.stop="continueAnalysis(row)">继续分析</el-button>
                  <el-button size="small" type="danger" link @click.stop="handleDelete(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!loading && sessions.length === 0" description="暂无会话" />
            <div style="display: flex; justify-content: flex-end; margin-top: 10px;">
              <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="pageSize" :current-page="page" :page-sizes="[10, 20, 50, 100]" @current-change="changePage" @size-change="changeSize" />
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="15">
        <el-card v-if="current">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <div>
                <span style="font-weight: 600;">{{ current.title }}</span>
                <el-tag size="small" :type="current.status === 'ACTIVE' ? 'success' : 'info'" style="margin-left: 8px;">{{ statusName(current.status) }}</el-tag>
                <span style="font-size: 12px; color: #909399; margin-left: 8px;">{{ datasetName(current.datasetId) }}</span>
              </div>
              <el-button size="small" type="primary" @click="continueAnalysis(current)">继续分析</el-button>
            </div>
          </template>
          <div v-loading="stepsLoading">
            <template v-for="round in roundResults" :key="round.roundNo">
              <div class="round-card">
                <div class="round-head">
                  <el-tag size="small" type="warning" effect="plain">第 {{ round.roundNo }} 轮</el-tag>
                  <span class="round-question">{{ round.question || "-" }}</span>
                  <el-tag size="small" :type="round.success ? 'success' : 'danger'">{{ round.success ? "成功" : "失败" }}</el-tag>
                  <span class="round-meta-item">{{ round.durationMs }} ms</span>
                </div>
                <div class="round-meta">
                  <el-tag v-if="round.intentName" size="small" type="info" effect="plain">{{ round.intentName }}</el-tag>
                  <span v-if="round.confidence != null" class="round-meta-item">置信度 {{ round.confidence }}</span>
                  <span v-if="round.targetTable" class="round-meta-item">目标表 {{ round.targetTable }}</span>
                  <span v-if="round.metricsText" class="round-meta-item">指标 {{ round.metricsText }}</span>
                  <span v-if="round.chartType" class="round-meta-item">图表 {{ round.chartType }}</span>
                  <span v-if="round.rowCount != null" class="round-meta-item">结果 {{ round.rowCount }} 行</span>
                </div>
                <el-tabs v-if="round.steps.length" type="border-card" size="small" class="round-tabs" @tab-change="(name) => onRoundTabChange(round.roundNo, name)">
                  <el-tab-pane name="sql" label="SQL">
                    <pre class="sql-block">{{ round.sqlText || "该轮未生成 SQL" }}</pre>
                  </el-tab-pane>
                  <el-tab-pane name="result" label="查询结果">
                    <el-table v-if="round.columns.length" :data="round.rows" size="small" max-height="260" border>
                      <el-table-column v-for="col in round.columns" :key="col" :prop="col" :label="col" min-width="110" show-overflow-tooltip />
                    </el-table>
                    <el-empty v-else description="该轮无执行数据" :image-size="60" />
                  </el-tab-pane>
                  <el-tab-pane name="chart" label="图表">
                    <div v-if="round.chartOk" :ref="(el) => setChartRef(round.roundNo, el)" class="round-chart"></div>
                    <el-empty v-else description="该轮无可用图表数据" :image-size="60" />
                  </el-tab-pane>
                  <el-tab-pane name="interpret" label="数据解读">
                    <div class="interpret-text">{{ round.interpretText || "该轮暂无解读" }}</div>
                  </el-tab-pane>
                  <el-tab-pane name="report" label="报告">
                    <div v-if="round.report">
                      <el-button size="small" type="primary" @click="openReport(round)">查看报告</el-button>
                      <el-tag size="small" style="margin-left: 8px;">{{ round.report.generatorType }}</el-tag>
                    </div>
                    <el-empty v-else description="该轮未生成报告" :image-size="60" />
                  </el-tab-pane>
                </el-tabs>
              </div>
            </template>
            <el-empty v-if="!stepsLoading && steps.length === 0" description="该会话暂无分析结果，点击「继续分析」发起第一轮" />
          </div>
        </el-card>
        <el-card v-else>
          <el-empty description="从左侧选择一个会话，查看该会话的分析结果" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 新建会话弹窗 -->
    <el-dialog v-model="createVisible" title="新建会话" width="520px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="会话标题" prop="title">
          <el-input v-model="form.title" placeholder="不填则默认取分析目标" />
        </el-form-item>
        <el-form-item label="分析目标" prop="goal">
          <el-input v-model="form.goal" type="textarea" :rows="3" placeholder="该会话整体研究主题（如：理解邵阳市发展状况），不是单个分析问题" />
        </el-form-item>
        <el-form-item label="数据集" prop="datasetId">
          <el-select v-model="form.datasetId" placeholder="不选则全库路由" clearable style="width: 100%;">
            <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <div style="font-size: 12px; color: #909399;">绑定后解读/报告注入该数据集元数据，不选则全库元数据路由</div>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :label="'ACTIVE'">激活</el-radio>
            <el-radio :label="'ARCHIVED'">归档</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 报告查看弹窗 -->
    <el-dialog v-model="reportVisible" :title="reportTitle" width="760px" top="5vh">
      <div class="report-content">{{ reportContent }}</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import * as echarts from "echarts";
import { listSessions, listSessionSteps, deleteSession, batchDeleteSessions, listDatasetOptions, createSession, getSessionReport } from "@/api/history";
import { buildChartOption, CHART_TYPES } from "@/utils/chartOption";


const router = useRouter();
const sessions = ref([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const steps = ref([]);
const keyword = ref("");
const filterDatasetId = ref(null);
const loading = ref(false);
const stepsLoading = ref(false);
const currentId = ref(null);
const current = ref(null);
const datasets = ref([]);
const datasetMap = ref({});
const selectedIds = ref([]);
const createVisible = ref(false);
const creating = ref(false);
const formRef = ref(null);
const reportVisible = ref(false);
const reportTitle = ref("");
const reportContent = ref("");
const chartInstances = {};
const chartRefs = {};

const form = ref({ title: "", goal: "", datasetId: null, status: "ACTIVE" });
const formRules = {
  goal: [{ required: true, message: "请输入分析目标", trigger: "blur" }],
};

function parseJson(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

/** 会话详情 = 该会话每轮的分析结果：问题、概览、SQL、结果行、图表、解读、报告。 */
const roundResults = computed(() => {
  const byRound = new Map();
  for (const step of steps.value || []) {
    const rn = step.roundNo || 1;
    if (!byRound.has(rn)) {
      byRound.set(rn, {
        roundNo: rn,
        question: "",
        success: true,
        durationMs: 0,
        intentName: "",
        confidence: null,
        targetTable: "",
        metricsText: "",
        chartType: "",
        rowCount: null,
        sqlText: "",
        columns: [],
        rows: [],
        interpretText: "",
        report: null,
        steps: [],
      });
    }
    const r = byRound.get(rn);
    r.steps.push(step);
    if (step.durationMs != null) r.durationMs += step.durationMs;
    if (step.status !== "SUCCESS") r.success = false;
    const out = parseJson(step.outputData);
    if (step.stepType === "INTENT") {
      const q = parseJson(step.inputData);
      r.question = typeof q === "string" ? q : (step.inputData || "");
      if (out) {
        r.intentName = out.intentName || "";
        r.confidence = out.confidence != null ? out.confidence : null;
      }
    } else if (step.stepType === "PLAN" && out) {
      r.targetTable = out.targetTable || "";
      r.chartType = out.chartType || "";
      r.metricsText = Array.isArray(out.metrics) ? out.metrics.map((m) => (/增速/.test(m) ? m + "（%）" : m)).join("、") : (out.metrics || "");
    } else if (step.stepType === "SQL" && out) {
      r.sqlText = out.sql || "";
    } else if (step.stepType === "EXECUTE" && out) {
      r.columns = out.columns || [];
      r.rows = out.rows || [];
      r.rowCount = (out.rows || []).length;
    } else if (step.stepType === "INTERPRET" && out && out.interpretation) {
      r.interpretText = typeof out.interpretation === "string" ? out.interpretation : (out.interpretation.text || "");
    } else if (step.stepType === "REPORT" && out) {
      r.report = { title: out.title || "", generatorType: out.generatorType || "" };
    }
  }
  const list = [...byRound.values()];
  list.sort((a, b) => a.roundNo - b.roundNo);
  for (const r of list) {
    r.chartOk = CHART_TYPES.includes(r.chartType) && r.columns.length > 0 && r.rows.length > 0;
  }
  return list;
});

function statusName(status) {
  return status === "ACTIVE" ? "进行中" : (status === "ARCHIVED" ? "已完成" : (status || "-"));
}

function datasetName(id) {
  if (!id) return "全库";
  return (datasetMap.value[id] || {}).name || ("数据集 #" + id);
}

function onSelectionChange(rows) {
  selectedIds.value = rows.map((r) => r.id);
}

async function fetchSessions() {
  loading.value = true;
  try {
    const res = await listSessions(keyword.value.trim() || undefined, filterDatasetId.value || undefined, page.value, pageSize.value);
    if (res.code === 200) {
      sessions.value = res.data?.rows || [];
      total.value = res.data?.total || 0;
    }
  } catch (e) {
    ElMessage.error("加载会话失败");
  } finally {
    loading.value = false;
  }
}

function searchSessions() {
  page.value = 1;
  fetchSessions();
}

function changePage(p) {
  page.value = p;
  fetchSessions();
}

function changeSize(s) {
  pageSize.value = s;
  page.value = 1;
  fetchSessions();
}

async function fetchDatasets() {
  try {
    const res = await listDatasetOptions();
    if (res.code === 200) {
      datasets.value = res.data || [];
      datasetMap.value = {};
      for (const d of datasets.value) datasetMap.value[d.id] = d;
    }
  } catch (e) {
    // 数据集接口不可用时静默降级
  }
}

function openCreate() {
  form.value = { title: "", goal: "", datasetId: null, status: "ACTIVE" };
  createVisible.value = true;
}

async function handleCreate() {
  await formRef.value.validate();
  creating.value = true;
  try {
    const res = await createSession({
      title: form.value.title || undefined,
      text: form.value.goal,
      datasetId: form.value.datasetId || undefined,
      status: form.value.status || "ACTIVE",
    });
    if (res.code === 200 && res.data?.id) {
      ElMessage.success("会话创建成功");
      createVisible.value = false;
      await fetchSessions();
      router.push({ path: "/ai-analysis", query: { sessionId: res.data.id } });
    } else {
      ElMessage.warning(res.message || "创建失败");
    }
  } catch (e) {
    ElMessage.error("创建失败");
  } finally {
    creating.value = false;
  }
}

async function selectSession(session) {
  disposeAllCharts();
  currentId.value = session.id;
  current.value = session;
  stepsLoading.value = true;
  try {
    const res = await listSessionSteps(session.id);
    if (res.code === 200) steps.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载步骤失败");
  } finally {
    stepsLoading.value = false;
  }
}

function continueAnalysis(session) {
  router.push({ path: "/ai-analysis", query: { sessionId: session.id } });
}

async function handleDelete(session) {
  try {
    await ElMessageBox.confirm("确认删除该会话？其步骤与报告将一并删除。", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteSession(session.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      if (currentId.value === session.id) {
        disposeAllCharts();
        currentId.value = null;
        current.value = null;
        steps.value = [];
      }
      const maxPage = Math.max(Math.ceil(Math.max(total.value - 1, 0) / pageSize.value), 1);
      if (page.value > maxPage) page.value = maxPage;
      await fetchSessions();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

async function handleBatchDelete() {
  if (!selectedIds.value.length) return;
  try {
    await ElMessageBox.confirm("确认删除选中的 " + selectedIds.value.length + " 个会话？其步骤与报告将一并删除。", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    const res = await batchDeleteSessions(selectedIds.value);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      disposeAllCharts();
      currentId.value = null;
      current.value = null;
      steps.value = [];
      const maxPage = Math.max(Math.ceil(Math.max(total.value - selectedIds.value.length, 0) / pageSize.value), 1);
      if (page.value > maxPage) page.value = maxPage;
      await fetchSessions();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

// ---------- 图表 ----------

function setChartRef(roundNo, el) {
  if (el) chartRefs[roundNo] = el;
  else delete chartRefs[roundNo];
}

function onRoundTabChange(roundNo, name) {
  if (name !== "chart") return;
  nextTick(() => renderRoundChart(roundNo));
}

function renderRoundChart(roundNo) {
  const round = roundResults.value.find((r) => r.roundNo === roundNo);
  const el = chartRefs[roundNo];
  if (!round || !round.chartOk || !el) return;
  if (chartInstances[roundNo]) {
    chartInstances[roundNo].dispose();
    delete chartInstances[roundNo];
  }
  const option = buildChartOption(round.chartType, round.columns, round.rows, { title: round.metricsText || round.question });
  if (!option) {
    el.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:#909399;font-size:13px;">暂无数据，无法渲染图表</div>';
    return;
  }
  chartInstances[roundNo] = echarts.init(el);
  chartInstances[roundNo].setOption(option);
}

function disposeAllCharts() {
  for (const key of Object.keys(chartInstances)) {
    chartInstances[key].dispose();
    delete chartInstances[key];
  }
}

function handleResize() {
  for (const key of Object.keys(chartInstances)) {
    chartInstances[key].resize();
  }
}

async function openReport(round) {
  if (!current.value?.id || round.roundNo == null) return;
  try {
    const res = await getSessionReport(current.value.id, round.roundNo);
    if (res.code === 200 && res.data) {
      reportTitle.value = res.data.title || "分析报告";
      reportContent.value = res.data.content || "（报告内容为空）";
      reportVisible.value = true;
    } else {
      ElMessage.warning(res.message || "未找到该轮报告");
    }
  } catch (e) {
    // 拦截器已统一提示（400 业务错误/网络错误）
  }
}

onMounted(() => {
  fetchDatasets();
  fetchSessions();
  window.addEventListener("resize", handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize);
  disposeAllCharts();
});
</script>

<style scoped>
.session-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  margin-bottom: 6px;
  cursor: pointer;
}
.session-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.round-card {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  margin-bottom: 12px;
  padding: 10px;
}
.round-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.round-question {
  font-weight: 600;
  color: #303133;
  flex: 1;
  min-width: 120px;
}
.round-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 8px 0;
}
.round-meta-item {
  font-size: 12px;
  color: #606266;
}
.round-tabs {
  margin-top: 4px;
}
.sql-block {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 10px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.interpret-text {
  white-space: pre-wrap;
  line-height: 1.8;
  color: #303133;
  font-size: 13px;
  max-height: 320px;
  overflow-y: auto;
}
.round-chart {
  height: 320px;
  width: 100%;
}
.report-content {
  white-space: pre-wrap;
  line-height: 1.8;
  max-height: 70vh;
  overflow-y: auto;
  font-size: 13px;
}
</style>