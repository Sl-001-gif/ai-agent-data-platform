# -*- coding: utf-8 -*-
"""
analyze_stat_indicator.py —— 邵阳统计指标库（stat_indicator）分析工具
数据源：tools/scraper/stat_scraper.py 结构化采集的「期间x指标x区县」长表（MySQL ai_agent_data）
输出：stdout 分析摘要 + docs/reports/ 下 CSV 导出
口径：以 XLSX 月报卡（confidence=high）为基准；公报/分析标注来源与置信度
"""
import csv
import os
import sys
from collections import OrderedDict

import pymysql

DB = dict(host="127.0.0.1", user="root", password="Admin@123456",
          database="ai_agent_data", charset="utf8mb4")
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "reports")
PERIOD_2025_9 = "2025年1-9月"
CITY = "全市"

# 核心指标口径（指标名 -> 展示名）
CORE_METRICS = OrderedDict([
    ("地区生产总值（GDP）", "地区生产总值（GDP）"),
    ("第一产业增加值", "第一产业增加值"),
    ("第二产业增加值", "第二产业增加值"),
    ("第三产业增加值", "第三产业增加值"),
    ("地方一般公共预算收入", "地方一般公共预算收入"),
    ("税收收入", "税收收入"),
    ("非税收入", "非税收入"),
    ("一般公共预算支出", "一般公共预算支出"),
    ("规模工业增加值", "规模工业增加值"),
    ("固定资产投资", "固定资产投资"),
    ("房地产开发投资", "房地产开发投资"),
    ("社会消费品零售总额", "社会消费品零售总额"),
    ("全体居民人均可支配收入", "全体居民人均可支配收入"),
    ("城镇居民人均可支配收入", "城镇居民人均可支配收入"),
    ("农村居民人均可支配收入", "农村居民人均可支配收入"),
])

MONTHS = ["2025年1-2月", "2025年1-3月", "2025年1-4月", "2025年1-5月",
          "2025年1-6月", "2025年1-7月", "2025年1-8月", "2025年1-9月"]

COUNTIES = ["双清区", "大祥区", "北塔区", "新邵县", "邵阳县", "隆回县",
            "洞口县", "绥宁县", "新宁县", "城步苗族自治县", "武冈市", "邵东市"]


def connect():
    return pymysql.connect(**DB)


def to_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def norm_wan_to_yi(value, unit):
    """万元 -> 亿元（值>10000 且单位为万元时换算），返回 (值, 单位)"""
    if unit == "万元" and value is not None and abs(value) >= 1000:
        return round(value / 10000.0, 2), "亿元"
    return value, unit


def fetch(conn, sql, args=None):
    cur = conn.cursor()
    cur.execute(sql, args)
    rows = cur.fetchall()
    cur.close()
    return rows


def asset_overview(conn):
    print("=" * 70)
    print("一、数据资产总览（stat_indicator）")
    print("=" * 70)
    rows = fetch(conn, "SELECT source_type, COUNT(*) FROM stat_indicator GROUP BY source_type")
    for r in rows:
        print(f"  {r[0]:10s} {r[1]:>7} 条")
    rows = fetch(conn, "SELECT COUNT(DISTINCT region), COUNT(DISTINCT period), COUNT(DISTINCT indicator_name) FROM stat_indicator")
    print(f"  区域 {rows[0][0]} 个 / 期间 {rows[0][1]} 个 / 指标名 {rows[0][2]} 个")
    rows = fetch(conn, "SELECT confidence, COUNT(*) FROM stat_indicator GROUP BY confidence")
    for r in rows:
        print(f"  置信度 {r[0]:8s} {r[1]:>7} 条")


def core_city(conn):
    print()
    print("=" * 70)
    print(f"二、全市核心指标（{PERIOD_2025_9}，XLSX 高置信）")
    print("=" * 70)
    rows = fetch(conn,
                 "SELECT indicator_name, value, unit, growth_rate FROM stat_indicator "
                 "WHERE source_type='XLSX' AND period=%s AND region=%s AND indicator_name IN "
                 "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                 (PERIOD_2025_9, CITY, *list(CORE_METRICS.keys())))
    out = []
    found = {}
    for name, value, unit, growth in rows:
        key = CORE_METRICS.get(name, name)
        if key not in found or (value is not None and found[key].get("value") is None):
            found[key] = dict(value=value, unit=unit, growth=growth)
    for display, meta in CORE_METRICS.items():
        d = found.get(display)
        if not d:
            print(f"  {display:16s} 无数据")
            continue
        v, u = norm_wan_to_yi(to_float(d["value"]), d["unit"])
        vstr = "-" if v is None else f"{v:,.2f} {u}"
        gstr = "-" if d["growth"] is None else f"{float(d['growth']):+.2f}%"
        print(f"  {display:16s} {vstr:>18s}   增速 {gstr}")
        out.append(dict(指标=display, 数值=v if v is not None else "", 单位=u or "",
                        同比增速=gstr))
    write_csv("全市核心指标_2025年1-9月.csv", out)

    # 三次产业占比（以第一/二/三产业增加值与 GDP 比值）
    gdp = found.get("地区生产总值（GDP）", {}).get("value")
    parts = [("第一产业增加值", found.get("第一产业增加值", {}).get("value")),
             ("第二产业增加值", found.get("第二产业增加值", {}).get("value")),
             ("第三产业增加值", found.get("第三产业增加值", {}).get("value"))]
    if gdp:
        gdp = to_float(gdp)
        print("  三次产业结构（增加值占比）：")
        for name, v in parts:
            v = to_float(v)
            if v:
                print(f"    {name:8s} {v/gdp*100:.1f}%")


def county_rank(conn):
    print()
    print("=" * 70)
    print(f"三、区县排名（{PERIOD_2025_9}，XLSX 高置信）")
    print("=" * 70)
    metrics = {
        "地区生产总值": "GDP",
        "地方一般公共预算收入": "财政收入",
        "规模工业增加值": "规上工业增速",
        "社会消费品零售总额": "社零",
        "全体居民人均可支配收入": "居民收入",
    }
    out = []
    for name, label in metrics.items():
        rows = fetch(conn,
                     "SELECT region, value, unit, growth_rate FROM stat_indicator "
                     "WHERE source_type='XLSX' AND period=%s AND indicator_name=%s AND region<>%s",
                     (PERIOD_2025_9, name, CITY))
        items = []
        for region, value, unit, growth in rows:
            v, u = norm_wan_to_yi(to_float(value), unit)
            items.append(dict(region=region, value=v, unit=u, growth=growth))
        # 去重（同一指标可能出现于多个 sheet）
        seen = {}
        for it in items:
            key = it["region"]
            if key not in seen or (it["value"] is not None and seen[key]["value"] is None):
                seen[key] = it
        items = list(seen.values())
        sort_key = (lambda x: x["growth"]) if name == "规模工业增加值" else (lambda x: x["value"] or -1e18)
        items.sort(key=sort_key, reverse=True)
        print(f"\n  ◆ {label} 排名（{name}）")
        for i, it in enumerate(items, 1):
            vstr = "-" if it["value"] is None else f"{it['value']:,.2f} {it['unit'] or ''}".strip()
            gstr = "-" if it["growth"] is None else f"{float(it['growth']):+.2f}%"
            print(f"    {i:>2}. {it['region']:8s} {vstr:>16s}   增速 {gstr}")
            out.append(dict(指标=label, 排名=i, 区县=it["region"], 数值=vstr, 增速=gstr))
    write_csv("区县排名_2025年1-9月.csv", out)


def xinning_profile(conn):
    print()
    print("=" * 70)
    print("四、新宁县画像（2025年1-9月）")
    print("=" * 70)
    rows = fetch(conn,
                 "SELECT indicator_name, value, unit, growth_rate FROM stat_indicator "
                 "WHERE source_type='XLSX' AND period=%s AND region='新宁县' AND indicator_name NOT LIKE '%%排名' "
                 "ORDER BY indicator_name", (PERIOD_2025_9,))
    rank_rows = fetch(conn,
                      "SELECT indicator_name, value FROM stat_indicator "
                      "WHERE source_type='XLSX' AND period=%s AND region='新宁县' AND indicator_name LIKE '%%排名'",
                      (PERIOD_2025_9,))
    ranks = {r[0].replace("排名", ""): int(r[1]) for r in rank_rows}
    for name, value, unit, growth in rows:
        v, u = norm_wan_to_yi(to_float(value), unit)
        vstr = "-" if v is None else f"{v:,.2f} {u or ''}".strip()
        gstr = "-" if growth is None else f"{float(growth):+.2f}%"
        rk = ranks.get(name)
        rkstr = f"（全市第{rk}）" if rk else ""
        print(f"  {name:16s} {vstr:>16s}   增速 {gstr:>8s} {rkstr}")


def monthly_trend(conn):
    print()
    print("=" * 70)
    print("五、2025 年月度累计趋势（XLSX 高置信，全市）")
    print("=" * 70)
    indicators = ["地区生产总值（GDP）", "规模工业增加值", "固定资产投资", "地方一般公共预算收入",
                  "社会消费品零售总额", "全体居民人均可支配收入"]
    out = []
    for m in MONTHS:
        ph = ",".join(["%s"] * len(indicators))
        rows = fetch(conn,
                     "SELECT indicator_name, value, unit, growth_rate FROM stat_indicator "
                     "WHERE source_type='XLSX' AND period=%s AND region=%s AND indicator_name IN (" + ph + ")",
                     (m, CITY, *indicators))
        found = {}
        for name, value, unit, growth in rows:
            if name not in found:
                found[name] = []
            found[name].append((value, unit, growth))
        line = [m.replace("2025年", "").replace("1-", "").replace("月", "月累计").replace("累计", "M")]
        line = [m]
        for ind in indicators:
            vals = found.get(ind, [])
            if not vals:
                line.append("-")
                continue
            # 重复行取第一个并标注
            value, unit, growth = vals[0]
            if ind in ("地区生产总值（GDP）", "社会消费品零售总额"):
                v, u = norm_wan_to_yi(to_float(value), unit)
                txt = "-" if v is None else f"{v:,.1f}"
            else:
                txt = "-"
            if growth is not None:
                txt = (txt + "/" if txt != "-" else "") + f"{float(growth):+.1f}%"
            if len(vals) > 1:
                txt += "×" + str(len(vals))
            line.append(txt)
        print("  " + " | ".join(line))
        out.append(dict(期间=m, **{indicators[i]: line[i + 1] for i in range(len(indicators))}))
    write_csv("月度趋势_2025.csv", out)


def annual_gdp(conn):
    print()
    print("=" * 70)
    print("六、年度 GDP 序列（2018-2025，XLSX 为基准；2022 后补公报口径）")
    print("=" * 70)
    out = []
    for y in ["2018年", "2019年", "2020年", "2021年", "2022年", "2023年", "2024年", "2025年"]:
        rows = fetch(conn,
                     "SELECT indicator_name, value, unit, sheet_name FROM stat_indicator "
                     "WHERE source_type='XLSX' AND period=%s AND region=%s AND indicator_name=%s "
                     "ORDER BY value DESC LIMIT 1",
                     (y, CITY, "生产总值（GDP）"))
        if not rows:
            rows = fetch(conn,
                         "SELECT indicator_name, value, unit, sheet_name FROM stat_indicator "
                         "WHERE source_type='XLSX' AND period=%s AND region=%s AND indicator_name=%s "
                         "ORDER BY value DESC LIMIT 1",
                         (y, CITY, "地区生产总值（GDP）"))
        src = "XLSX"
        if not rows:
            # 公报兜底：亿元单位、量级 2000-4000 的全市口径
            rows = fetch(conn,
                         "SELECT indicator_name, value, unit, sheet_name FROM stat_indicator "
                         "WHERE source_type='BULLETIN' AND period=%s AND region=%s "
                         "AND indicator_name=%s AND unit='亿元' AND value BETWEEN 1500 AND 4500 "
                         "ORDER BY value DESC LIMIT 1",
                         (y, CITY, "地区生产总值（GDP）"))
            src = "BULLETIN"
        if not rows:
            print(f"  {y}: 无可靠数据")
            out.append(dict(年度=y, GDP= "", 来源="无"))
            continue
        name, value, unit, sheet = rows[0]
        print(f"  {y}: GDP {float(value):,.2f} 亿元（{src}）")
        out.append(dict(年度=y, GDP=float(value), 来源=src))
    write_csv("年度GDP序列.csv", out)


def write_csv(fname, rows):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, fname)
    if not rows:
        return
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"  [CSV] {os.path.relpath(path)}")


def main():
    conn = connect()
    try:
        asset_overview(conn)
        core_city(conn)
        county_rank(conn)
        xinning_profile(conn)
        monthly_trend(conn)
        annual_gdp(conn)
    finally:
        conn.close()
    print()
    print("完成：CSV 已导出到 docs/reports/")


if __name__ == "__main__":
    main()