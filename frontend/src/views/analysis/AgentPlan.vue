<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>分析计划</span>
              <el-button type="primary" size="small" @click="router.push('/ai-analysis')">新建计划</el-button>
            </div>
          </template>
          <el-input v-model="keyword" placeholder="搜索计划" clearable size="small" style="margin-bottom: 10px;" @keyup.enter="fetchPlans" />
          <div v-loading="loading">
            <div v-for="p in plans" :key="p.id" class="plan-item" :class="{ active: currentId === p.id }" @click="selectPlan(p)">
              <div style="font-weight: 600;">{{ p.title }}</div>
              <div style="font-size: 12px; color: #909399; margin-top: 4px;">{{ p.createTime || "-" }}</div>
            </div>
            <el-empty v-if="!loading && plans.length === 0" description="暂无计划" />
          </div>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card v-if="current">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>{{ current.title }}</span>
              <div>
                <el-tag size="small">{{ stepCount }} 个步骤</el-tag>
                <el-button size="small" type="primary" style="margin-left: 8px;" @click="router.push('/agent-track')">执行追踪</el-button>
              </div>
            </div>
          </template>
          <el-table :data="steps" v-loading="stepsLoading" border stripe size="small">
            <el-table-column prop="stepOrder" label="序号" width="60" />
            <el-table-column prop="stepType" label="步骤" min-width="110" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status || "-" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
            <el-table-column label="错误信息" min-width="180">
              <template #default="{ row }">
                <div style="display: flex; align-items: center; gap: 6px;">
                  <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ row.errorMessage }}</span>
                  <el-button v-if="row.errorMessage" size="small" type="primary" link @click="detailRef.open(row.errorMessage)">详情</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
        <el-card v-else>
          <el-empty description="从左侧选择一个计划查看步骤" />
        </el-card>
      </el-col>
    </el-row>
    <TextDetailDialog ref="detailRef" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { listSessions, listSessionSteps } from "@/api/history";
import TextDetailDialog from "@/components/TextDetailDialog.vue";
const detailRef = ref(null);

const router = useRouter();
const plans = ref([]);
const steps = ref([]);
const keyword = ref("");
const loading = ref(false);
const stepsLoading = ref(false);
const currentId = ref(null);
const current = ref(null);

const stepCount = computed(() => steps.value.length);

async function fetchPlans() {
  loading.value = true;
  try {
    const res = await listSessions(keyword.value.trim() || undefined);
    if (res.code === 200) plans.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载计划失败");
  } finally {
    loading.value = false;
  }
}

async function selectPlan(plan) {
  currentId.value = plan.id;
  current.value = plan;
  stepsLoading.value = true;
  try {
    const res = await listSessionSteps(plan.id);
    if (res.code === 200) steps.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载步骤失败");
  } finally {
    stepsLoading.value = false;
  }
}

onMounted(fetchPlans);
</script>

<style scoped>
.plan-item {
  padding: 10px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  margin-bottom: 8px;
  cursor: pointer;
}
.plan-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}
</style>