<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>Prompt 模板管理</span>
          <el-button type="primary" size="small" @click="openCreate">新增模板</el-button>
        </div>
      </template>
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column prop="name" label="模板名称" min-width="150" />
        <el-table-column prop="type" label="类型" width="110">
          <template #default="{ row }">
            <el-tag>{{ row.type || "-" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模板内容" min-width="320">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ row.content }}</span>
              <el-button size="small" type="primary" link @click="detailRef.open(row.content)">详情</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="variables" label="变量" width="180" show-overflow-tooltip />
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column prop="version" label="版本" width="70" />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑模板' : '新增模板'" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="模板名称" prop="name">
          <el-input v-model="form.name" placeholder="如：SQL 生成基线" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" style="width: 200px;">
            <el-option label="INTENT 意图识别" value="INTENT" />
            <el-option label="SQL 生成" value="SQL" />
            <el-option label="CHART 图表推荐" value="CHART" />
            <el-option label="INTERPRET 数据解读" value="INTERPRET" />
            <el-option label="RECOMMEND 推荐追问" value="RECOMMEND" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板内容" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="系统提示词内容" />
        </el-form-item>
        <el-form-item label="变量" prop="variables">
          <el-input v-model="form.variables" placeholder="逗号分隔，如：datasetSchema,userQuestion,originSQL" />
        </el-form-item>
        <el-form-item label="版本" prop="version">
          <el-input-number v-model="form.version" :min="1" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
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
import { ref, reactive, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { listPrompts, createPrompt, updatePrompt, deletePrompt } from "@/api/aiConfig";
import TextDetailDialog from "@/components/TextDetailDialog.vue";
const detailRef = ref(null);

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const dialogVisible = ref(false);
const formRef = ref(null);

function defaultForm() {
  return { id: null, name: "", type: "SQL", content: "", version: 1, status: 1, variables: "", sort: 0 };
}
const form = reactive(defaultForm());

const formRules = {
  name: [{ required: true, message: "请输入模板名称", trigger: "blur" }],
  content: [{ required: true, message: "请输入模板内容", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listPrompts();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载模板失败");
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
    type: row.type || "SQL",
    content: row.content || "",
    variables: row.variables || "",
    version: row.version || 1,
    sort: row.sort == null ? 0 : row.sort,
    status: row.status == null ? 1 : row.status,
  });
  dialogVisible.value = true;
}

async function handleSave() {
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    saving.value = true;
    try {
      const payload = { ...form };
      const res = payload.id ? await updatePrompt(payload.id, payload) : await createPrompt(payload);
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
    await ElMessageBox.confirm("确认删除该模板？", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    await deletePrompt(row.id);
    ElMessage.success("删除成功");
    await fetchList();
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(fetchList);
</script>