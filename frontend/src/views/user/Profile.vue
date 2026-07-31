<template>
  <div class="profile-container">
    <el-card class="profile-card">
      <template #header>
        <span>个人信息</span>
      </template>
      <el-form ref="formRef" :model="form" label-width="80px">
        <el-form-item label="用户名">
          <el-input :model-value="userStore.userInfo?.username" disabled />
        </el-form-item>
        <el-form-item label="角色">
          <el-tag :type="userStore.isAdmin ? 'danger' : 'primary'">
            {{ userStore.isAdmin ? "管理员" : "普通用户" }}
          </el-tag>
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleUpdate">保存修改</el-button>
          <el-button @click="router.push('/analysis')">返回</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { updateProfile } from "@/api/auth";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const userStore = useUserStore();
const loading = ref(false);

const form = reactive({
  nickname: "",
  email: "",
});

onMounted(() => {
  form.nickname = userStore.userInfo?.nickname || "";
  form.email = userStore.userInfo?.email || "";
});

async function handleUpdate() {
  loading.value = true;
  try {
    const res = await updateProfile(form);
    if (res.code === 200) {
      await userStore.fetchProfile();
      ElMessage.success("保存成功");
    }
  } catch (e) {
    ElMessage.error("保存失败");
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.profile-container {
  max-width: 600px;
  margin: 40px auto;
  padding: 0 20px;
}
</style>
