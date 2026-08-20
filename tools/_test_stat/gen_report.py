# -*- coding: utf-8 -*-
# 生成汇总报告：results/*.json -> 汇总表.csv + 汇总报告.md
import json, os, glob, csv, re, sys

BASE = r"D:\codestudy\大学项目\aiagent数据分析平台\tools\_test_stat"
RES = os.path.join(BASE, "results")

def trunc(s, n):
    s = "" if s is None else str(s)
    s = re.sub(r"\s+", " ", s).strip()
    return s[:n] + ("…" if len(s) > n else "")

def key_numbers(rows, n=6):
    if not rows:
        return ""
    out = []
    for r in rows[:n]:
        parts = []
        for k in ("period", "region", "industry", "unit", "value", "growth_rate", "indicator_name"):
            if k in r and r[k] is not None:
                v = r[k]
                if isinstance(v, float):
                    v = round(v, 4)
                parts.append(str(v))
        out.append("/".join(parts))
    return " | ".join(out)

rows = []
for fp in sorted(glob.glob(os.path.join(RES, "*.json"))):
    with open(fp, "r", encoding="utf-8") as f:
        d = json.load(f)
    v = d.get("verdict", {})
    if d.get("error"):
        status = "ERR"
    elif v.get("pass"):
        status = "PASS"
    else:
        status = "FAIL"
    rows.append({
        "id": d.get("id", os.path.basename(fp)),
        "domain": d.get("domain", ""),
        "question": d.get("question", ""),
        "status": status,
        "chart": d.get("chartType") or "",
        "rowCount": v.get("rowCount", ""),
        "nums": key_numbers(d.get("execution", {}).get("rows") or []),
        "sql": trunc(d.get("sql"), 220),
        "interp": trunc((d.get("interpretation") or {}).get("text"), 160),
        "reportTitle": trunc((d.get("report") or {}).get("title"), 40),
        "gen": (d.get("interpretation") or {}).get("generatorType", ""),
        "fails": "；".join(v.get("fails") or []) or (d.get("error") or ""),
        "checks": "；".join(v.get("checks") or []),
    })

csv_path = os.path.join(BASE, "汇总表.csv")
with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
    w = csv.writer(f)
    w.writerow(["编号", "业务域", "问题", "判定", "图表类型", "行数", "关键数值", "SQL", "解读摘要", "报告标题", "检查项", "失败项"])
    for r in rows:
        w.writerow([r["id"], r["domain"], r["question"], r["status"], r["chart"], r["rowCount"], r["nums"], r["sql"], r["interp"], r["reportTitle"], r["checks"], r["fails"]])

n_pass = sum(1 for r in rows if r["status"] == "PASS")
n_fail = sum(1 for r in rows if r["status"] == "FAIL")
n_err = sum(1 for r in rows if r["status"] == "ERR")
md = []
md.append("# stat_monthly 深度测试汇总报告")
md.append("")
md.append(f"- 生成时间：{__import__('datetime').datetime.now().isoformat(timespec='seconds')}")
md.append(f"- 总用例：{len(rows)}　PASS：{n_pass}　FAIL：{n_fail}　ERR：{n_err}")
md.append("- 原始证据：`tools/_test_stat/results/{id}.json`（每问含 intent/plan/sql/execution/interpretation/report 全量）")
md.append("- 汇总表：`tools/_test_stat/汇总表.csv`（Excel 直接打开，可筛选）")
md.append("")
md.append("## 分业务域统计")
md.append("")
domains = {}
for r in rows:
    domains.setdefault(r["domain"], [0, 0, 0, 0])
    domains[r["domain"]][0] += 1
    domains[r["domain"]][1 + ["PASS", "FAIL", "ERR"].index(r["status"])] += 1
md.append("| 业务域 | 用例数 | PASS | FAIL | ERR |")
md.append("|---|---|---|---|---|")
for k in sorted(domains):
    c = domains[k]
    md.append(f"| {k} | {c[0]} | {c[1]} | {c[2]} | {c[3]} |")
md.append("")
md.append("## 逐用例明细")
md.append("")
md.append("| 编号 | 业务域 | 问题 | 判定 | 图表 | 行数 | 关键数值 | 失败/检查项 |")
md.append("|---|---|---|---|---|---|---|---|")
for r in rows:
    md.append(f"| {r['id']} | {r['domain']} | {r['question']} | {r['status']} | {r['chart']} | {r['rowCount']} | {r['nums']} | {r['fails'] or r['checks']} |")
md.append("")
md.append("## 人工检查指引")
md.append("")
md.append("1. 打开 `汇总表.csv`，先看「判定」列：PASS = 脚本断言通过（数值/非空/图表类型）；FAIL/ERR = 需人工复核。")
md.append("2. 对 FAIL：看「失败项」列定位（期望值未命中/解读空/报告空/执行异常），再打开对应 `results/{id}.json` 看 SQL 与原始行。")
md.append("3. 对 PASS 但想看细节：`results/{id}.json` 的 `interpretation.text` 与 `report.content` 可全文阅读；`followups` 为推荐追问。")
md.append("4. 数值核对基准：Excel 2025-09 月报（`%TEMP%\\stat_monthly_ground_truth.json`）。")
md.append("")
with open(os.path.join(BASE, "汇总报告.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(md))
print(f"rows={len(rows)} pass={n_pass} fail={n_fail} err={n_err}")
print("wrote:", csv_path)
print("wrote:", os.path.join(BASE, "汇总报告.md"))