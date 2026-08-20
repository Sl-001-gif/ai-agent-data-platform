<template>
  <div style="max-width: 1200px; margin: 0 auto;">
    <!-- 顶部：执行调度与监控区 -->
    <el-card style="margin-bottom: 16px;">
      <template #header><span>执行调度与监控</span></template>
      <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap;">
        <el-select v-model="selectedPlanId" placeholder="选择需要执行的分析计划" style="width: 380px;" filterable @change="onSelectPlan">
          <el-option v-for="p in plans" :key="p.id" :label="p.title" :value="p.id" />
        </el-select>
        <el-button type="primary" :loading="executing" :disabled="!selectedPlanId" @click="executePlan">执行计划</el-button>
        <el-button v-if="selectedPlanId" :disabled="executing" @click="loadDetail">刷新状态</el-button>
      </div>
      <div v-if="currentPlan" style="margin-top: 10px; font-size: 13px; color: #606266;">
        分析目标：{{ currentPlan.goal }}
      </div>
    </el-card>

    <!-- 中间：步骤执行状态监控列表区 -->
    <el-card>
      <template #header>
        <span>步骤执行状态监控</span>
        <el-tag v-if="currentPlan" size="small" :type="statusTagType(currentPlan.status)" style="margin-left: 8px;">{{ statusName(currentPlan.status) }}</el-tag>
      </template>
      <el-table :data="steps" v-loading="loading" border stripe size="small" :row-class-name="rowClass">
        <el-table-column prop="stepNo" label="序号" width="60" />
        <el-table-column prop="name" label="步骤名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="分析问题" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.question || "-" }}</template>
        </el-table-column>
        <el-table-column label="SQL 目的" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sqlPurpose || "-" }}</template>
        </el-table-column>
        <el-table-column label="建议图表" width="90">
          <template #default="{ row }">{{ row.chartType || "-" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行详情" min-width="220">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                耗时 {{ row.durationMs != null ? row.durationMs + " ms" : "-" }}<template v-if="row.error">，错误：{{ row.error }}</template>
              </span>
              <el-button v-if="row.sql" size="small" type="primary" link @click="openDetail(row)">详情</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && steps.length === 0" description="选择计划后点击「执行计划」，或选择已有计划查看执行结果" />
    </el-card>

    <!-- 步骤详情弹窗 -->
    <el-dialog v-model="detailVisible" title="步骤执行详情" width="820px" top="5vh">
      <div v-if="detailRow" style="max-height: 66vh; overflow: auto; line-height: 1.8; font-size: 13px;">
        <div><b>步骤：</b>{{ detailRow.name }}（{{ detailRow.question }}）</div>
        <div><b>SQL 目的：</b>{{ detailRow.sqlPurpose || "-" }}</div>
        <div><b>返回行数：</b>{{ detailRow.rowCount ?? "-" }}</div>
        <div style="margin-top: 8px;"><b>SQL：</b></div>
        <pre style="background: #f5f7fa; padding: 10px; border-radius: 4px; white-space: pre-wrap; word-break: break-all; margin: 4px 0 0;">{{ detailRow.sql }}</pre>
        <div v-if="detailRow.interpretation" style="margin-top: 8px;"><b>AI 解读：</b>{{ detailRow.interpretation }}</div>
        <div v-if="detailRow.error" style="margin-top: 8px; color: #f56c6c;"><b>错误：</b>{{ detailRow.error }}</div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { listAgentPlans, getAgentPlan, executeAgentPlan } from "@/api/agentPlan";

const route = useRoute();
const plans = ref([]);
const selectedPlanId = ref(route.query.planId ? Number(route.query.planId) : null);
const currentPlan = ref(null);
const steps = ref([]);
const loading = ref(false);
const executing = ref(false);
const detailVisible = ref(false);
const detailRow = ref(null);

function statusName(status) {
  return { PENDING: "待执行", RUNNING: "执行中", SUCCESS: "已执行", FAILED: "失败", GENERATED: "已生成", EXECUTING: "执行中", DONE: "已完成" }[status] || (status || "-");
}
function statusTagType(status) {
  return status === "SUCCESS" || status === "DONE" ? "success" : (status === "FAILED" ? "danger" : (status === "RUNNING" || status === "EXECUTING" ? "warning" : "info"));
}
function rowClass({ row }) {
  return row.status === "FAILED" ? "el-row-error" : "";
}

async function fetchPlans() {
  const res = await listAgentPlans();
  if (res.code === 200) plans.value = res.data || [];
}

async function loadDetail() {
  if (!selectedPlanId.value) return;
  loading.value = true;
  try {
    const res = await getAgentPlan(selectedPlanId.value);
    if (res.code === 200) {
      currentPlan.value = res.data;
      steps.value = res.data.steps || [];
    } else {
      ElMessage.warning(res.message || "加载失败");
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false;
  }
}

function onSelectPlan() {
  loadDetail();
}

async function executePlan() {
  if (!selectedPlanId.value) return;
  executing.value = true;
  try {
    const res = await executeAgentPlan(selectedPlanId.value);
    if (res.code === 200) {
      currentPlan.value = res.data;
      steps.value = res.data.steps || [];
      ElMessage.success("执行完成");
    } else {
      ElMessage.warning(res.message || "执行失败");
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    executing.value = false;
  }
}

function openDetail(row) {
  detailRow.value = row;
  detailVisible.value = true;
}

onMounted(() => {
  fetchPlans().then(() => {
    if (selectedPlanId.value) loadDetail();
  });
});
</script>

<style scoped>
:deep(.el-row-error) {
  background: #fef0f0;
}
</style>