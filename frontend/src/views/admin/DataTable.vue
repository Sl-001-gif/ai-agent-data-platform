<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>数据表列表</span>
          <el-button type="primary" size="small" @click="openCreate">新增数据表</el-button>
        </div>
      </template>
      <el-tabs v-model="activeCategory" type="card" style="margin-bottom: 8px;">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane v-for="c in categories" :key="c.id" :name="String(c.id)" :label="c.name" />
        <el-tab-pane label="未分类" name="none" />
      </el-tabs>
      <el-table :data="pagedRows" v-loading="loading" border stripe size="small">
        <el-table-column prop="datasetName" label="所属数据集" min-width="150" />
        <el-table-column prop="tableName" label="表名" min-width="140" />
        <el-table-column prop="tableComment" label="表说明" min-width="130" />
        <el-table-column prop="relationDesc" label="关系说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? "启用" : "停用" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="margin-top: 12px; display: flex; justify-content: flex-end;"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="filteredRows.length"
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据表' : '新增数据表'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="所属数据集" prop="datasetId">
          <el-select v-model="form.datasetId" placeholder="请选择数据集" style="width: 100%;">
            <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="表名" prop="tableName">
          <el-input v-model="form.tableName" placeholder="如：gov_info_record" />
        </el-form-item>
        <el-form-item label="表说明" prop="tableComment">
          <el-input v-model="form.tableComment" placeholder="如：政府信息公开记录" />
        </el-form-item>
        <el-form-item label="关系说明" prop="relationDesc">
          <el-input v-model="form.relationDesc" type="textarea" :rows="2" placeholder="该表与其他表的关联关系" />
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
import { ref, reactive, computed, onMounted, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { listDataTables, createDataTable, updateDataTable, deleteDataTable } from "@/api/metadata";
import { listDatasets, listCategories } from "@/api/metadata";

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const datasets = ref([]);
const categories = ref([]);
const activeCategory = ref("all");
const page = ref(1);
const pageSize = ref(10);

const filteredRows = computed(() => {
  const a = activeCategory.value;
  return rows.value.filter((r) => {
    if (!a || a === "all") return true;
    if (a === "none") return !r.categoryId;
    return String(r.categoryId) === a;
  });
});
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value;
  return filteredRows.value.slice(start, start + pageSize.value);
});

watch([activeCategory], () => { page.value = 1; });

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
  return { id: null, datasetId: null, tableName: "", tableComment: "", relationDesc: "", status: 1, sort: 0 };
}
const form = reactive(defaultForm());

const formRules = {
  datasetId: [{ required: true, message: "请选择所属数据集", trigger: "change" }],
  tableName: [{ required: true, message: "请输入表名", trigger: "blur" }],
  tableComment: [{ required: true, message: "请输入表说明", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listDataTables();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载数据表失败");
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
    tableName: row.tableName,
    tableComment: row.tableComment,
    relationDesc: row.relationDesc,
    status: row.status == null ? 1 : row.status,
    sort: row.sort == null ? 0 : row.sort,
  });
  dialogVisible.value = true;
}

function payload() {
  return {
    datasetId: form.datasetId,
    tableName: form.tableName,
    tableComment: form.tableComment,
    relationDesc: form.relationDesc,
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
      res = await updateDataTable(form.id, payload());
    } else {
      res = await createDataTable(payload());
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
    await ElMessageBox.confirm("确定删除数据表「" + row.tableName + "」吗？", "删除确认", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteDataTable(row.id);
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
