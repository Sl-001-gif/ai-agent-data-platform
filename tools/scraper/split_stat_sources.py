# -*- coding: utf-8 -*-
"""三源数据分离：stat_indicator 按 source_type 拆为独立表（幂等重建，不修改原表）。
  stat_monthly_xlsx <- XLSX   （统计月报附件表格）
  stat_bulletin    <- BULLETIN（统计公报正文抽取）
  stat_analysis    <- ANALYSIS（统计分析正文抽取）
"""
import csv
import os
import pymysql

DB = dict(host="localhost", port=3306, user="root", password="Admin@123456",
          database="ai_agent_data", charset="utf8mb4")
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "data")
SOURCES = [
    ("stat_monthly_xlsx", "XLSX", "stat_monthly_xlsx.csv"),
    ("stat_bulletin", "BULLETIN", "stat_bulletin.csv"),
    ("stat_analysis", "ANALYSIS", "stat_analysis.csv"),
]


def main():
    conn = pymysql.connect(**DB, autocommit=True)
    cur = conn.cursor()
    os.makedirs(OUT_DIR, exist_ok=True)
    for table, source, csv_name in SOURCES:
        cur.execute("DROP TABLE IF EXISTS %s" % table)
        cur.execute("CREATE TABLE %s LIKE stat_indicator" % table)
        cur.execute("INSERT INTO %s SELECT * FROM stat_indicator WHERE source_type=%%s" % table, (source,))
        cur.execute("SELECT COUNT(*) FROM %s" % table)
        total = cur.fetchone()[0]
        cur.execute("SELECT * FROM %s" % table)
        cols = [d[0] for d in cur.description]
        rows = cur.fetchall()
        path = os.path.join(OUT_DIR, csv_name)
        with open(path, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            w.writerow(cols)
            w.writerows(rows)
        print("[%s] <- %s：%d 行，CSV=%s" % (table, source, total, os.path.abspath(path)))
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()