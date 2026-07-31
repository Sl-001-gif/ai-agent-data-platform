import request from "./request";

export function parseAnalysis(data) {
  return request.post("/analysis/parse", data);
}