<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>分析配置管理</span>
          <el-button type="primary" size="small" @click="openCreate">新增{{ activeTab === "rule" ? "意图规则" : "计划配置" }}</el-button>
        </div>
      </template>
      <el-tabs v-model="activeTab" @tab-change="fetchList">
        <el-tab-pane label="意图规则" name="rule">
          <el-table :data="rules" v-loading="ruleLoading" border stripe size="small">
            <el-table-column prop="intentCode" label="意图编码" min-width="130" />
            <el-table-column prop="intentName" label="意图名称" min-width="110" />
            <el-table-column prop="keywords" label="关键词（逗号分隔）" min-width="260" show-overflow-tooltip />
            <el-table-column prop="priority" label="优先级" width="80" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? "启用" : "停用" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="计划配置" name="plan">
          <el-table :data="plans" v-loading="planLoading" border stripe size="small">
            <el-table-column prop="intentCode" label="意图编码" min-width="130" />
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag :type="row.isGov === 1 ? 'warning' : 'primary'">{{ row.isGov === 1 ? "政务" : "普通" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="tableName" label="目标表" min-width="150" />
            <el-table-column prop="metrics" label="指标" min-width="140" show-overflow-tooltip />
            <el-table-column prop="dimensions" label="维度" min-width="120" show-overflow-tooltip />
            <el-table-column prop="chartType" label="图表" width="80" />
            <el-table-column prop="timeRange" label="时间范围" width="100" />
            <el-table-column label="规则 SQL 模板" min-width="240">
              <template #default="{ row }">
                <div style="display: flex; align-items: center; gap: 6px;">
                  <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ row.sqlTemplate }}</span>
                  <el-button size="small" type="primary" link @click="detailRef.open(row.sqlTemplate)">详情</el-button>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <TextDetailDialog ref="detailRef" />

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="620px" destroy-on-close>
      <el-form v-if="activeTab === 'rule'" ref="ruleFormRef" :model="ruleForm" :rules="ruleRules" label-width="100px">
        <el-form-item label="意图编码" prop="intentCode">
          <el-input v-model="ruleForm.intentCode" placeholder="如：SALES_TREND" />
        </el-form-item>
        <el-form-item label="意图名称" prop="intentName">
          <el-input v-model="ruleForm.intentName" placeholder="如：销售趋势" />
        </el-form-item>
        <el-form-item label="关键词" prop="keywords">
          <el-input v-model="ruleForm.keywords" type="textarea" :rows="3" placeholder="逗号分隔，如：销售,趋势,增长" />
        </el-form-item>
        <el-form-item label="优先级" prop="priority">
          <el-input-number v-model="ruleForm.priority" :min="0" />
          <span style="margin-left: 8px; color: #909399; font-size: 12px;">越小越先匹配</span>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="ruleForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-form v-else ref="planFormRef" :model="planForm" :rules="planRules" label-width="100px">
        <el-form-item label="意图编码" prop="intentCode">
          <el-input v-model="planForm.intentCode" placeholder="如：SALES_TREND / GENERAL" />
        </el-form-item>
        <el-form-item label="类型" prop="isGov">
          <el-radio-group v-model="planForm.isGov">
            <el-radio :label="0">普通</el-radio>
            <el-radio :label="1">政务</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="目标表" prop="tableName">
          <el-input v-model="planForm.tableName" placeholder="如：order_info / GOV_INFO_RECORD" />
        </el-form-item>
        <el-form-item label="指标" prop="metrics">
          <el-input v-model="planForm.metrics" placeholder="逗号分隔，如：发文量,日均发文量" />
        </el-form-item>
        <el-form-item label="维度" prop="dimensions">
          <el-input v-model="planForm.dimensions" placeholder="逗号分隔，如：公开单位" />
        </el-form-item>
        <el-form-item label="图表类型" prop="chartType">
          <el-select v-model="planForm.chartType" style="width: 200px;">
            <el-option label="折线图 line" value="line" />
            <el-option label="柱状图 bar" value="bar" />
            <el-option label="饼图 pie" value="pie" />
            <el-option label="表格 table" value="table" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围" prop="timeRange">
          <el-input v-model="planForm.timeRange" placeholder="如：近30天 / 近3年" />
        </el-form-item>
        <el-form-item label="SQL 模板" prop="sqlTemplate">
          <el-input v-model="planForm.sqlTemplate" type="textarea" :rows="3" placeholder="规则 SQL，{timeRange} 为时间范围占位" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="planForm.sort" :min="0" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="planForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  listIntentRules, createIntentRule, updateIntentRule, deleteIntentRule,
  listPlanConfigs, createPlanConfig, updatePlanConfig, deletePlanConfig,
} from "@/api/analysisConfig";
import TextDetailDialog from "@/components/TextDetailDialog.vue";
const detailRef = ref(null);

const activeTab = ref("rule");
const rules = ref([]);
const plans = ref([]);
const ruleLoading = ref(false);
const planLoading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const ruleFormRef = ref(null);
const planFormRef = ref(null);

const dialogTitle = computed(() => {
  const label = activeTab.value === "rule" ? "意图规则" : "计划配置";
  const editing = activeTab.value === "rule" ? ruleForm.id : planForm.id;
  return (editing ? "编辑" : "新增") + label;
});

function defaultRuleForm() {
  return { id: null, intentCode: "", intentName: "", keywords: "", priority: 0, status: 1 };
}
const ruleForm = reactive(defaultRuleForm());

function defaultPlanForm() {
  return {
    id: null, intentCode: "", isGov: 0, tableName: "", metrics: "", dimensions: "",
    chartType: "table", timeRange: "近30天", sqlTemplate: "", sort: 0, status: 1,
  };
}
const planForm = reactive(defaultPlanForm());

const ruleRules = {
  intentCode: [{ required: true, message: "请输入意图编码", trigger: "blur" }],
  intentName: [{ required: true, message: "请输入意图名称", trigger: "blur" }],
  keywords: [{ required: true, message: "请输入关键词", trigger: "blur" }],
};
const planRules = {
  intentCode: [{ required: true, message: "请输入意图编码", trigger: "blur" }],
  tableName: [{ required: true, message: "请输入目标表", trigger: "blur" }],
};

async function fetchList() {
  if (activeTab.value === "rule") {
    ruleLoading.value = true;
    try {
      const res = await listIntentRules();
      if (res.code === 200) rules.value = res.data || [];
    } catch (e) {
      ElMessage.error("加载意图规则失败");
    } finally {
      ruleLoading.value = false;
    }
  } else {
    planLoading.value = true;
    try {
      const res = await listPlanConfigs();
      if (res.code === 200) plans.value = res.data || [];
    } catch (e) {
      ElMessage.error("加载计划配置失败");
    } finally {
      planLoading.value = false;
    }
  }
}

function openCreate() {
  if (activeTab.value === "rule") {
    Object.assign(ruleForm, defaultRuleForm());
  } else {
    Object.assign(planForm, defaultPlanForm());
  }
  dialogVisible.value = true;
}

function openEdit(row) {
  if (activeTab.value === "rule") {
    Object.assign(ruleForm, {
      id: row.id, intentCode: row.intentCode, intentName: row.intentName,
      keywords: row.keywords, priority: row.priority == null ? 0 : row.priority,
      status: row.status == null ? 1 : row.status,
    });
  } else {
    Object.assign(planForm, {
      id: row.id, intentCode: row.intentCode, isGov: row.isGov == null ? 0 : row.isGov,
      tableName: row.tableName, metrics: row.metrics || "", dimensions: row.dimensions || "",
      chartType: row.chartType || "table", timeRange: row.timeRange || "近30天",
      sqlTemplate: row.sqlTemplate || "", sort: row.sort == null ? 0 : row.sort,
      status: row.status == null ? 1 : row.status,
    });
  }
  dialogVisible.value = true;
}

async function handleSave() {
  const isRule = activeTab.value === "rule";
  const formRef = isRule ? ruleFormRef : planFormRef;
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    saving.value = true;
    try {
      const payload = isRule ? { ...ruleForm } : { ...planForm };
      let res;
      if (isRule) {
        res = payload.id ? await updateIntentRule(payload.id, payload) : await createIntentRule(payload);
      } else {
        res = payload.id ? await updatePlanConfig(payload.id, payload) : await createPlanConfig(payload);
      }
      if (res.code === 200) {
        ElMessage.success("保存成功");
        dialogVisible.value = false;
        await fetchList();
      }
    } catch (e) {
      ElMessage.error("保存失败");
    } finally {
      saving.value = false;
    }
  });
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm("确认删除该配置？删除后规则引擎将按剩余配置/内置回退生效。", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    if (activeTab.value === "rule") {
      await deleteIntentRule(row.id);
    } else {
      await deletePlanConfig(row.id);
    }
    ElMessage.success("删除成功");
    await fetchList();
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(fetchList);
</script>