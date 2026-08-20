const path = require("path");
const { chromium } = require("playwright-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const BASE = "http://localhost:5173";

const results = [];
const consoleErrors = [];
const pageErrors = [];

function log(msg) { console.log(msg); }

async function readChartOption(page) {
  return page.evaluate(async () => {
    const out = { ok: false, error: null, yAxisCount: null, yAxisNames: [], series: [], title: "", titleRaw: null, xAxisData: [], canvases: [] };
    try {
      const modUrl = performance.getEntriesByType("resource")
        .map((e) => e.name)
        .find((n) => n.indexOf("/node_modules/.vite/deps/echarts") >= 0);
      if (!modUrl) { out.error = "echarts module url not found"; return out; }
      const echarts = await import(modUrl);
      const canvases = Array.from(document.querySelectorAll("canvas")).filter((c) => c.width > 100);
      for (const c of canvases) {
        let dom = c;
        while (dom && !dom.getAttribute("_echarts_instance_")) dom = dom.parentElement;
        const inst = dom ? echarts.getInstanceByDom(dom) : null;
        let info = { w: c.width, h: c.height, inst: !!inst };
        if (inst) {
          const opt = inst.getOption();
          info.title = (opt.title && (Array.isArray(opt.title) ? opt.title[0] : opt.title).text) || "";
          const xa = opt.xAxis ? (Array.isArray(opt.xAxis) ? opt.xAxis[0] : opt.xAxis) : null;
          info.xLen = (xa && xa.data && xa.data.length) || 0;
          info.yCount = Array.isArray(opt.yAxis) ? opt.yAxis.length : (opt.yAxis ? 1 : 0);
          info.series = (opt.series || []).map((s) => s.name + (s.yAxisIndex ? ":" + s.yAxisIndex : ""));
        }
        out.canvases.push(info);
      }
      const canv = canvases[canvases.length - 1];
      let dom = canv;
      while (dom && !dom.getAttribute("_echarts_instance_")) dom = dom.parentElement;
      if (!dom) { out.error = "echarts dom (with _echarts_instance_) not found"; return out; }
      const inst = echarts.getInstanceByDom(dom);
      if (!inst) { out.error = "echarts instance not found on dom"; return out; }
      const opt = inst.getOption();
      const yAxis = Array.isArray(opt.yAxis) ? opt.yAxis : [opt.yAxis];
      out.yAxisCount = yAxis.length;
      out.yAxisNames = yAxis.map((a) => (a && a.name) || "");
      out.series = (opt.series || []).map((s) => ({
        name: s.name,
        type: s.type,
        yAxisIndex: s.yAxisIndex,
        dashed: !!(s.lineStyle && s.lineStyle.type === "dashed"),
        dataLen: Array.isArray(s.data) ? s.data.length : 0,
      }));
      out.titleRaw = JSON.stringify(opt.title);
      const t = Array.isArray(opt.title) ? opt.title[0] : opt.title;
      out.title = (t && t.text) || "";
      const xa = opt.xAxis ? (Array.isArray(opt.xAxis) ? opt.xAxis[0] : opt.xAxis) : null;
      out.xAxisData = (xa && xa.data) || [];
      out.ok = true;
    } catch (e) {
      out.error = String((e && e.stack) || e);
    }
    return out;
  });
}

async function runScenario(page, question, expect) {
  log("=== scenario: " + question + " ===");
  const textarea = page.getByPlaceholder("请输入本次要分析的问题");
  await textarea.fill(question);
  await page.getByRole("button", { name: "执行SQL" }).click();
  await page.getByText("分析结果概览").waitFor({ timeout: 120000 });
  await page.getByRole("tab", { name: "推荐图表" }).click();
  await page.waitForFunction(
    () => Array.from(document.querySelectorAll("canvas")).some((c) => c.width > 100),
    null,
    { timeout: 30000 }
  );
  await page.waitForTimeout(800);
  const info = await readChartOption(page);
  info.question = question;
  results.push(info);
  log(JSON.stringify(info));
  const passed = expect(info);
  log("=> assert: " + (passed ? "PASS" : "FAIL"));
  return passed;
}

(async () => {
  const browser = await chromium.launch({ executablePath: EDGE, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  await page.goto(BASE + "/login", { waitUntil: "domcontentloaded" });
  await page.getByPlaceholder("用户名").fill("admin");
  await page.getByPlaceholder("密码").fill("admin123");
  await page.getByRole("button", { name: "登 录" }).click();
  await page.waitForURL(/(home|analysis)/, { timeout: 30000 });
  await page.goto(BASE + "/analysis", { waitUntil: "domcontentloaded" });
  await page.getByPlaceholder("请输入本次要分析的问题").waitFor({ timeout: 30000 });

  const p1 = await runScenario(page, "各区县进出口总额增速排名", (i) => {
    return i.ok
      && i.yAxisCount >= 2
      && i.yAxisNames.some((n) => String(n).indexOf("增速") >= 0)
      && i.series.some((s) => s.dashed && s.yAxisIndex > 0 && String(s.name).indexOf("增速") >= 0)
      && i.series.length >= 2
      && i.xAxisData.length >= 12;
  });

  const p2 = await runScenario(page, "邵阳市进出口总额趋势", (i) => {
    if (!i.ok) return false;
    if (i.title.indexOf("口径") < 0) return false;
    const re = /^(\d{4})年(\d{1,2})月-(\d{1,2})月$/;
    const spans = i.xAxisData.map((x) => { const m = String(x).match(re); return m ? m[2] + "-" + m[3] : null; });
    if (!spans.length || spans.some((s) => s === null)) return false;
    const consistent = spans.every((s) => s === spans[0]);
    return consistent && spans.length >= 2;
  });

  log("=== summary ===");
  log("scenario1 (增速排名 -> 独立增速右轴): " + (p1 ? "PASS" : "FAIL"));
  log("scenario2 (趋势 -> 粒度守卫口径标注): " + (p2 ? "PASS" : "FAIL"));
  log("console errors: " + JSON.stringify(consoleErrors));
  log("page errors: " + JSON.stringify(pageErrors));
  await browser.close();
  process.exit(p1 && p2 ? 0 : 1);
})().catch((e) => { console.error("FATAL", e); process.exit(2); });
