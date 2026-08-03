<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>数据集列表</span>
          <div>
            <el-input v-model="keyword" placeholder="搜索名称" clearable style="width: 200px; margin-right: 8px;" />
            <el-button type="primary" size="small" @click="openCreate">新增数据集</el-button>
          </div>
        </div>
      </template>
      <el-table :data="filteredRows" v-loading="loading" border stripe size="small">
        <el-table-column prop="name" label="数据集名称" min-width="150" />
        <el-table-column prop="businessScene" label="业务场景" min-width="110" />
        <el-table-column prop="tableName" label="数据库表名" min-width="140" />
        <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? "启用" : "停用" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column label="更新时间" min-width="150">
          <template #default="{ row }">{{ row.updateTime || "-" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据集' : '新增数据集'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="数据集名称" prop="name">
          <el-input v-model="form.name" placeholder="如：邵阳政务信息公开数据" />
        </el-form-item>
        <el-form-item label="业务场景" prop="businessScene">
          <el-input v-model="form.businessScene" placeholder="如：政务公开" />
        </el-form-item>
        <el-form-item label="数据库表名" prop="tableName">
          <el-input v-model="form.tableName" placeholder="如：gov_info_record" />
        </el-form-item>
        <el-form-item label="说明" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="数据集说明" />
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
import { listDatasets, createDataset, updateDataset, deleteDataset } from "@/api/metadata";

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const keyword = ref("");
const dialogVisible = ref(false);
const formRef = ref(null);

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows.value;
  return rows.value.filter((r) => (r.name || "").toLowerCase().includes(kw));
});

function defaultForm() {
  return {
    id: null,
    name: "",
    businessScene: "",
    tableName: "",
    description: "",
    status: 1,
    sort: 0,
    dbType: "MYSQL",
    dbHost: "",
    dbPort: 3306,
    dbName: "",
    dbUsername: "",
    dbPassword: "",
  };
}
const form = reactive(defaultForm());

const formRules = {
  name: [{ required: true, message: "请输入数据集名称", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listDatasets();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载数据集失败");
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    businessScene: row.businessScene,
    tableName: row.tableName,
    description: row.description,
    status: row.status == null ? 1 : row.status,
    sort: row.sort == null ? 0 : row.sort,
    dbType: row.dbType || "MYSQL",
    dbHost: row.dbHost || "",
    dbPort: row.dbPort || 3306,
    dbName: row.dbName || "",
    dbUsername: row.dbUsername || "",
    dbPassword: row.dbPassword || "",
  });
  dialogVisible.value = true;
}

function payload() {
  return {
    name: form.name,
    businessScene: form.businessScene,
    tableName: form.tableName,
    description: form.description,
    status: form.status,
    sort: form.sort,
    dbType: form.dbType,
    dbHost: form.dbHost,
    dbPort: form.dbPort,
    dbName: form.dbName,
    dbUsername: form.dbUsername,
    dbPassword: form.dbPassword,
  };
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    let res;
    if (form.id) {
      res = await updateDataset(form.id, payload());
    } else {
      res = await createDataset(payload());
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
    await ElMessageBox.confirm("确定删除数据集「" + row.name + "」吗？", "删除确认", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteDataset(row.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      await fetchList();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(fetchList);
</script>
