<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>数据浏览</span>
          <el-select v-model="selectedDs" placeholder="选择数据源" style="width: 260px;" @change="loadTables">
            <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </div>
      </template>
      <template v-if="selectedDs">
        <el-row :gutter="16">
          <el-col :span="6">
            <div style="border: 1px solid #ebeef5; border-radius: 6px; max-height: 60vh; overflow: auto;" v-loading="tablesLoading">
              <div v-for="t in tables" :key="t.tableName" class="table-item" :class="{ active: currentTable === t.tableName }" @click="selectTable(t)">
                <div style="font-weight: 600;">{{ t.tableName }}</div>
                <div style="font-size: 12px; color: #909399;">{{ t.tableComment || "-" }}</div>
              </div>
              <el-empty v-if="!tablesLoading && tables.length === 0" description="该库暂无业务表" :image-size="60" />
            </div>
          </el-col>
          <el-col :span="18">
            <div v-if="currentTable">
              <el-table :data="rows" v-loading="queryLoading" border stripe size="small" max-height="55vh">
                <el-table-column v-for="col in columns" :key="col" :prop="col" :label="col" min-width="120" show-overflow-tooltip />
              </el-table>
              <div style="display: flex; justify-content: flex-end; margin-top: 10px;">
                <el-pagination background layout="total, prev, pager, next" :total="total" :page-size="pageSize" :current-page="page" @current-change="changePage" />
              </div>
            </div>
            <el-empty v-else description="从左侧选择一张表" />
          </el-col>
        </el-row>
      </template>
      <el-empty v-else description="请先选择数据源" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { listDataSources } from "@/api/datasource";
import { listTables, queryData } from "@/api/dataBrowse";

const dataSources = ref([]);
const selectedDs = ref(null);
const tables = ref([]);
const tablesLoading = ref(false);
const currentTable = ref("");
const columns = ref([]);
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 50;
const queryLoading = ref(false);

async function fetchDataSources() {
  try {
    const res = await listDataSources();
    if (res.code === 200) {
      dataSources.value = res.data || [];
      if (dataSources.value.length === 1) {
        selectedDs.value = dataSources.value[0].id;
        await loadTables();
      }
    }
  } catch (e) {
    ElMessage.error("加载数据源失败");
  }
}

async function loadTables() {
  if (!selectedDs.value) return;
  tablesLoading.value = true;
  currentTable.value = "";
  columns.value = [];
  rows.value = [];
  try {
    const res = await listTables(selectedDs.value);
    if (res.code === 200) tables.value = res.data || [];
  } catch (e) {
    ElMessage.error("加载表列表失败");
  } finally {
    tablesLoading.value = false;
  }
}

async function selectTable(t) {
  currentTable.value = t.tableName;
  page.value = 1;
  await loadData();
}

async function changePage(p) {
  page.value = p;
  await loadData();
}

async function loadData() {
  if (!currentTable.value) return;
  queryLoading.value = true;
  try {
    const res = await queryData({ dataSourceId: selectedDs.value, tableName: currentTable.value, page: page.value, pageSize });
    if (res.code === 200) {
      columns.value = res.data.columns || [];
      rows.value = res.data.rows || [];
      total.value = res.data.total || 0;
    }
  } catch (e) {
    ElMessage.error("查询数据失败");
  } finally {
    queryLoading.value = false;
  }
}

onMounted(fetchDataSources);
</script>

<style scoped>
.table-item {
  padding: 10px;
  border-bottom: 1px solid #ebeef5;
  cursor: pointer;
}
.table-item.active {
  background: #ecf5ff;
}
.table-item:hover {
  background: #f5f7fa;
}
</style>