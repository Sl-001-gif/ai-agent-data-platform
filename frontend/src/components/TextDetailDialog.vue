<template>
  <el-dialog v-model="visible" title="内容详情" width="760px" top="5vh">
    <pre style="max-height: 65vh; overflow: auto; background: #f5f7fa; padding: 12px; border-radius: 4px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; margin: 0;">{{ text }}</pre>
    <template #footer>
      <el-button type="primary" @click="copy">复制</el-button>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from "vue";
import { ElMessage } from "element-plus";

const visible = ref(false);
const text = ref("");

function open(content) {
  let t = content || "";
  if (t) {
    try {
      t = JSON.stringify(JSON.parse(t), null, 2);
    } catch (e) {
      // 非 JSON 内容按原文展示
    }
  }
  text.value = t || "-";
  visible.value = true;
}

async function copy() {
  try {
    await navigator.clipboard.writeText(text.value);
    ElMessage.success("已复制");
  } catch (e) {
    ElMessage.error("复制失败，请手动选择");
  }
}

defineExpose({ open });
</script>