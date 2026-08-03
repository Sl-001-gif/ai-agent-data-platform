<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>用户列表</span>
          <div>
            <el-input v-model="keyword" placeholder="请输入账号名" clearable style="width: 200px; margin-right: 8px;" @keyup.enter="fetchList" />
            <el-button type="primary" size="small" @click="openCreate">新增</el-button>
          </div>
        </div>
      </template>
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column prop="username" label="账号" min-width="130" />
        <el-table-column prop="nickname" label="姓名" min-width="110" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'primary'">{{ row.role === "ADMIN" ? "管理员" : "普通用户" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="160" />
        <el-table-column prop="phone" label="电话" min-width="120" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? "启用" : "禁用" }}</el-tag>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" :disabled="!!form.id" placeholder="登录账号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空则不修改' : '必填'" />
        </el-form-item>
        <el-form-item label="姓名" prop="nickname">
          <el-input v-model="form.nickname" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 200px;">
            <el-option label="普通用户" value="USER" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="邮箱地址" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
          <el-input v-model="form.phone" placeholder="手机号" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">禁用</el-radio>
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
import { listUsers, createUser, updateUser, deleteUser } from "@/api/userAdmin";

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const keyword = ref("");
const dialogVisible = ref(false);
const formRef = ref(null);

function defaultForm() {
  return { id: null, username: "", password: "", nickname: "", role: "USER", email: "", phone: "", status: 1 };
}
const form = reactive(defaultForm());

const formRules = {
  username: [{ required: true, message: "请输入账号", trigger: "blur" }],
  nickname: [{ required: true, message: "请输入姓名", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listUsers(keyword.value.trim() || undefined);
    if (res.code === 200) rows.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载用户失败");
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
    username: row.username,
    password: "",
    nickname: row.nickname || "",
    role: row.role || "USER",
    email: row.email || "",
    phone: row.phone || "",
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
      if (!payload.password) delete payload.password;
      const res = payload.id ? await updateUser(payload.id, payload) : await createUser(payload);
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
    await ElMessageBox.confirm("确认删除用户 " + row.username + "？", "提示", { type: "warning" });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteUser(row.id);
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