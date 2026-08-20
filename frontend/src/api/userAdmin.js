import request from "./request";

export function listUsers(keyword, page, pageSize) {
  const params = keyword ? { keyword } : {};
  if (page != null) params.page = page;
  if (pageSize != null) params.pageSize = pageSize;
  return request.get("/admin/user", { params });
}
export function createUser(data) {
  return request.post("/admin/user", data);
}
export function updateUser(id, data) {
  return request.put("/admin/user/" + id, data);
}
export function deleteUser(id) {
  return request.delete("/admin/user/" + id);
}