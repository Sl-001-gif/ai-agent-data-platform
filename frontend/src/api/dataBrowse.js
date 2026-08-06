import request from "./request";

export function listTables(dataSourceId) {
  return request.post("/admin/data-browse/tables", { dataSourceId });
}

export function queryData(payload) {
  return request.post("/admin/data-browse/query", payload);
}