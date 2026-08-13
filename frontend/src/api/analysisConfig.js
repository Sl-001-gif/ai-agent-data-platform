import request from "./request";

export function listIntentRules() {
  return request.get("/admin/analysis-config/intent-rules");
}
export function createIntentRule(data) {
  return request.post("/admin/analysis-config/intent-rules", data);
}
export function updateIntentRule(id, data) {
  return request.put("/admin/analysis-config/intent-rules/" + id, data);
}
export function deleteIntentRule(id) {
  return request.delete("/admin/analysis-config/intent-rules/" + id);
}

export function listPlanConfigs() {
  return request.get("/admin/analysis-config/plan-configs");
}
export function createPlanConfig(data) {
  return request.post("/admin/analysis-config/plan-configs", data);
}
export function updatePlanConfig(id, data) {
  return request.put("/admin/analysis-config/plan-configs/" + id, data);
}
export function deletePlanConfig(id) {
  return request.delete("/admin/analysis-config/plan-configs/" + id);
}

export function listPlanTypes() {
  return request.get("/admin/analysis-config/plan-types");
}
export function createPlanType(data) {
  return request.post("/admin/analysis-config/plan-types", data);
}
export function updatePlanType(id, data) {
  return request.put("/admin/analysis-config/plan-types/" + id, data);
}
export function deletePlanType(id) {
  return request.delete("/admin/analysis-config/plan-types/" + id);
}