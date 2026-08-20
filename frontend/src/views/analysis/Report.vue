<template>
  <div style="max-width: 1200px; margin: 0 auto;">
    <el-row :gutter="16">
      <!-- 左侧：生成报告配置区 -->
      <el-col :span="9">
        <el-card>
          <template #header><span>生成报告配置</span></template>
          <el-form label-position="top">
            <el-form-item label="选择已生成的分析计划">
              <el-select v-model="selectedPlanId" placeholder="选择已执行的分析计划" style="width: 100%;" filterable>
                <el-option v-for="p in plans" :key="p.id" :label="p.title" :value="p.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="报告生成模型">
              <el-select v-model="selectedModelId" placeholder="自动按用途路由" clearable style="width: 100%;">
                <el-option v-for="m in models" :key="m.id" :label="m.name + '（' + m.modelName + '）'" :value="m.id" />
              </el-select>
            </el-form-item>
            <el-button type="primary" style="width: 100%;" :loading="generating" :disabled="!selectedPlanId" @click="generateReportFn">
              生成报告
            </el-button>
          </el-form>
        </el-card>
      </el-col>
      <!-- 右侧：历史报告列表区 -->
      <el-col :span="15">
        <el-card>
          <template #header>
            <span>历史报告列表</span>
            <el-button size="small" text type="primary" style="margin-left: 8px;" @click="fetchList">刷新</el-button>
          </template>
          <el-table :data="rows" v-loading="loading" border stripe size="small">
            <el-table-column prop="reportTitle" label="报告标题" min-width="200" show-overflow-tooltip />
            <el-table-column label="数据集" width="130">
              <template #default="{ row }">{{ datasetOf(row) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" type="success">已生成</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" min-width="150" />
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="viewReport(row)">查看</el-button>
                <el-button size="small" type="danger" link @click="handleDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && rows.length === 0" description="暂无报告，选择左侧计划生成" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 报告详情阅读视图：步骤标题 → 内容 → 对应图表 -->
    <el-dialog v-model="dialogVisible" title="报告详情" width="980px" top="4vh" destroy-on-close @closed="disposeCharts">
      <div v-if="detail" style="max-height: 74vh; overflow: auto; padding: 0 6px 12px;">
        <div style="display: flex; align-items: center; justify-content: space-between; border-bottom: 2px solid #409eff; padding-bottom: 8px;">
          <h3 style="margin: 0;">{{ detail.reportTitle }}</h3>
          <div>
            <el-button size="small" @click="downloadReportMd">下载 Markdown</el-button>
            <el-button size="small" type="primary" plain @click="downloadReportHtml">下载 HTML（含图表）</el-button>
          </div>
        </div>
        <div style="color: #909399; font-size: 12px; margin-bottom: 12px; margin-top: 8px;">生成方式：{{ detail.reportGeneratorType }}</div>

        <div v-for="(sec, i) in reportView.sections" :key="i" style="margin-bottom: 18px;">
          <div v-if="sec.heading" :class="'md-heading md-h' + Math.min(sec.level, 3)">{{ sec.heading }}</div>
          <div v-if="sec.body" class="md-body" v-html="renderMarkdown(sec.body)"></div>
          <div v-if="sec.chart" style="margin-top: 10px;">
            <div style="font-size: 12px; color: #909399; margin-bottom: 4px;">对应图表 · {{ sec.chart.chartType }}</div>
            <el-alert
              v-if="sec.chart.dataStatus === 'blocked'"
              type="warning" show-icon :closable="false"
              title="查询/数据异常，未展示图表"
              :description="sec.chart.blockedText || '该步骤查询结果为空或与目标指标不匹配'" />
            <el-table v-else-if="sec.chart.chartType === 'table'" :data="sec.chart.rows" border stripe size="small" style="width: 100%;">
              <el-table-column v-for="col in sec.chart.columns" :key="col" :prop="col" :label="col" min-width="110" show-overflow-tooltip />
            </el-table>
            <div v-else :ref="(el) => setChartRef(i, el)" style="width: 100%; height: 320px;"></div>
          </div>
        </div>

        <div v-if="reportView.orphans.length" style="margin-top: 18px;">
          <div style="font-weight: 600; margin-bottom: 8px;">附：其余图表</div>
          <div v-for="(chart, j) in reportView.orphans" :key="'o' + j" style="margin-bottom: 14px;">
            <div style="font-size: 12px; color: #909399; margin-bottom: 4px;">步骤 {{ chart.stepNo }} · {{ chart.stepName }}（{{ chart.chartType }}）</div>
            <el-alert
              v-if="chart.dataStatus === 'blocked'"
              type="warning" show-icon :closable="false"
              title="查询/数据异常，未展示图表"
              :description="chart.blockedText || '该步骤查询结果为空或与目标指标不匹配'" />
            <el-table v-else-if="chart.chartType === 'table'" :data="chart.rows" border stripe size="small" style="width: 100%;">
              <el-table-column v-for="col in chart.columns" :key="col" :prop="col" :label="col" min-width="110" show-overflow-tooltip />
            </el-table>
            <div v-else :ref="(el) => setChartRef('o' + j, el)" style="width: 100%; height: 320px;"></div>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import * as echarts from "echarts";
import { generateAgentReport, listAgentReports, listAgentPlans, getAgentPlan, deleteAgentPlan } from "@/api/agentPlan";
import { buildChartOption } from "@/utils/chartOption";
import { parseReportSections, renderMarkdown, attachCharts } from "@/utils/reportRender";
import { listDatasetOptions, listModelOptions } from "@/api/history";

const loading = ref(false);
const generating = ref(false);
const rows = ref([]);
const plans = ref([]);
const models = ref([]);
const selectedPlanId = ref(null);
const selectedModelId = ref(null);
const dialogVisible = ref(false);
const detail = ref(null);
const chartRefs = {};
const chartInstances = [];

function datasetOf(row) {
  if (!row.datasetId) return "全库";
  const d = datasetOptions.value.find((x) => x.id === row.datasetId);
  return d ? d.name : ("数据集 #" + row.datasetId);
}
const datasetOptions = ref([]);

async function loadOptions() {
  const [d, m] = await Promise.allSettled([listDatasetOptions(), listModelOptions()]);
  if (d.status === "fulfilled" && d.value?.code === 200) datasetOptions.value = d.value.data || [];
  if (m.status === "fulfilled" && m.value?.code === 200) models.value = m.value.data || [];
}

async function fetchPlans() {
  const res = await listAgentPlans();
  if (res.code === 200) plans.value = res.data || [];
}

async function fetchList() {
  loading.value = true;
  try {
    const res = await listAgentReports();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false;
  }
}

async function generateReportFn() {
  if (!selectedPlanId.value) return;
  generating.value = true;
  try {
    const res = await generateAgentReport(selectedPlanId.value, { modelConfigId: selectedModelId.value || undefined });
    if (res.code === 200) {
      ElMessage.success("报告生成成功");
      fetchList();
      viewReport({ id: selectedPlanId.value });
    } else {
      ElMessage.warning(res.message || "报告生成失败");
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    generating.value = false;
  }
}

async function viewReport(row) {
  const res = await getAgentPlan(row.id);
  if (res.code === 200 && res.data.reportContent) {
    detail.value = res.data;
    for (const k of Object.keys(chartRefs)) delete chartRefs[k];
    dialogVisible.value = true;
    await nextTick();
    renderCharts();
  } else {
    ElMessage.warning(res.message || "该计划暂无报告");
  }
}

function setChartRef(i, el) {
  if (el) chartRefs[i] = el;
}

/** 报告正文按标题分段 + 图表挂到对应步骤段落。 */
const reportView = computed(() => {
  if (!detail.value) return { sections: [], orphans: [] };
  return attachCharts(parseReportSections(detail.value.reportContent), detail.value.charts || []);
});

function renderCharts() {
  disposeCharts();
  const view = reportView.value;
  view.sections.forEach((sec, i) => {
    if (!sec.chart) return;
    if (sec.chart.chartType === 'table') return;
    const el = chartRefs[i];
    if (!el) return;
    const option = buildChartOption(sec.chart.chartType, sec.chart.columns || [], sec.chart.rows || [], { title: sec.chart.title || "" });
    if (!option) {
      el.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:#909399;font-size:13px;">暂无数据，无法渲染图表</div>';
      return;
    }
    const inst = echarts.init(el);
    inst.setOption(option);
    chartInstances.push(inst);
  });
  view.orphans.forEach((chart, j) => {
    if (chart.chartType === 'table') return;
    const el = chartRefs["o" + j];
    if (!el) return;
    const option = buildChartOption(chart.chartType, chart.columns || [], chart.rows || [], { title: chart.title || "" });
    if (!option) {
      el.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:#909399;font-size:13px;">暂无数据，无法渲染图表</div>';
      return;
    }
    const inst = echarts.init(el);
    inst.setOption(option);
    chartInstances.push(inst);
  });
}

function disposeCharts() {
  for (const inst of chartInstances) {
    try { inst.dispose(); } catch (e) { /* ignore */ }
  }
  chartInstances.length = 0;
}

function escapeHtml(text) {
  return String(text ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function downloadBlob(text, type, filename) {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function tableToHtml(chart) {
  const cols = chart.columns || [];
  const rows = chart.rows || [];
  let html = "<table><thead><tr>" + cols.map((c) => "<th>" + escapeHtml(c) + "</th>").join("") + "</tr></thead><tbody>";
  html += rows.map((r) => "<tr>" + cols.map((c) => "<td>" + escapeHtml(r[c]) + "</td>").join("") + "</tr>").join("");
  html += "</tbody></table>";
  return html;
}

/** 下载报告 Markdown 原文（服务端生成内容，保留完整结构）。 */
function downloadReportMd() {
  if (!detail.value?.reportContent) return;
  downloadBlob(detail.value.reportContent, "text/markdown;charset=utf-8", (detail.value.reportTitle || "分析报告") + ".md");
}

/** 下载自包含 HTML：报告全文 + 步骤对应图表（ECharts 截图 base64 内嵌，双击即看、可打印 PDF）。 */
function downloadReportHtml() {
  if (!detail.value) return;
  const title = detail.value.reportTitle || "分析报告";
  const parts = [];
  parts.push("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>" + escapeHtml(title) + "</title><style>body{font-family:\"Microsoft YaHei\",\"PingFang SC\",sans-serif;max-width:920px;margin:28px auto;padding:0 20px;color:#333;line-height:1.8;}h1{color:#1f2d3d;border-bottom:2px solid #409eff;padding-bottom:8px;}h2,h3{color:#303133;}img{max-width:100%;}table{border-collapse:collapse;width:100%;margin:10px 0;}th,td{border:1px solid #dcdfe6;padding:6px 10px;font-size:13px;}th{background:#f5f7fa;}.muted{color:#909399;font-size:12px;}</style></head><body>");
  parts.push("<div class=\"muted\">生成方式：" + escapeHtml(detail.value.reportGeneratorType || "") + "</div>");
  parts.push("<h1>" + escapeHtml(title) + "</h1>");
  const view = reportView.value;
  const pushChart = (chart, refKey) => {
    if (!chart) return;
    if (chart.dataStatus === "blocked") {
      parts.push("<div style=\"color:#b88230;background:#fdf6ec;padding:8px 12px;border-radius:4px;margin:10px 0;\">⚠️ " + escapeHtml(chart.blockedText || "查询/数据异常，未展示图表") + "</div>");
      return;
    }
    if (chart.chartType === "table") {
      parts.push(tableToHtml(chart));
      return;
    }
    const el = chartRefs[refKey];
    const inst = el ? echarts.getInstanceByDom(el) : null;
    if (inst) {
      parts.push("<div style=\"margin:14px 0;\"><img src=\"" + inst.getDataURL({ pixelRatio: 2, backgroundColor: "#fff" }) + "\" alt=\"" + escapeHtml(chart.title || "") + "\" /></div>");
    } else {
      parts.push("<div class=\"muted\">（图表未渲染：" + escapeHtml(chart.title || chart.chartType) + "）</div>");
    }
  };
  view.sections.forEach((sec, i) => {
    if (sec.heading) parts.push("<h" + Math.min(sec.level, 3) + ">" + escapeHtml(sec.heading) + "</h" + Math.min(sec.level, 3) + ">");
    if (sec.body) parts.push("<div>" + renderMarkdown(sec.body) + "</div>");
    if (sec.chart) pushChart(sec.chart, i);
  });
  if (view.orphans.length) {
    parts.push("<hr><h2>附：其余图表</h2>");
    view.orphans.forEach((chart, j) => {
      parts.push("<p class=\"muted\">步骤 " + (chart.stepNo || "") + " · " + escapeHtml(chart.stepName || "") + "（" + chart.chartType + "）</p>");
      pushChart(chart, "o" + j);
    });
  }
  parts.push("</body></html>");
  downloadBlob(parts.join("\n"), "text/html;charset=utf-8", title + ".html");
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm("确认删除该计划？其报告将一并删除。", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteAgentPlan(row.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      fetchList();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(() => {
  loadOptions();
  fetchPlans();
  fetchList();
});
onBeforeUnmount(disposeCharts);
</script>

<style scoped>
.md-heading {
  font-weight: 600;
  line-height: 1.5;
}
.md-h1 { font-size: 20px; }
.md-h2 {
  font-size: 17px;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 6px;
  margin-bottom: 8px;
}
.md-h3 {
  font-size: 15px;
  color: #409eff;
  border-left: 3px solid #409eff;
  padding-left: 8px;
  margin: 14px 0 8px;
}
.md-body {
  font-size: 14px;
  line-height: 1.9;
  color: #303133;
  word-break: break-word;
}
.md-body p { margin: 6px 0; }
.md-body ul, .md-body ol { margin: 6px 0; padding-left: 22px; }
.md-body li { margin: 3px 0; }
.md-body strong { font-weight: 600; }
.md-body code {
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 3px;
  padding: 1px 5px;
  font-size: 12.5px;
}
.md-body hr { border: none; border-top: 1px solid #ebeef5; margin: 10px 0; }
</style>
