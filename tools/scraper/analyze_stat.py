# -*- coding: utf-8 -*-
"""统计栏目结构化分析：对 gov_info_record 中 统计月报/统计公报/统计分析 三栏目做分析并可选入库。

用法：
    python analyze_stat.py             # 仅打印分析结果
    python analyze_stat.py --store     # 幂等写入 analysis_result 表（先删同名再插入）
    python analyze_stat.py --store --name "统计栏目发布分析"

输出结果 DBeaver 可直接查询 analysis_result 表。
"""
import argparse
import re
import pymysql
from collections import Counter

CATS = ["统计月报", "统计公报", "统计分析"]
DB = dict(host="localhost", port=3306, user="root", password="Admin@123456",
          database="ai_agent_data", charset="utf8mb4")

DDL = """CREATE TABLE IF NOT EXISTS analysis_result (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  analysis_name VARCHAR(200) NOT NULL COMMENT '分析名称',
  dimension VARCHAR(100) NOT NULL COMMENT '维度：类目占比/年份分布/月趋势/主题词Top',
  dim_value VARCHAR(200) NOT NULL COMMENT '维度值：类目名/年份/年月/关键词',
  metric_value DECIMAL(14,4) DEFAULT NULL COMMENT '指标值',
  metric_unit VARCHAR(50) DEFAULT '条' COMMENT '指标单位',
  remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_name (analysis_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结构化分析结果表'"""


def run(cur, sql, args=None):
    cur.execute(sql, args)
    return cur.fetchall()


def analyze(cur):
    rows = []
    mark = ",".join(["%s"] * len(CATS))

    # 0) 总量与时间范围
    total = run(cur, "SELECT COUNT(*) FROM gov_info_record WHERE category IN (%s)" % mark, CATS)[0][0]
    scope = run(cur, "SELECT MIN(publish_date), MAX(publish_date) FROM gov_info_record WHERE category IN (%s) AND publish_date IS NOT NULL" % mark, CATS)[0]
    rows.append(("数据规模", "统计三栏目总条数", total, "条",
                 "时间范围 %s ~ %s" % (scope[0], scope[1]) if scope[0] else ""))

    # 1) 类目占比
    for cat, cnt, ratio in run(cur,
            "SELECT category, COUNT(*), COUNT(*)*100.0/%s FROM gov_info_record WHERE category IN (%s) GROUP BY category ORDER BY cnt DESC" % ("%s" if False else str(total), mark), CATS) if False else []:
        pass
    for r in run(cur,
            "SELECT category, COUNT(*) FROM gov_info_record WHERE category IN (%s) GROUP BY category ORDER BY COUNT(*) DESC" % mark, CATS):
        cat, cnt = r[0], r[1]
        rows.append(("类目占比", cat, cnt, "条", "占比 %.1f%%" % (cnt * 100.0 / total)))

    # 2) 年份分布
    for r in run(cur,
            "SELECT category, YEAR(publish_date), COUNT(*) FROM gov_info_record WHERE category IN (%s) AND publish_date IS NOT NULL GROUP BY category, YEAR(publish_date) ORDER BY category, YEAR(publish_date)" % mark, CATS):
        rows.append(("年份分布", "%s/%s" % (r[0], r[1]), r[2], "条", ""))

    # 3) 近3年月趋势（合计）
    sql3 = ("SELECT DATE_FORMAT(publish_date,'%%Y-%%m'), COUNT(*) "
            "FROM gov_info_record WHERE category IN (%s, %s, %s) AND publish_date >= '2024-01-01' "
            "GROUP BY DATE_FORMAT(publish_date,'%%Y-%%m') ORDER BY DATE_FORMAT(publish_date,'%%Y-%%m')")
    for r in run(cur, sql3, CATS):
        rows.append(("近3年月趋势", r[0], r[1], "条", ""))

    # 4) 主题词 Top10
    titles = [t[0] for t in run(cur, "SELECT title FROM gov_info_record WHERE category IN (%s)" % mark, CATS)]
    stop = {"邵阳市", "我市", "全市", "经济", "运行", "情况", "报告", "分析", "统计", "公报", "月报",
            "年", "月", "关于", "发布", "信息", "数据", "工作", "开展", "完成", "增长", "湖南省", "湖南"}
    cnt = Counter()
    for t in titles:
        for w in re.findall(r"[\u4e00-\u9fff]{2,6}", t):
            if w not in stop:
                cnt[w] += 1
    for w, c in cnt.most_common(10):
        rows.append(("主题词Top", w, c, "次", ""))
    return rows, total


def store(cur, name, rows):
    cur.execute("DELETE FROM analysis_result WHERE analysis_name = %s", (name,))
    sql = ("INSERT INTO analysis_result (analysis_name, dimension, dim_value, metric_value, metric_unit, remark) "
           "VALUES (%s, %s, %s, %s, %s, %s)")
    for dimension, dim_value, metric_value, unit, remark in rows:
        cur.execute(sql, (name, dimension, dim_value, metric_value, unit, remark))
    return len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", action="store_true", help="幂等写入 analysis_result 表")
    ap.add_argument("--name", default="统计栏目发布分析", help="分析名称（入库用，默认：统计栏目发布分析）")
    args = ap.parse_args()

    conn = pymysql.connect(**DB, autocommit=True)
    cur = conn.cursor()
    cur.execute(DDL)
    rows, total = analyze(cur)
    for dimension, dim_value, metric_value, unit, remark in rows:
        print("  [%s] %s = %s %s  %s" % (dimension, dim_value, metric_value, unit, remark))
    if args.store:
        n = store(cur, args.name, rows)
        print("已入库 analysis_result：分析名称=%s，共 %d 行（先删同名再插入，幂等）" % (args.name, n))
    else:
        print("（提示：加 --store 可将以上结果入库 analysis_result 表）")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()