import request from "./request";

/** Agent 多步分析计划：拆解 / 执行 / 报告 三页共用。 */
export function createAgentPlan(data) {
  return request.post("/agent-plan", data);
}
export function executeAgentPlan(id) {
  return request.post("/agent-plan/" + id + "/execute");
}
export function getAgentPlan(id) {
  return request.get("/agent-plan/" + id);
}
export function listAgentPlans() {
  return request.get("/agent-plan/list");
}
export function deleteAgentPlan(id) {
  return request.delete("/agent-plan/" + id);
}
export function generateAgentReport(id, data) {
  return request.post("/agent-plan/" + id + "/report", data || {});
}
export function listAgentReports() {
  return request.get("/agent-plan/reports");
}