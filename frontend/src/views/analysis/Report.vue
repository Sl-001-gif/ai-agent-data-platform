<template>
  <div>
    <el-card>
      <template #header>
        <span>分析报告历史</span>
      </template>
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="报告标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DONE' ? 'success' : 'info'">{{ row.status || "-" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="生成时间" min-width="160" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="viewReport(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="报告详情" width="720px" destroy-on-close>
      <h3 style="margin-top: 0;">{{ detail.title }}</h3>
      <pre style="white-space: pre-wrap; word-break: break-word; background: #f5f7fa; padding: 12px; border-radius: 6px;">{{ detail.content }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { listReports, getReport } from "@/api/history";

const loading = ref(false);
const rows = ref([]);
const dialogVisible = ref(false);
const detail = ref({});

async function fetchList() {
  loading.value = true;
  try {
    const res = await listReports();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载报告失败");
  } finally {
    loading.value = false;
  }
}

async function viewReport(row) {
  try {
    const res = await getReport(row.id);
    if (res.code === 200) {
      detail.value = res.data || {};
      dialogVisible.value = true;
    }
  } catch (e) {
    ElMessage.error("加载报告详情失败");
  }
}

onMounted(fetchList);
</script>