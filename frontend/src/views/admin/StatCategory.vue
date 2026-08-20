<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>统计指标分类（一级大类 / 二级中类 / 三级叶子指标）</span>
          <div>
            <el-button size="small" @click="toggleExpand">{{ expandAll ? "折叠全部" : "展开全部" }}</el-button>
            <el-button type="primary" size="small" @click="openCreate(null)">新增一级分类</el-button>
          </div>
        </div>
      </template>
      <el-tree
        ref="treeRef"
        :data="tree"
        :props="{ label: 'name', children: 'children' }"
        :default-expand-all="true"
        :expand-on-click-node="false"
        node-key="id"
        v-loading="loading"
        empty-text="暂无分类数据，请先新增一级分类"
      >
        <template #default="{ node, data }">
          <div style="display: flex; align-items: center; flex: 1; min-width: 0;">
            <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
              {{ data.name }}
              <el-tag size="small" type="info" style="margin-left: 6px;">{{ data.code }}</el-tag>
              <el-tag size="small" :type="levelTagType(data.level)" style="margin-left: 4px;">{{ data.level }}级</el-tag>
              <span v-if="data.color" :style="{ display: 'inline-block', width: '10px', height: '10px', borderRadius: '50%', background: data.color, marginLeft: '4px' }" />
            </span>
            <span style="flex-shrink: 0;">
              <el-button v-if="data.level < 3" link type="primary" size="small" @click.stop="openCreate(data)">新增下级</el-button>
              <el-button link type="primary" size="small" @click.stop="openEdit(data)">编辑</el-button>
              <el-button link type="danger" size="small" @click.stop="handleDelete(data)">删除</el-button>
            </span>
          </div>
        </template>
      </el-tree>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-alert
        v-if="form.parentId"
        :title="'将创建在上级「' + form.parentName + '」下，层级为 ' + form.level + ' 级'"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px;"
      />
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="节点名称" prop="name">
          <el-input v-model="form.name" placeholder="如：经济核算 / 地区生产总值 / 地区生产总值（GDP）" maxlength="60" show-word-limit />
        </el-form-item>
        <el-form-item label="节点编码" prop="code">
          <el-input v-model="form.code" placeholder="如：c01 / c01_01 / 010101" maxlength="40" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item v-if="form.level === 1" label="颜色" prop="color">
          <el-color-picker v-model="form.color" />
        </el-form-item>
        <el-form-item label="启用" prop="status">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
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
import { treeStatCategory, createStatCategory, updateStatCategory, deleteStatCategory } from "@/api/statCategory";

const loading = ref(false);
const saving = ref(false);
const expandAll = ref(true);
const tree = ref([]);
const dialogVisible = ref(false);
const formRef = ref(null);

const dialogTitle = computed(() => {
  if (form.value.id) return "编辑分类节点";
  return form.value.parentId ? "新增下级分类" : "新增一级分类";
});

function defaultForm() {
  return {
    id: null,
    parentId: null,
    parentName: "",
    name: "",
    code: "",
    level: 1,
    sort: 0,
    color: "#409eff",
    status: 1,
  };
}
const form = ref(defaultForm());

const formRules = {
  name: [{ required: true, message: "请输入节点名称", trigger: "blur" }],
  code: [
    { required: true, message: "请输入节点编码", trigger: "blur" },
    { pattern: /^[a-zA-Z0-9_\-]+$/, message: "编码仅支持字母、数字、下划线、短横线", trigger: "blur" },
  ],
};

function levelTagType(level) {
  return level === 1 ? "danger" : level === 2 ? "warning" : "success";
}

function toggleExpand() {
  if (!treeRef.value) return;
  expandAll.value = !expandAll.value;
  if (expandAll.value) {
    treeRef.value.store.expandAll();
  } else {
    treeRef.value.store.collapseAll();
  }
}

async function fetchTree() {
  loading.value = true;
  try {
    const res = await treeStatCategory();
    if (res.code === 200) tree.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载分类树失败");
  } finally {
    loading.value = false;
  }
}

function openCreate(parent) {
  Object.assign(form.value, defaultForm());
  if (parent) {
    form.value.parentId = parent.id;
    form.value.parentName = parent.name;
    form.value.level = parent.level + 1;
    form.value.sort = 0;
  }
  dialogVisible.value = true;
}

function openEdit(row) {
  Object.assign(form.value, defaultForm(), {
    id: row.id,
    parentId: row.parentId,
    parentName: row.parentId ? "（上级节点）" : "",
    name: row.name,
    code: row.code,
    level: row.level,
    sort: row.sort == null ? 0 : row.sort,
    color: row.color || "#409eff",
    status: row.status == null ? 1 : row.status,
  });
  dialogVisible.value = true;
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    const payload = {
      parentId: form.value.parentId,
      name: form.value.name,
      code: form.value.code,
      level: form.value.level,
      sort: form.value.sort,
      color: form.value.level === 1 ? form.value.color : null,
      status: form.value.status,
    };
    let res;
    if (form.value.id) {
      res = await updateStatCategory(form.value.id, payload);
    } else {
      res = await createStatCategory(payload);
    }
    if (res.code === 200) {
      ElMessage.success(form.value.id ? "更新成功" : "新增成功");
      dialogVisible.value = false;
      await fetchTree();
    }
  } catch (e) {
    ElMessage.error("保存失败：" + ((e && e.response && e.response.data && e.response.data.message) || "请检查层级与编码"));
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm("确定删除「" + row.name + "」吗？存在子节点时将无法删除。", "删除确认", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    });
  } catch (e) {
    return;
  }
  try {
    const res = await deleteStatCategory(row.id);
    if (res.code === 200) {
      ElMessage.success("删除成功");
      await fetchTree();
    }
  } catch (e) {
    ElMessage.error("删除失败：" + ((e && e.response && e.response.data && e.response.data.message) || "请先删除子节点"));
  }
}

onMounted(fetchTree);
</script>
