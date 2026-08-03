import request from "./request";

export function listSessions(keyword) {
  const params = keyword ? { keyword } : {};
  return request.get("/analysis/sessions", { params });
}
export function deleteSession(id) {
  return request.delete("/analysis/session/" + id);
}
export function listSessionSteps(id) {
  return request.get("/analysis/session/" + id + "/steps");
}
export function listReports() {
  return request.get("/analysis/reports");
}
export function getReport(id) {
  return request.get("/analysis/report/" + id);
}