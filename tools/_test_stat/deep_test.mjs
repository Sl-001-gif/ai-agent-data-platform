// 全量 LLM 深度测试脚本（stat_monthly，dataset 23）
// 用法: node deep_test.mjs [--from=ID] [--concurrency=3] [--max=N]
// 输入: questions.json  输出: results/{id}.json + 汇总报告.md + 汇总表.csv
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dir = dirname(fileURLToPath(import.meta.url));
const BASE = "http://localhost:8080/api";
const QUESTIONS_FILE = join(__dir, "questions.json");
const RESULTS_DIR = join(__dir, "results");
const PREFIX = "政务部员深度测试1";
const DATASET_ID = 23;
const TIMEOUT_MS = 240000;

const args = process.argv.slice(2);
const fromId = args.find((a) => a.startsWith("--from="))?.split("=")[1] ?? "";
const concurrency = Number(args.find((a) => a.startsWith("--concurrency="))?.split("=")[1] ?? 3);
const maxN = Number(args.find((a) => a.startsWith("--max="))?.split("=")[1] ?? 0);

mkdirSync(RESULTS_DIR, { recursive: true });
const questions = JSON.parse(readFileSync(QUESTIONS_FILE, "utf-8"));
let token = null;

async function http(method, path, body) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: ctrl.signal,
    });
    const text = await res.text();
    let json;
    try { json = JSON.parse(text); } catch { json = { raw: text }; }
    if (json.code != null && json.code !== 200) {
      throw new Error(path + " -> " + json.code + " " + (json.message || ""));
    }
    return json;
  } finally { clearTimeout(timer); }
}

function findValue(rows, target, tol = 0.02) {
  if (!rows || !Array.isArray(rows)) return false;
  const t = typeof target === "number" ? target : Number(String(target).replace(/,/g, ""));
  if (Number.isNaN(t)) return false;
  for (const r of rows) {
    for (const [k, v] of Object.entries(r)) {
      if (v == null || v === "" || typeof v === "object") continue;
      const n = Number(String(v).replace(/,/g, ""));
      if (!Number.isNaN(n) && Math.abs(n - t) <= tol) return true;
    }
  }
  return false;
}

function verdict(q, parsed, exec, rep) {
  const rows = exec?.execution?.rows || [];
  const checks = [];
  const fails = [];
  const e = q.expect || {};
  if (e.minRows != null) {
    const ok = rows.length >= e.minRows;
    (ok ? checks : fails).push("行数>=" + e.minRows + " 实际=" + rows.length);
  }
  if (e.expectValue != null) {
    const ok = findValue(rows, e.expectValue, e.tolerance ?? 0.02);
    (ok ? checks : fails).push("含期望值 " + e.expectValue + " -> " + (ok ? "命中" : "未命中"));
  }
  if (e.expectGrowth != null) {
    const ok = findValue(rows, e.expectGrowth, 0.06);
    (ok ? checks : fails).push("含期望增速 " + e.expectGrowth + " -> " + (ok ? "命中" : "未命中"));
  }
  const hasInterpret = !!(exec?.interpretation?.text && exec.interpretation.text.trim());
  (hasInterpret ? checks : fails).push("解读非空");
  const hasReport = !!(rep?.report?.content && rep.report.content.trim());
  (hasReport ? checks : fails).push("报告非空");
  const chartType = exec?.chartType ?? parsed?.plan?.chartType;
  if (e.expectChart) {
    const ok = chartType === e.expectChart;
    (ok ? checks : fails).push("图表类型=" + e.expectChart + " 实际=" + chartType);
  }
  return {
    pass: fails.length === 0,
    checks,
    fails,
    chartType,
    rowCount: rows.length,
  };
}

async function runQuestion(q) {
  const out = { id: q.id, domain: q.domain, question: q.question, expect: q.expect || {}, ts: new Date().toISOString() };
  try {
    const parseBody = { text: q.question, datasetId: DATASET_ID, title: PREFIX + "-" + q.domain };
    if (q.sessionId) parseBody.sessionId = q.sessionId;
    const parsed = await http("POST", "/analysis/parse", parseBody);
    out.sessionId = parsed.data.sessionId;
    out.roundNo = parsed.data.roundNo;
    out.intent = parsed.data.intent;
    out.plan = parsed.data.plan;
    out.sqlExplanation = parsed.data.sqlExplanation;
    const exec = await http("POST", "/analysis/execute", {
      text: q.question,
      datasetId: DATASET_ID,
      sessionId: out.sessionId,
    });
    out.sql = exec.data.sql;
    out.validation = exec.data.validation;
    out.execution = exec.data.execution;
    out.chartType = exec.data.chartType;
    out.dataWarnings = exec.data.dataWarnings;
    out.interpretation = exec.data.interpretation;
    out.followups = exec.data.followups;
    let rep = null;
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        rep = await http("POST", "/analysis/report", { sessionId: out.sessionId, roundNo: out.roundNo });
        out.report = rep.data.report;
        break;
      } catch (err) {
        out.reportError = String(err && err.message ? err.message : err);
        if (attempt === 2) break;
        await new Promise((res) => setTimeout(res, 5000));
      }
    }
    out.verdict = verdict(q, parsed?.data, exec?.data, rep?.data);
  } catch (err) {
    out.error = String(err && err.message ? err.message : err);
    out.verdict = { pass: false, checks: [], fails: ["执行异常: " + out.error] };
  }
  return out;
}

async function main() {
  const login = await http("POST", "/auth/login", { username: "admin", password: "admin123" });
  token = login.data.token;
  console.log("login ok, questions:", questions.length, "concurrency:", concurrency, "from:", fromId || "-");

  const todo = [];
  for (const q of questions) {
    if (fromId && q.id < fromId) continue;
    if (existsSync(join(RESULTS_DIR, q.id + ".json"))) continue;
    todo.push(q);
    if (maxN && todo.length >= maxN) break;
  }
  console.log("todo:", todo.length);
  let done = 0;
  const pool = async (list, worker) => {
    let i = 0;
    const run = async () => {
      while (i < list.length) {
        const idx = i++;
        const q = list[idx];
        const r = await worker(q);
        writeFileSync(join(RESULTS_DIR, q.id + ".json"), JSON.stringify(r, null, 1), "utf-8");
        done++;
        const status = r.error ? "ERR " : r.verdict.pass ? "PASS" : "FAIL";
        console.log(`[${done}/${list.length}] ${status} ${q.id} ${q.question.slice(0, 40)}`);
      }
    };
    await Promise.all(Array.from({ length: concurrency }, run));
  };
  await pool(todo, runQuestion);
  console.log("ALL DONE, done:", done);
}

main().catch((e) => { console.error("FATAL", e); process.exit(1); });