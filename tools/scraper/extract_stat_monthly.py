# -*- coding: utf-8 -*-
"""提取统计月报爬取数据：stat_indicator(source_type='XLSX') -> 独立表 stat_monthly_xlsx + CSV。
幂等可重跑：重建表 + INSERT SELECT 原样提取，不修改原表。"""
import csv
import os
import sys
import pymysql

DB = dict(host="localhost", port=3306, user="root", password="Admin@123456",
          database="ai_agent_data", charset="utf8mb4")
CSV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "data", "stat_monthly_xlsx.csv")


def main():
    conn = pymysql.connect(**DB, autocommit=True)
    cur = conn.cursor()
    cur.execute("DROP TABLE IF EXISTS stat_monthly_xlsx")
    cur.execute("CREATE TABLE stat_monthly_xlsx LIKE stat_indicator")
    cur.execute("INSERT INTO stat_monthly_xlsx SELECT * FROM stat_indicator WHERE source_type='XLSX'")
    cur.execute("SELECT COUNT(*) FROM stat_monthly_xlsx")
    total = cur.fetchone()[0]
    print("已重建 stat_monthly_xlsx：共 %d 行（source_type=XLSX 原样提取）" % total)

    cur.execute("SELECT COUNT(DISTINCT period), COUNT(DISTINCT region), COUNT(DISTINCT indicator_name) FROM stat_monthly_xlsx")
    p, r, i = cur.fetchone()
    print("结构：%d 种 period / %d 个 region / %d 个 indicator_name" % (p, r, i))

    cur.execute("SELECT unit, COUNT(*) FROM stat_monthly_xlsx GROUP BY unit ORDER BY COUNT(*) DESC")
    units = cur.fetchall()
    print("单位分布：" + " | ".join("%s=%d" % (u or "空", n) for u, n in units))

    cur.execute("SELECT * FROM stat_monthly_xlsx")
    cols = [d[0] for d in cur.description]
    rows = cur.fetchall()
    os.makedirs(os.path.dirname(CSV_PATH), exist_ok=True)
    with open(CSV_PATH, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(cols)
        w.writerows(rows)
    print("已导出 CSV：%s（%d 行）" % (os.path.abspath(CSV_PATH), len(rows)))
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()