import request from "./request";

export function treeStatCategory() {
  return request.get("/admin/stat-category/tree");
}
export function createStatCategory(data) {
  return request.post("/admin/stat-category", data);
}
export function updateStatCategory(id, data) {
  return request.put("/admin/stat-category/" + id, data);
}
export function deleteStatCategory(id) {
  return request.delete("/admin/stat-category/" + id);
}
