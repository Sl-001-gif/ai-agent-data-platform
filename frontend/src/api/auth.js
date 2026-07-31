import request from "./request";

export function login(data) {
  return request.post("/auth/login", data);
}

export function register(data) {
  return request.post("/auth/register", data);
}

export function getProfile() {
  return request.get("/auth/profile");
}

export function updateProfile(data) {
  return request.put("/auth/profile", data);
}

export function changePassword(data) {
  return request.put("/auth/password", data);
}
