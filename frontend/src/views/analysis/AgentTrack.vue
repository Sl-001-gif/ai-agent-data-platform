<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>Agent 执行追踪</span>
          <el-select v-model="selectedId" placeholder="选择分析计划" style="width: 300px;" @change="loadSteps">
            <el-option v-for="s in sessions" :key="s.id" :label="s.title" :value="s.id" />
          </el-select>
        </div>
      </template>
      <el-table :data="steps" v-loading="loading" border stripe size="small">
        <el-table-column prop="stepOrder" label="序号" width="60" />
        <el-table-column prop="stepType" label="步骤类型" min-width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status || "-" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
        <el-table-column label="输出预览" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ preview(row.outputData) }}</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { listSessions, listSessionSteps } from "@/api/history";

const sessions = ref([]);
const steps = ref([]);
const selectedId = ref(null);
const loading = ref(false);

function preview(data) {
  if (!data) return "-";
  const text = String(data);
  return text.length > 80 ? text.slice(0, 80) + "..." : text;
}

async function fetchSessions() {
  try {
    const res = await listSessions();
    if (res.code === 200) sessions.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载计划失败");
  }
}

async function loadSteps() {
  if (!selectedId.value) return;
  loading.value = true;
  try {
    const res = await listSessionSteps(selectedId.value);
    if (res.code === 200) steps.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载步骤失败");
  } finally {
    loading.value = false;
  }
}

onMounted(fetchSessions);
</script>