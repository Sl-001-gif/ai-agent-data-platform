<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>AI 模型配置</span>
          <el-button type="primary" size="small" @click="openCreate">新增模型配置</el-button>
        </div>
      </template>
      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="API Key 不入库：接口调用时优先读取环境变量 AI_API_KEY；这里仅维护模型名 / 地址 / 用途。" />
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column prop="name" label="配置名称" min-width="150" />
        <el-table-column prop="modelName" label="模型名" min-width="140" />
        <el-table-column prop="endpoint" label="接口地址" min-width="200" />
        <el-table-column prop="maxTokens" label="最大 Token" width="100" />
        <el-table-column prop="temperature" label="温度" width="80" />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑模型配置' : '新增模型配置'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-form-item label="配置名称" prop="name">
          <el-input v-model="form.name" placeholder="含 text/sql/report 关键字可被按用途路由" />
        </el-form-item>
        <el-form-item label="模型名" prop="modelName">
          <el-input v-model="form.modelName" placeholder="如：deepseek-chat" />
        </el-form-item>
        <el-form-item label="接口地址" prop="endpoint">
          <el-input v-model="form.endpoint" placeholder="如：https://api.deepseek.com/v1" />
        </el-form-item>
        <el-form-item label="最大 Token" prop="maxTokens">
          <el-input-number v-model="form.maxTokens" :min="1" :step="512" />
        </el-form-item>
        <el-form-item label="温度" prop="temperature">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
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
import { listAiModels, createAiModel, updateAiModel, deleteAiModel } from "@/api/aiConfig";

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const dialogVisible = ref(false);
const formRef = ref(null);

function defaultForm() {
  return { id: null, name: "", modelName: "", endpoint: "https://api.deepseek.com/v1", maxTokens: 2048, temperature: 0.2, status: 1 };
}
const form = reactive(defaultForm());

const formRules = {
  name: [{ required: true, message: "请输入配置名称", trigger: "blur" }],
  modelName: [{ required: true, message: "请输入模型名", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listAiModels();
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载模型配置失败");
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
    modelName: row.modelName,
    endpoint: row.endpoint || "https://api.deepseek.com/v1",
    maxTokens: row.maxTokens || 2048,
    temperature: row.temperature == null ? 0.2 : row.temperature,
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
      const res = payload.id ? await updateAiModel(payload.id, payload) : await createAiModel(payload);
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
    await ElMessageBox.confirm("确认删除该模型配置？", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    await deleteAiModel(row.id);
    ElMessage.success("删除成功");
    await fetchList();
  } catch (e) {
    ElMessage.error("删除失败");
  }
}

onMounted(fetchList);
</script>