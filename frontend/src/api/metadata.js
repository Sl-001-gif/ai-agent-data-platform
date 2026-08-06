import request from "./request";

export function listDatasets() {
  return request.get("/admin/dataset");
}
export function createDataset(data) {
  return request.post("/admin/dataset", data);
}
export function updateDataset(id, data) {
  return request.put("/admin/dataset/" + id, data);
}
export function deleteDataset(id) {
  return request.delete("/admin/dataset/" + id);
}

export function listDataTables() {
  return request.get("/admin/data-table");
}
export function createDataTable(data) {
  return request.post("/admin/data-table", data);
}
export function updateDataTable(id, data) {
  return request.put("/admin/data-table/" + id, data);
}
export function deleteDataTable(id) {
  return request.delete("/admin/data-table/" + id);
}

export function listFieldSemantics() {
  return request.get("/admin/field-semantic");
}
export function createFieldSemantic(data) {
  return request.post("/admin/field-semantic", data);
}
export function updateFieldSemantic(id, data) {
  return request.put("/admin/field-semantic/" + id, data);
}
export function deleteFieldSemantic(id) {
  return request.delete("/admin/field-semantic/" + id);
}

export function listMetrics() {
  return request.get("/admin/metric");
}
export function createMetric(data) {
  return request.post("/admin/metric", data);
}
export function updateMetric(id, data) {
  return request.put("/admin/metric/" + id, data);
}
export function deleteMetric(id) {
  return request.delete("/admin/metric/" + id);
}

export function listCategories() {
  return request.get("/admin/category");
}
export function createCategory(data) {
  return request.post("/admin/category", data);
}
export function updateCategory(id, data) {
  return request.put("/admin/category/" + id, data);
}
export function deleteCategory(id) {
  return request.delete("/admin/category/" + id);
}
