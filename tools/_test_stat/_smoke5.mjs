import { readFileSync } from "node:fs";
const BASE = "http://localhost:8080/api";
const DATASET_ID = 23;
const ids = ["居民收支-Q07","外贸外资-Q10","外贸外资-Q11","财政-Q03","财政-Q10","贸易-Q03","贸易-Q12","跨期-Q02","跨期-Q10","金融-Q03","金融-Q07"];
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
  let rep = null; let repErr = null;
  for (let a=1;a<=2;a++){ try { rep = await http("POST","/analysis/report",{sessionId:sid,roundNo:parsed.data.roundNo}); repErr=null; break; } catch(e){ repErr=String(e.message||e); await new Promise(r=>setTimeout(r,2000)); } }
  const exp = q.expect||{};
  const v = [];
  if (exp.expectValue!=null) v.push("val:"+(findValue(rows, exp.expectValue, exp.tolerance??0.02)?"OK":"MISS"));
  if (exp.expectGrowth!=null) v.push("grow:"+(findValue(rows, exp.expectGrowth, 0.06)?"OK":"MISS"));
  if (exp.minRows!=null) v.push("rows:"+(rows.length>=exp.minRows?"OK("+rows.length+")":"MISS("+rows.length+")"));
  v.push("rep:"+(rep&&rep.data?.report?.content ? "OK":"FAIL"+(repErr?"("+repErr+")":"")));
  const ok = v.every(x=>!x.includes("MISS")&&!x.includes("FAIL("));
  ok?pass++:fail++;
  console.log((ok?"PASS":"FAIL")+" "+id+" ["+v.join(" ")+"]");
  if (!ok) {
    console.log("   metrics="+JSON.stringify(plan.metrics)+" SQL="+exec.data.sql);
    if (rows.length) console.log("   rows:", JSON.stringify(rows.slice(0,3)));
  }
}
console.log("\nSMOKE pass="+pass+" fail="+fail);