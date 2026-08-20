import request from "./request";

export function createSession(data) {
  return request.post("/analysis/session", data);
}
export function deleteReport(id) {
  return request.delete("/analysis/report/" + id);
}
export function listSessions(keyword, datasetId, page, pageSize) {
  const params = {};
  if (keyword) params.keyword = keyword;
  if (datasetId) params.datasetId = datasetId;
  if (page != null) params.page = page;
  if (pageSize != null) params.pageSize = pageSize;
  return request.get("/analysis/sessions", { params });
}
export function deleteSession(id) {
  return request.delete("/analysis/session/" + id);
}
export function batchDeleteSessions(ids) {
  return request.post("/analysis/sessions/batch-delete", { ids });
}
export function listSessionSteps(id) {
  return request.get("/analysis/session/" + id + "/steps");
}
export function getSessionReport(sessionId, roundNo) {
  const params = roundNo != null ? { roundNo } : {};
  return request.get("/analysis/session/" + sessionId + "/report", { params });
}
export function listDatasetOptions() {
  return request.get("/analysis/datasets");
}
export function listModelOptions() {
  return request.get("/analysis/models");
}
export function listReports(page, pageSize) {
  const params = {};
  if (page != null) params.page = page;
  if (pageSize != null) params.pageSize = pageSize;
  return request.get("/analysis/reports", { params });
}
export function getReport(id) {
  return request.get("/analysis/report/" + id);
}