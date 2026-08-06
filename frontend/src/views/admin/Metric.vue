<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>指标口径列表</span>
          <div>
            <el-input v-model="keyword" placeholder="搜索指标名称" clearable style="width: 200px; margin-right: 8px;" />
            <el-button type="primary" size="small" @click="openCreate">新增指标</el-button>
          </div>
        </div>
      </template>
      <el-tabs v-model="activeCategory" type="card" style="margin-bottom: 8px;">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane v-for="c in categories" :key="c.id" :name="String(c.id)" :label="c.name" />
        <el-tab-pane label="未分类" name="none" />
      </el-tabs>
      <el-table :data="filteredRows" v-loading="loading" border stripe size="small">
        <el-table-column prop="datasetName" label="数据集" min-width="130" />
        <el-table-column prop="name" label="指标名称" min-width="120" />
        <el-table-column prop="metricCode" label="指标编码" min-width="110" />
        <el-table-column prop="metricType" label="指标类型" width="90" />
        <el-table-column label="口径公式" min-width="180">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ row.calculationFormula }}</span>
              <el-button size="small" type="primary" link @click="detailRef.open(row.calculationFormula)">详情</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="业务含义" min-width="180">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ row.description }}</span>
              <el-button size="small" type="primary" link @click="detailRef.open(row.description)">详情</el-button>
            </div>
          </template>
        </el-table-column>
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
    </el-card>
    <TextDetailDialog ref="detailRef" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑指标口径' : '新增指标口径'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="所属数据集" prop="datasetId">
          <el-select v-model="form.datasetId" placeholder="请选择数据集" style="width: 100%;">
            <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="指标名称" prop="name">
          <el-input v-model="form.name" placeholder="如：发文量" />
        </el-form-item>
        <el-form-item label="指标编码" prop="metricCode">
          <el-input v-model="form.metricCode" placeholder="如：doc_count" />
        </el-form-item>
        <el-form-item label="指标类型" prop="metricType">
          <el-select v-model="form.metricType" placeholder="请选择指标类型" style="width: 100%;">
            <el-option label="基础指标" value="基础指标" />
            <el-option label="计算指标" value="计算指标" />
          </el-select>
        </el-form-item>
        <el-form-item label="口径公式" prop="calculationFormula">
          <el-input v-model="form.calculationFormula" placeholder="中文计算描述" />
        </el-form-item>
        <el-form-item label="SQL表达式" prop="sqlExpression">
          <el-input v-model="form.sqlExpression" type="textarea" :rows="3" placeholder="如：SELECT COUNT(*) FROM gov_info_record" />
        </el-form-item>
        <el-form-item label="业务含义" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="指标业务说明" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" />
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
import { listMetrics, createMetric, updateMetric, deleteMetric } from "@/api/metadata";
import { listDatasets, listCategories } from "@/api/metadata";
import TextDetailDialog from "@/components/TextDetailDialog.vue";
const detailRef = ref(null);

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const datasets = ref([]);
const keyword = ref("");
const categories = ref([]);
const activeCategory = ref("all");

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  const a = activeCategory.value;
  return rows.value.filter((r) => {
    if (kw && !(r.name || "").toLowerCase().includes(kw)) return false;
    if (!a || a === "all") return true;
    if (a === "none") return !r.categoryId;
    return String(r.categoryId) === a;
  });
});

async function fetchCategories() {
  try {
    const res = await listCategories();
    if (res.code === 200) categories.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载分类失败");
  }
}
const dialogVisible = ref(false);
const formRef = ref(null);


function defaultForm() {
  return {
    id: null,
    datasetId: null,
    name: "",
    metricCode: "",
    metricType: "基础指标",
    calculationFormula: "",
    sqlExpression: "",
    description: "",
    status: 1,
    sort: 0,
  };
}
const form = reactive(defaultForm());

const formRules = {
  name: [{ required: true, message: "请输入指标名称", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listMetrics();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载指标失败");
  } finally {
    loading.value = false;
  }
}

async function fetchDatasets() {
  try {
    const res = await listDatasets();
    if (res.code === 200) datasets.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载数据集失败");
  }
}

function openCreate() {
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    datasetId: row.datasetId,
    name: row.name,
    metricCode: row.metricCode,
    metricType: row.metricType || "基础指标",
    calculationFormula: row.calculationFormula,
    sqlExpression: row.sqlExpression,
    description: row.description,
    status: row.status == null ? 1 : row.status,
    sort: row.sort == null ? 0 : row.sort,
  });
  dialogVisible.value = true;
}

function payload() {
  return {
    datasetId: form.datasetId,
    name: form.name,
    metricCode: form.metricCode,
    metricType: form.metricType,
    calculationFormula: form.calculationFormula,
    sqlExpression: form.sqlExpression,
    description: form.description,
    status: form.status,
    sort: form.sort,
  };
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    let res;
    if (form.id) {
      res = await updateMetric(form.id, payload());
    } else {
      res = await createMetric(payload());
    }
    if (res.code === 200) {
      ElMessage.success(form.id ? "更新成功" : "新增成功");
      dialogVisible.value = false;
      await fetchList();
    }
  } catch (e) {
    ElMessage.error("保存失败");
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm("确定删除指标「" + row.name + "」吗？", "删除确认", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteMetric(row.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      await fetchList();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(() => {
  fetchCategories();
  fetchDatasets();
  fetchList();
});
</script>
