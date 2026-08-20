import request from "./request";

export function planAnalysis(data) {
  return request.post("/analysis/plan", data);
}
export function parseAnalysis(data) {
  return request.post("/analysis/parse", data);
}

export function sqlAnalysis(data) {
  return request.post("/analysis/sql", data);
}

export function executeAnalysis(data) {
  return request.post("/analysis/execute", data);
}

export function generateReport(data) {
  return request.post("/analysis/report", data);
}