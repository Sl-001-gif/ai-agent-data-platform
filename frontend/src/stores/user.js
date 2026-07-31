import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { getProfile } from "@/api/auth";

export const useUserStore = defineStore("user", () => {
  const token = ref(localStorage.getItem("token") || "");
  const userInfo = ref(JSON.parse(localStorage.getItem("user") || "{}"));

  const isLoggedIn = computed(() => !!token.value);
  const isAdmin = computed(() => userInfo.value?.role === "ADMIN");

  function setToken(newToken) {
    token.value = newToken;
    localStorage.setItem("token", newToken);
  }

  function setUserInfo(info) {
    userInfo.value = info;
    localStorage.setItem("user", JSON.stringify(info));
  }

  async function fetchProfile() {
    try {
      const res = await getProfile();
      if (res.code === 200) {
        setUserInfo(res.data);
      }
    } catch (e) {
      console.error("获取用户信息失败", e);
    }
  }

  function logout() {
    token.value = "";
    userInfo.value = {};
    localStorage.removeItem("token");
    localStorage.removeItem("user");
  }

  return { token, userInfo, isLoggedIn, isAdmin, setToken, setUserInfo, fetchProfile, logout };
});
