<template>
  <div style="max-width: 1200px; margin: 0 auto;">
    <!-- 顶部：计划生成配置区 -->
    <el-card style="margin-bottom: 16px;">
      <template #header><span>计划生成配置</span></template>
      <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px;">
        <el-select v-model="selectedSessionId" placeholder="选择会话（可选）" clearable size="small" style="width: 220px;">
          <el-option v-for="s in sessionOptions" :key="s.id" :label="s.title" :value="s.id" />
        </el-select>
        <el-select v-model="selectedDatasetId" placeholder="选择数据集" clearable size="small" style="width: 200px;">
          <el-option v-for="d in datasetOptions" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-select v-model="selectedModelId" placeholder="计划生成模型（自动路由）" clearable size="small" style="width: 210px;">
          <el-option v-for="m in modelOptions" :key="m.id" :label="m.name + '（' + m.modelName + '）'" :value="m.id" />
        </el-select>
      </div>
      <el-input v-model="analysisGoal" type="textarea" :rows="3"
                placeholder="输入宏观分析目标，如：电商销售复盘分析 / 邵阳市经济复盘分析（系统将拆解为多步分析计划）" />
      <div style="margin-top: 12px; text-align: right;">
        <el-button type="primary" :loading="loading" @click="generatePlan">生成计划</el-button>
      </div>
    </el-card>

    <!-- 中间：计划拆解展示区 -->
    <el-card v-if="planResult" shadow="never" style="margin-bottom: 16px;">
      <template #header>
        <span>计划拆解</span>
        <el-tag v-if="planResult.id" size="small" style="margin-left: 8px;">计划 #{{ planResult.id }}</el-tag>
        <el-tag size="small" :type="statusTagType(planResult.status)" style="margin-left: 8px;">{{ statusName(planResult.status) }}</el-tag>
      </template>
      <h3 style="margin-top: 0; border-bottom: 1px solid #ebeef5; padding-bottom: 8px;">{{ planResult.title }}</h3>
      <div style="color: #606266; margin-bottom: 12px; font-size: 13px;">分析目标：{{ planResult.goal }}</div>
      <div v-for="step in planResult.steps || []" :key="step.stepNo"
           style="border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 14px; margin-bottom: 8px;">
        <div style="font-weight: 600;">{{ step.stepNo }}. {{ step.name }}
          <el-tag size="small" style="margin-left: 8px;">{{ step.chartType }}</el-tag>
          <el-tag size="small" :type="step.status === 'SUCCESS' ? 'success' : (step.status === 'FAILED' ? 'danger' : 'info')" style="margin-left: 6px;">{{ step.status }}</el-tag>
        </div>
        <div style="font-size: 13px; color: #606266; margin-top: 4px;">分析问题：{{ step.question }}</div>
        <div style="font-size: 13px; color: #909399; margin-top: 2px;">逻辑说明：{{ step.logic }}</div>
        <div v-if="step.error" style="font-size: 12px; color: #f56c6c; margin-top: 2px;">错误：{{ step.error }}</div>
      </div>
    </el-card>

    <!-- 底部：历史计划列表区 -->
    <el-card v-if="plans.length" shadow="never">
      <template #header>
        <span>历史计划列表</span>
        <el-button size="small" text type="primary" style="margin-left: 8px;" @click="fetchPlans">刷新</el-button>
      </template>
      <el-table :data="plans" size="small" border stripe>
        <el-table-column prop="title" label="计划标题" min-width="180" show-overflow-tooltip />
        <el-table-column label="数据集" width="130">
          <template #default="{ row }">{{ datasetName(row.datasetId) }}</template>
        </el-table-column>
        <el-table-column prop="goal" label="分析目标" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="150" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="goTrack(row)">执行追踪</el-button>
            <el-button size="small" type="danger" link @click="deletePlan(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { createAgentPlan, listAgentPlans, deleteAgentPlan } from "@/api/agentPlan";
import { listSessions, listDatasetOptions, listModelOptions } from "@/api/history";

const router = useRouter();
const analysisGoal = ref("");
const loading = ref(false);
const selectedSessionId = ref(null);
const selectedDatasetId = ref(null);
const selectedModelId = ref(null);
const sessionOptions = ref([]);
const datasetOptions = ref([]);
const modelOptions = ref([]);
const plans = ref([]);
const planResult = ref(null);

function datasetName(id) {
  if (!id) return "全库";
  const d = datasetOptions.value.find((x) => x.id === id);
  return d ? d.name : ("数据集 #" + id);
}
function statusName(status) {
  return { GENERATED: "已生成", EXECUTING: "执行中", DONE: "已完成", FAILED: "失败" }[status] || (status || "-");
}
function statusTagType(status) {
  return status === "DONE" ? "success" : (status === "FAILED" ? "danger" : (status === "EXECUTING" ? "warning" : "info"));
}

async function loadOptions() {
  const [s, d, m] = await Promise.allSettled([listSessions(undefined, undefined, 1, 1000), listDatasetOptions(), listModelOptions()]);
  if (s.status === "fulfilled" && s.value?.code === 200) sessionOptions.value = s.value.data?.rows || [];
  if (d.status === "fulfilled" && d.value?.code === 200) datasetOptions.value = d.value.data || [];
  if (m.status === "fulfilled" && m.value?.code === 200) modelOptions.value = m.value.data || [];
}

async function generatePlan() {
  if (!analysisGoal.value.trim()) {
    ElMessage.warning("请输入分析目标");
    return;
  }
  loading.value = true;
  try {
    const res = await createAgentPlan({
      goal: analysisGoal.value,
      sessionId: selectedSessionId.value || undefined,
      datasetId: selectedDatasetId.value || undefined,
      modelConfigId: selectedModelId.value || undefined,
    });
    if (res.code === 200) {
      planResult.value = res.data;
      ElMessage.success("计划生成成功");
      fetchPlans();
    } else {
      ElMessage.warning(res.message || "计划生成失败");
    }
  } catch (e) {
    // 错误提示已由 request.js 拦截器统一处理
  } finally {
    loading.value = false;
  }
}

async function fetchPlans() {
  const res = await listAgentPlans();
  if (res.code === 200) plans.value = res.data || [];
}

function goTrack(row) {
  router.push({ path: "/agent-track", query: { planId: row.id } });
}

async function deletePlan(row) {
  try {
    await ElMessageBox.confirm("确认删除该计划？其步骤结果与报告将一并删除。", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteAgentPlan(row.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      fetchPlans();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(() => {
  loadOptions();
  fetchPlans();
});
</script>