<template>
  <div class="login-container">
    <div class="login-card">
      <h2 class="title">AI Agent 数据分析平台</h2>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="0">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="footer">
        <span>还没有账号？</span>
        <router-link to="/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { login } from "@/api/auth";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const userStore = useUserStore();
const formRef = ref(null);
const loading = ref(false);

const form = reactive({
  username: "",
  password: "",
});

const rules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  loading.value = true;
  try {
    const res = await login(form);
    if (res.code === 200) {
      userStore.setToken(res.data.token);
      userStore.setUserInfo(res.data);
      ElMessage.success("登录成功");
      router.push("/home");
    } else {
      ElMessage.error(res.message);
    }
  } catch (e) {
    ElMessage.error("登录失败");
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 400px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
}
.title {
  text-align: center;
  margin-bottom: 30px;
  color: #303133;
  font-size: 22px;
}
.submit-btn {
  width: 100%;
}
.footer {
  text-align: center;
  margin-top: 16px;
  font-size: 14px;
  color: #909399;
}
.footer a {
  color: #409eff;
  text-decoration: none;
}
</style>
