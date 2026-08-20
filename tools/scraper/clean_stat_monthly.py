# -*- coding: utf-8 -*-
"""统计月报数据整理：stat_monthly_xlsx -> stat_monthly（干净版）。规则：
  1. 剔除污染行（indicator_name 实为区县/市名/合计行） 2. 去重 3. 单位推断补齐。
幂等重建，不动源表。"""
import csv
import os
import re
from collections import Counter

import pymysql

DB = dict(host="localhost", port=3306, user="root", password="Admin@123456",
          database="ai_agent_data", charset="utf8mb4")
SRC = "stat_monthly_raw"
DST = "stat_monthly"
OUT_CSV = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "data", "stat_monthly.csv")

REGION_KEYS = ["全市", "双清", "大祥", "北塔", "新邵", "邵阳县", "隆回", "洞口", "绥宁", "新宁", "城步",
               "武冈", "邵东", "市辖区", "市本级", "双清区", "大祥区", "北塔区", "新邵县", "隆回县",
               "洞口县", "绥宁县", "新宁县", "城步苗族自治县", "武冈市", "邵东市", "邵阳", "邵阳市"]
CITY_KEYS = ["长沙市", "株洲市", "湘潭市", "衡阳市", "岳阳市", "常德市", "张家界市", "益阳市", "郴州市",
             "永州市", "怀化市", "娄底市", "湘西自治州", "全省合计"]
POLLUTED = set(REGION_KEYS) | set(CITY_KEYS)

UNIT_RULES = [
    (r"人均可支配收入", "元"),
    (r"排名$", "名"),
    (r"外商直接投资", "万美元"),
    (r"地方一般公共预算收入|一般公共预算支出", "万元"),
    (r"GDP|生产总值|产业投资|固定资产投资|工业投资|房地产开发投资|高技术产业投资|基础设施建设投资"
     r"|社会消费品零售总额|进出口|出口|金融机构本外币存款|金融机构本外币贷款", "亿元"),
]

COLS = ["period", "region", "indicator_code", "indicator_name", "value", "unit", "growth_rate",
        "sheet_name", "source_type", "confidence", "generator_type"]


def std_unit(indicator):
    for pat, u in UNIT_RULES:
        if re.search(pat, indicator):
            return u
    return ""

def name_unit(indicator):
    """指标名自带单位：保险收入(万元) -> 万元"""
    m = re.search(r"[（(]([^（）()]{1,12})[）)]", indicator or "")
    if m:
        u = m.group(1).strip()
        if re.search(r"[\u4e00-\u9fa5%]", u):
            return u
    return ""


def main():
    conn = pymysql.connect(**DB, autocommit=True)
    cur = conn.cursor()
    cur.execute("SELECT stat_doc_id, %s FROM %s" % (", ".join(COLS), SRC))
    raw = cur.fetchall()
    print("输入：%d 行" % len(raw))

    dropped = 0
    keep = []
    for r in raw:
        name = re.sub(r"\s+", "", r[4])
        if name in POLLUTED:
            dropped += 1
            continue
        keep.append(r)
    print("剔除污染行：%d" % dropped)

    seen = set()
    dedup = []
    dup = 0
    for r in keep:
        key = (r[1], r[2], r[3], r[5], r[7])
        if key in seen:
            dup += 1
            continue
        seen.add(key)
        dedup.append(r)
    print("去重剔除：%d，去重后：%d" % (dup, len(dedup)))

    unit_counter = Counter()
    for r in dedup:
        u = (r[6] or "").strip()
        if u:
            unit_counter[(r[4], u)] += 1
    by_ind = {}
    for (ind, u), n in unit_counter.items():
        by_ind.setdefault(ind, []).append((n, u))
    mode_unit = {ind: max(lst)[1] for ind, lst in by_ind.items()}

    filled = still_empty = 0
    out = []
    for r in dedup:
        unit = (r[6] or "").strip()
        if not unit:
            unit = name_unit(r[4]) or mode_unit.get(r[4]) or std_unit(r[4])
            if unit:
                filled += 1
            else:
                still_empty += 1
        out.append((r[0], r[1], r[2], r[3], r[4], r[5], unit or None, r[7], r[8], r[9], r[10], r[11]))
    empty_period = sum(1 for r in out if not (r[2] or "").strip())
    print("单位补齐：%d，仍缺单位：%d，空 period：%d" % (filled, still_empty, empty_period))

    cur.execute("DROP TABLE IF EXISTS %s" % DST)
    cur.execute("CREATE TABLE %s LIKE %s" % (DST, SRC))
    sql = ("INSERT INTO %s (stat_doc_id, %s) VALUES (%s)" % (DST, ", ".join(COLS), ", ".join(["%s"] * (len(COLS) + 1))))
    cur.executemany(sql, out)
    print("已写入 %s：%d 行" % (DST, len(out)))

    os.makedirs(os.path.dirname(OUT_CSV), exist_ok=True)
    with open(OUT_CSV, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["stat_doc_id"] + COLS)
        w.writerows(out)
    print("CSV：%s" % os.path.abspath(OUT_CSV))

    top = Counter(r[4] for r in out)
    print("Top 指标：")
    for ind, n in top.most_common(12):
        print("  %-24s x%d" % (ind[:24], n))
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()