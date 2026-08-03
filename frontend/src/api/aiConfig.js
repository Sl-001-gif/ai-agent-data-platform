import request from "./request";

export function listAiModels() {
  return request.get("/admin/ai-config/models");
}
export function createAiModel(data) {
  return request.post("/admin/ai-config/models", data);
}
export function updateAiModel(id, data) {
  return request.put("/admin/ai-config/models/" + id, data);
}
export function deleteAiModel(id) {
  return request.delete("/admin/ai-config/models/" + id);
}

export function listPrompts() {
  return request.get("/admin/ai-config/prompts");
}
export function createPrompt(data) {
  return request.post("/admin/ai-config/prompts", data);
}
export function updatePrompt(id, data) {
  return request.put("/admin/ai-config/prompts/" + id, data);
}
export function deletePrompt(id) {
  return request.delete("/admin/ai-config/prompts/" + id);
}