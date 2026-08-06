<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>数据集列表</span>
          <div>
            <el-input v-model="keyword" placeholder="搜索名称" clearable style="width: 200px; margin-right: 8px;" />
            <el-button type="primary" size="small" @click="openCreate">新增数据集</el-button>
          <el-button size="small" @click="manageVisible = true">分类管理</el-button>
          </div>
        </div>
      </template>
      <el-tabs v-model="activeCategory" type="card" style="margin-bottom: 8px;">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane v-for="c in categories" :key="c.id" :name="String(c.id)" :label="c.name" />
        <el-tab-pane label="未分类" name="none" />
      </el-tabs>
      <el-table :data="filteredRows" v-loading="loading" border stripe size="small">
        <el-table-column prop="name" label="数据集名称" min-width="150" />
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.categoryName" size="small" :style="{ color: '#fff', backgroundColor: categoryColor(row.categoryId) }">{{ row.categoryName }}</el-tag>
            <span v-else style="color: #909399;">未分类</span>
          </template>
        </el-table-column>
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
        <el-form-item label="数据分类" prop="categoryId">
          <el-select v-model="form.categoryId" clearable placeholder="未分类（可选）" style="width: 100%;">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
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
    <el-dialog v-model="manageVisible" title="分类管理" width="620px" destroy-on-close>
      <el-form inline>
        <el-form-item label="名称"><el-input v-model="catForm.name" placeholder="如：政务数据" style="width: 140px" /></el-form-item>
        <el-form-item label="颜色"><el-input v-model="catForm.color" placeholder="#409eff" style="width: 110px" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="catForm.sort" :min="0" style="width: 110px" /></el-form-item>
        <el-form-item><el-button type="primary" @click="saveCategory">新增分类</el-button></el-form-item>
      </el-form>
      <el-table :data="categories" border stripe size="small">
        <el-table-column label="名称">
          <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
        </el-table-column>
        <el-table-column label="颜色" width="150">
          <template #default="{ row }"><el-input v-model="row.color" size="small" placeholder="#409eff" /></template>
        </el-table-column>
        <el-table-column label="排序" width="110">
          <template #default="{ row }"><el-input-number v-model="row.sort" :min="0" size="small" controls-position="right" style="width: 100%" /></template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="updateCategoryRow(row)">保存</el-button>
            <el-button size="small" type="danger" @click="removeCategoryRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { listDatasets, createDataset, updateDataset, deleteDataset, listCategories, createCategory, updateCategory, deleteCategory } from "@/api/metadata";

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const keyword = ref("");
const categories = ref([]);
const activeCategory = ref("all");
const manageVisible = ref(false);
const catForm = reactive({ name: "", color: "#409eff", sort: 0 });
const dialogVisible = ref(false);
const formRef = ref(null);

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return rows.value.filter((r) => {
    if (kw && !(r.name || "").toLowerCase().includes(kw)) return false;
    return categoryMatches(r);
  });
});

function defaultForm() {
  return {
    id: null,
    name: "",
    businessScene: "",
    categoryId: null,
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
    categoryId: row.categoryId || null,
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
    categoryId: form.categoryId || null,
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

async function fetchCategories() {
  try {
    const res = await listCategories();
    if (res.code === 200) categories.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载分类失败");
  }
}

function categoryMatches(row) {
  const a = activeCategory.value;
  if (!a || a === "all") return true;
  if (a === "none") return !row.categoryId;
  return String(row.categoryId) === a;
}

function categoryColor(categoryId) {
  const found = categories.value.find((c) => c.id === categoryId);
  return found && found.color ? found.color : "#909399";
}

async function saveCategory() {
  if (!catForm.name || !catForm.name.trim()) {
    ElMessage.warning("请输入分类名称");
    return;
  }
  try {
    const res = await createCategory({ name: catForm.name.trim(), color: catForm.color || "#409eff", sort: catForm.sort || 0 });
    if (res.code === 200) {
      ElMessage.success("新增分类成功");
      catForm.name = "";
      catForm.color = "#409eff";
      catForm.sort = 0;
      await fetchCategories();
    }
  } catch (e) {
    ElMessage.error("新增分类失败");
  }
}

async function updateCategoryRow(row) {
  if (!row.name || !row.name.trim()) {
    ElMessage.warning("分类名称不能为空");
    return;
  }
  try {
    const res = await updateCategory(row.id, { name: row.name.trim(), color: row.color || "#409eff", sort: row.sort || 0 });
    if (res.code === 200) {
      ElMessage.success("保存成功");
      await fetchCategories();
    }
  } catch (e) {
    ElMessage.error("保存失败，请检查是否重名");
  }
}

async function removeCategoryRow(row) {
  try {
    await ElMessageBox.confirm("确定删除分类「" + row.name + "」吗？相关数据集将回到未分类。", "删除确认", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteCategory(row.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      await fetchCategories();
    }
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(() => {
  fetchList();
  fetchCategories();
});
</script>
