import { readFileSync } from "node:fs";
const BASE = "http://localhost:8080/api";
const DATASET_ID = 23;
const ids = ["工业-Q04","核算-Q04","财政-Q04","能源交通-Q05"];
const qs = JSON.parse(readFileSync("questions.json","utf-8"));
const byId = new Map(qs.map(q=>[q.id,q]));
let token = null;
async function http(method, path, body) {
  const res = await fetch(BASE+path, { method, headers: {"Content-Type":"application/json; charset=utf-8", ...(token?{Authorization:"Bearer "+token}:{})}, body: body?JSON.stringify(body):undefined });
  const text = await res.text();
  let json; try { json = JSON.parse(text); } catch { json = {raw:text}; }
  if (json.code != null && json.code !== 200) throw new Error(path+" -> "+json.code+" "+json.message);
  return json;
}
function findValue(rows, target, tol=0.02){
  if(!rows||!Array.isArray(rows)) return false;
  const t = typeof target==="number"?target:Number(String(target).replace(/,/g,""));
  if(Number.isNaN(t)) return false;
  for(const r of rows){ for(const [k,v] of Object.entries(r)){ if(v==null||v===""||typeof v==="object") continue; const n=Number(String(v).replace(/,/g,"")); if(!Number.isNaN(n)&&Math.abs(n-t)<=tol) return true; } }
  return false;
}
const login = await http("POST","/auth/login",{username:"admin",password:"admin123"});
token = login.data.token;
for (const id of ids){
  const q = byId.get(id);
  console.log("\n=== "+id+" ===");
  const parsed = await http("POST","/analysis/parse",{text:q.question,datasetId:DATASET_ID,title:"政务部员深度测试1-smoke"});
  const sid = parsed.data.sessionId;
  const plan = parsed.data.plan;
  console.log("intent:", JSON.stringify(parsed.data.intent));
  console.log("metrics:", JSON.stringify(plan.metrics), "dims:", JSON.stringify(plan.dimensions), "time:", plan.timeRange, "chart:", plan.chartType);
  const exec = await http("POST","/analysis/execute",{text:q.question,datasetId:DATASET_ID,sessionId:sid});
  console.log("SQL:", exec.data.sql);
  const rows = exec.data.execution.rows;
  console.log("rowCount:", rows.length, "chartType:", exec.data.chartType);
  console.log("warnings:", JSON.stringify(exec.data.dataWarnings));
  if (rows.length) console.log("first2:", JSON.stringify(rows.slice(0,2)));
  const exp = q.expect||{};
  if (exp.expectValue!=null) console.log("expectValue", exp.expectValue, "->", findValue(rows, exp.expectValue, exp.tolerance??0.02));
  if (exp.expectGrowth!=null) console.log("expectGrowth", exp.expectGrowth, "->", findValue(rows, exp.expectGrowth, 0.06));
  if (exp.minRows!=null) console.log("minRows", exp.minRows, "->", rows.length>=exp.minRows);
  console.log("interpret:", (exec.data.interpretation?.text||"").slice(0,80));
}
console.log("\nSMOKE DONE");