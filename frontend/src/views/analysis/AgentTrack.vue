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
        <el-table-column label="输出预览" min-width="240">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ preview(row.outputData) }}</span>
              <el-button size="small" type="primary" link @click="openDetail(row)">详情</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" title="步骤输出详情" width="760px" top="5vh">
      <pre style="max-height: 65vh; overflow: auto; background: #f5f7fa; padding: 12px; border-radius: 4px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; margin: 0;">{{ detailText }}</pre>
      <template #footer>
        <el-button type="primary" @click="copyDetail">复制</el-button>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
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
const detailVisible = ref(false);
const detailText = ref("");

function preview(data) {
  if (!data) return "-";
  const text = String(data);
  return text.length > 80 ? text.slice(0, 80) + "..." : text;
}

function openDetail(row) {
  let text = row.outputData || "";
  if (text) {
    try {
      const obj = JSON.parse(text);
      text = JSON.stringify(obj, null, 2);
    } catch (e) {
      // 非 JSON 内容按原文展示
    }
  }
  detailText.value = text || "-";
  detailVisible.value = true;
}

async function copyDetail() {
  try {
    await navigator.clipboard.writeText(detailText.value);
    ElMessage.success("已复制");
  } catch (e) {
    ElMessage.error("复制失败，请手动选择");
  }
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