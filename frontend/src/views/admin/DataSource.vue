<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>数据源列表</span>
          <el-button type="primary" size="small" @click="openCreate">新增数据源</el-button>
        </div>
      </template>
      <el-table :data="pagedRows" v-loading="loading" border stripe size="small">
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="dbType" label="类型" width="80" />
        <el-table-column label="地址" min-width="160">
          <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column prop="databaseName" label="数据库" min-width="120" />
        <el-table-column prop="username" label="数据库用户" min-width="100" />
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="创建时间" min-width="150">
          <template #default="{ row }">{{ row.createTime || "-" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :loading="testingId === row.id" @click="handleTest(row)">测试连接</el-button>
            <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="margin-top: 12px; display: flex; justify-content: flex-end;"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="dataSources.length"
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据源' : '新增数据源'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如：演示业务库" />
        </el-form-item>
        <el-form-item label="类型" prop="dbType">
          <el-select v-model="form.dbType" style="width: 100%;">
            <el-option label="MySQL" value="MYSQL" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机地址" prop="host">
          <el-input v-model="form.host" placeholder="如：localhost" />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="数据库名" prop="databaseName">
          <el-input v-model="form.databaseName" placeholder="如：ai_agent_data" />
        </el-form-item>
        <el-form-item label="数据库用户名" prop="username">
          <el-input v-model="form.username" placeholder="MySQL 账号，如：root" />
        </el-form-item>
        <el-form-item label="数据库密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="数据库密码" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testing" @click="handleTestForm">测试连接</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { listDataSources, createDataSource, updateDataSource, deleteDataSource, testDataSource } from "@/api/datasource";

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const testingId = ref(null);
const dataSources = ref([]);
const page = ref(1);
const pageSize = ref(10);

const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value;
  return dataSources.value.slice(start, start + pageSize.value);
});
const dialogVisible = ref(false);
const formRef = ref(null);

function defaultForm() {
  return {
    id: null,
    name: "",
    dbType: "MYSQL",
    host: "",
    port: 3306,
    databaseName: "",
    username: "",
    password: "",
    remark: "",
  };
}

const form = reactive(defaultForm());

const formRules = {
  name: [{ required: true, message: "请输入数据源名称", trigger: "blur" }],
  host: [{ required: true, message: "请输入主机地址", trigger: "blur" }],
  port: [{ required: true, message: "请输入端口", trigger: "blur" }],
  databaseName: [{ required: true, message: "请输入数据库名", trigger: "blur" }],
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};

async function fetchList() {
  loading.value = true;
  try {
    const res = await listDataSources();
    if (res.code === 200) {
      dataSources.value = res.data || [];
    }
  } catch (e) {
    ElMessage.error("加载数据源失败");
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
    dbType: row.dbType,
    host: row.host,
    port: row.port,
    databaseName: row.databaseName,
    username: row.username,
    password: row.password,
    remark: row.remark,
  });
  dialogVisible.value = true;
}

function formPayload() {
  return {
    name: form.name,
    dbType: form.dbType,
    host: form.host,
    port: form.port,
    databaseName: form.databaseName,
    username: form.username,
    password: form.password,
    remark: form.remark,
  };
}

async function handleTestForm() {
  testing.value = true;
  try {
    const res = await testDataSource(formPayload());
    if (res.code === 200) {
      if (res.data.success) {
        ElMessage.success("连接成功：" + res.data.latencyMs + "ms");
      } else {
        ElMessage.error("连接失败：" + (res.data.message || "未知原因"));
      }
    }
  } catch (e) {
    ElMessage.error("连接测试失败");
  } finally {
    testing.value = false;
  }
}

async function handleTest(row) {
  testingId.value = row.id;
  try {
    const res = await testDataSource({
      name: row.name,
      dbType: row.dbType,
      host: row.host,
      port: row.port,
      databaseName: row.databaseName,
      username: row.username,
      password: row.password,
      remark: row.remark,
    });
    if (res.code === 200) {
      if (res.data.success) {
        ElMessage.success("连接成功：" + res.data.latencyMs + "ms");
      } else {
        ElMessage.error("连接失败：" + (res.data.message || "未知原因"));
      }
    }
  } catch (e) {
    ElMessage.error("连接测试失败");
  } finally {
    testingId.value = null;
  }
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    let res;
    if (form.id) {
      res = await updateDataSource(form.id, formPayload());
    } else {
      res = await createDataSource(formPayload());
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
    await ElMessageBox.confirm("确定删除数据源「" + row.name + "」吗？", "删除确认", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteDataSource(row.id);
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
