import request from "./request";

export function parseAnalysis(data) {
  return request.post("/analysis/parse", data);
}

export function executeAnalysis(data) {
  return request.post("/analysis/execute", data);
}

export function generateReport(data) {
  return request.post("/analysis/report", data);
}