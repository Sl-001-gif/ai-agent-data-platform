<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>会话列表</span>
              <el-button type="primary" size="small" @click="router.push('/ai-analysis')">新建会话</el-button>
            </div>
          </template>
          <el-input v-model="keyword" placeholder="搜索会话标题" clearable size="small" style="margin-bottom: 10px;" @keyup.enter="fetchSessions" />
          <div v-loading="loading">
            <div v-for="s in sessions" :key="s.id" class="session-item" :class="{ active: currentId === s.id }" @click="selectSession(s)">
              <div style="font-weight: 600;">{{ s.title }}</div>
              <div style="font-size: 12px; color: #909399; margin-top: 4px;">{{ s.createTime || "-" }}</div>
              <el-button size="small" type="danger" text @click.stop="handleDelete(s)">删除</el-button>
            </div>
            <el-empty v-if="!loading && sessions.length === 0" description="暂无会话" />
          </div>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card v-if="current">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>{{ current.title }}</span>
              <div>
                <el-tag size="small" :type="current.status === 'ACTIVE' ? 'success' : 'info'">{{ current.status }}</el-tag>
                <el-button size="small" type="primary" style="margin-left: 8px;" @click="continueAnalysis">继续分析</el-button>
              </div>
            </div>
          </template>
          <el-timeline v-loading="stepsLoading">
            <el-timeline-item v-for="step in steps" :key="step.id" :timestamp="step.stepType + '（order ' + step.stepOrder + '）'" placement="top">
              <el-tag size="small" :type="step.status === 'SUCCESS' ? 'success' : 'danger'">{{ step.status || "-" }}</el-tag>
              <span style="margin-left: 8px; color: #909399;">{{ step.durationMs != null ? step.durationMs + " ms" : "" }}</span>
              <div v-if="step.errorMessage" style="color: #f56c6c; font-size: 12px; margin-top: 4px;">{{ step.errorMessage }}</div>
            </el-timeline-item>
            <el-empty v-if="!stepsLoading && steps.length === 0" description="该会话暂无步骤，点击「继续分析」重新执行" />
          </el-timeline>
        </el-card>
        <el-card v-else>
          <el-empty description="从左侧选择一个会话查看详情" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { listSessions, listSessionSteps, deleteSession } from "@/api/history";

const router = useRouter();
const sessions = ref([]);
const steps = ref([]);
const keyword = ref("");
const loading = ref(false);
const stepsLoading = ref(false);
const currentId = ref(null);
const current = ref(null);

async function fetchSessions() {
  loading.value = true;
  try {
    const res = await listSessions(keyword.value.trim() || undefined);
    if (res.code === 200) sessions.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载会话失败");
  } finally {
    loading.value = false;
  }
}

async function selectSession(session) {
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

function continueAnalysis() {
  router.push("/ai-analysis");
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
        currentId.value = null;
        current.value = null;
        steps.value = [];
      }
      await fetchSessions();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(fetchSessions);
</script>

<style scoped>
.session-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  margin-bottom: 8px;
  cursor: pointer;
  position: relative;
}
.session-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.session-item .el-button {
  position: absolute;
  right: 6px;
  top: 6px;
}
</style>
