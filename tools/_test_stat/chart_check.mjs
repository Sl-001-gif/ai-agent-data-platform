// chartOption 脚本级渲染验证：读取第4轮结果，逐题用真实 columns/rows 跑 buildChartOption，断言序列与坐标轴非空。
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
const __dir = dirname(fileURLToPath(import.meta.url));
const { buildChartOption } = await import("file:///D:/codestudy/大学项目/aiagent数据分析平台/frontend/src/utils/chartOption.js");
const results = readdirSync(join(__dir, "results")).filter((f) => f.endsWith(".json")).sort();
let pass = 0, fail = 0, empty = 0;
const fails = [];
for (const f of results) {
  const d = JSON.parse(readFileSync(join(__dir, "results", f), "utf-8"));
  const exec = d.execution || {};
  const cols = exec.columns || [];
  const rows = exec.rows || [];
  const chartType = d.chartType || d.plan?.chartType || "table";
  if (!rows || !rows.length) { empty++; continue; }
  try {
    const opt = buildChartOption(chartType, cols, rows, {});
    const series = opt?.series || [];
    const hasData = series.some((s) => Array.isArray(s.data) && s.data.length > 0);
    const xa = opt?.xAxis && (Array.isArray(opt.xAxis) ? opt.xAxis[0] : opt.xAxis);
    const xData = (xa && xa.data) || [];
    if (hasData && xData.length > 0) { pass++; }
    else { fail++; fails.push(d.id + " 无有效序列/坐标轴 rows=" + rows.length); }
  } catch (e) {
    fail++; fails.push(d.id + " 异常: " + String((e && e.message) || e).slice(0, 150));
  }
}
console.log("chartOption check: pass=" + pass + " fail=" + fail + " empty=" + empty);
if (fail) { console.log(fails.join("\n")); process.exit(1); }