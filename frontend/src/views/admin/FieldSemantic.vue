<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>字段语义列表</span>
          <el-button type="primary" size="small" @click="openCreate">新增字段</el-button>
        </div>
      </template>
      <el-tabs v-model="activeCategory" type="card" style="margin-bottom: 8px;">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane v-for="c in categories" :key="c.id" :name="String(c.id)" :label="c.name" />
        <el-tab-pane label="未分类" name="none" />
      </el-tabs>
      <el-table :data="filteredRows" v-loading="loading" border stripe size="small">
        <el-table-column prop="datasetName" label="数据集" min-width="130" />
        <el-table-column prop="tableName" label="表名" min-width="120" />
        <el-table-column prop="fieldName" label="字段名" min-width="110" />
        <el-table-column prop="fieldComment" label="字段说明" min-width="100" />
        <el-table-column prop="fieldType" label="字段类型" width="90" />
        <el-table-column prop="semanticType" label="语义类型" width="90" />
        <el-table-column prop="businessMeaning" label="业务含义" min-width="160" show-overflow-tooltip />
        <el-table-column label="可查询" width="80">
          <template #default="{ row }">{{ row.canQuery === 1 ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="可聚合" width="80">
          <template #default="{ row }">{{ row.canAgg === 1 ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑字段语义' : '新增字段语义'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="所属表" prop="tableId">
          <el-select v-model="form.tableId" placeholder="请选择数据表" style="width: 100%;">
            <el-option v-for="t in tables" :key="t.id" :label="(t.datasetName || '') + ' / ' + t.tableName" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="字段名" prop="fieldName">
          <el-input v-model="form.fieldName" placeholder="如：publish_date" />
        </el-form-item>
        <el-form-item label="字段说明" prop="fieldComment">
          <el-input v-model="form.fieldComment" placeholder="如：发布日期" />
        </el-form-item>
        <el-form-item label="字段类型" prop="fieldType">
          <el-select v-model="form.fieldType" placeholder="请选择字段类型" style="width: 100%;">
            <el-option v-for="t in fieldTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="语义类型" prop="semanticType">
          <el-select v-model="form.semanticType" placeholder="请选择语义类型" style="width: 100%;">
            <el-option v-for="t in semanticTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务含义" prop="businessMeaning">
          <el-input v-model="form.businessMeaning" type="textarea" :rows="2" placeholder="字段在业务中的含义和使用限制" />
        </el-form-item>
        <el-form-item label="可查询" prop="canQuery">
          <el-radio-group v-model="form.canQuery">
            <el-radio :label="1">是</el-radio>
            <el-radio :label="0">否</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="可聚合" prop="canAgg">
          <el-radio-group v-model="form.canAgg">
            <el-radio :label="1">是</el-radio>
            <el-radio :label="0">否</el-radio>
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
import { listFieldSemantics, createFieldSemantic, updateFieldSemantic, deleteFieldSemantic } from "@/api/metadata";
import { listDataTables, listCategories } from "@/api/metadata";

const fieldTypes = ["varchar", "decimal", "datetime", "int", "text"];
const semanticTypes = ["维度", "指标", "标识"];

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const categories = ref([]);
const activeCategory = ref("all");

const filteredRows = computed(() => {
  const a = activeCategory.value;
  return rows.value.filter((r) => {
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
const tables = ref([]);
const dialogVisible = ref(false);
const formRef = ref(null);

function defaultForm() {
  return {
    id: null,
    tableId: null,
    fieldName: "",
    fieldComment: "",
    fieldType: "varchar",
    semanticType: "维度",
    businessMeaning: "",
    canQuery: 1,
    canAgg: 0,
    sort: 0,
  };
}
const form = reactive(defaultForm());

const formRules = {
  tableId: [{ required: true, message: "请选择所属表", trigger: "change" }],
  fieldName: [{ required: true, message: "请输入字段名", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listFieldSemantics();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载字段失败");
  } finally {
    loading.value = false;
  }
}

async function fetchTables() {
  try {
    const res = await listDataTables();
    if (res.code === 200) tables.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载数据表失败");
  }
}

function openCreate() {
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    tableId: row.tableId,
    fieldName: row.fieldName,
    fieldComment: row.fieldComment,
    fieldType: row.fieldType || "varchar",
    semanticType: row.semanticType || "维度",
    businessMeaning: row.businessMeaning,
    canQuery: row.canQuery == null ? 1 : row.canQuery,
    canAgg: row.canAgg == null ? 0 : row.canAgg,
    sort: row.sort == null ? 0 : row.sort,
  });
  dialogVisible.value = true;
}

function payload() {
  return {
    tableId: form.tableId,
    fieldName: form.fieldName,
    fieldComment: form.fieldComment,
    fieldType: form.fieldType,
    semanticType: form.semanticType,
    businessMeaning: form.businessMeaning,
    canQuery: form.canQuery,
    canAgg: form.canAgg,
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
      res = await updateFieldSemantic(form.id, payload());
    } else {
      res = await createFieldSemantic(payload());
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
    await ElMessageBox.confirm("确定删除字段「" + row.fieldName + "」吗？", "删除确认", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteFieldSemantic(row.id);
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
  fetchTables();
  fetchList();
});
</script>
