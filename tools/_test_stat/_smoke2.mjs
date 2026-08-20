import { readFileSync } from "node:fs";
const BASE = "http://localhost:8080/api";
const DATASET_ID = 23;
const ids = ["工业-Q04","核算-Q04","财政-Q04","能源交通-Q01","能源交通-Q02","能源交通-Q03","能源交通-Q04","能源交通-Q05","能源交通-Q06","能源交通-Q07","能源交通-Q08","能源交通-Q09","能源交通-Q11","跨期-Q03","跨期-Q08","跨期-Q10"];
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
let pass=0, fail=0;
for (const id of ids){
  const q = byId.get(id);
  const parsed = await http("POST","/analysis/parse",{text:q.question,datasetId:DATASET_ID,title:"政务部员深度测试1-smoke"});
  const sid = parsed.data.sessionId;
  const plan = parsed.data.plan;
  const exec = await http("POST","/analysis/execute",{text:q.question,datasetId:DATASET_ID,sessionId:sid});
  const rows = exec.data.execution.rows;
  const exp = q.expect||{};
  const v = [];
  if (exp.expectValue!=null) v.push("val:"+(findValue(rows, exp.expectValue, exp.tolerance??0.02)?"OK":"MISS"));
  if (exp.expectGrowth!=null) v.push("grow:"+(findValue(rows, exp.expectGrowth, 0.06)?"OK":"MISS"));
  if (exp.minRows!=null) v.push("rows:"+(rows.length>=exp.minRows?"OK("+rows.length+")":"MISS("+rows.length+")"));
  const ok = v.every(x=>x.endsWith("OK)")||x.includes("OK(")||x.includes(":OK"));
  ok?pass++:fail++;
  console.log((ok?"PASS":"FAIL")+" "+id+" ["+v.join(" ")+"] SQL: "+exec.data.sql);
  if (!ok && rows.length) console.log("   rows:", JSON.stringify(rows.slice(0,3)));
  if (!ok && !rows.length) console.log("   warnings:", JSON.stringify(exec.data.dataWarnings));
}
console.log("\nSMOKE SUMMARY pass="+pass+" fail="+fail);