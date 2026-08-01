import request from "./request";

export function listDataSources() {
  return request.get("/admin/datasource");
}

export function createDataSource(data) {
  return request.post("/admin/datasource", data);
}

export function updateDataSource(id, data) {
  return request.put("/admin/datasource/" + id, data);
}

export function deleteDataSource(id) {
  return request.delete("/admin/datasource/" + id);
}

export function testDataSource(data) {
  return request.post("/admin/datasource/test", data);
}