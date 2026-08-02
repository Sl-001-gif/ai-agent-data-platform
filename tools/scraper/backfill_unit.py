# -*- coding: utf-8 -*-
"""
政务数据「发文单位」三级补齐脚本
=================================

背景：gov_info_record.publish_unit 全表为空（3601/3601），「单位发文量排名」模板会得到
全 NULL 分组。本脚本按三级口径幂等补齐：

  A. 详情页真实单位（--mode detail，需联网）
     逐条抓取记录详情页正文，正则提取 发布单位/发文机关/公开单位/信息来源 等字段；
     只对 publish_unit 为空的记录抓取，抓到即按主键更新，绝不覆盖已有值。
  B. 域名推断（--mode local，无需联网）
     部门子站域名 -> 单位名（如 fgw.shaoyang.gov.cn -> 邵阳市发展和改革委员会）。
  C. 类目代理兜底（--mode local，无需联网）
     仍无单位的记录按公开类目归属近似单位（仅用于演示排名；口径已在
     metric_definition「单位发文量」中标注为近似推断）。

幂等：A/B/C 均只填空位，可反复运行。
附加：末尾幂等同步 metric_definition 口径说明（与 gov_scraper.py._METRICS_META 保持一致，
修订口径时两处需同步修改）。

用法：
    python backfill_unit.py --mode local              # B+C，无需联网，秒级完成
    python backfill_unit.py --mode detail --limit 500 # A，联网，可分批
    python backfill_unit.py --dry-run --mode local    # 只统计不落库
依赖：requests、pymysql（与 gov_scraper.py 相同；缺失时仅影响联网/落库功能，纯映射函数可用）
"""

import argparse
import os
import re
import sys
import time
from urllib.parse import urlparse

try:
    import requests
except ImportError:  # 未安装时仅联网功能不可用，映射/单测不受影响
    requests = None

try:
    import pymysql
except ImportError:  # 未安装时仅落库功能不可用
    pymysql = None

# ============================================================
# CONFIG：数据库连接与请求参数（可用环境变量覆盖，与 gov_scraper.py 一致）
# ============================================================
CONFIG = {
    "mysql": {
        "host": os.environ.get("MYSQL_HOST", "localhost"),
        "port": int(os.environ.get("MYSQL_PORT", "3306")),
        "user": os.environ.get("MYSQL_USER", "root"),
        "password": os.environ.get("MYSQL_PASSWORD", "Admin@123456"),
        "db": os.environ.get("MYSQL_DB", "ai_agent_data"),
        "charset": "utf8mb4",
    },
    # 请求间隔（秒），礼貌抓取（环境变量：SCRAPER_INTERVAL）
    "request_interval": float(os.environ.get("SCRAPER_INTERVAL", "0.5")),
    "request_timeout": 15,
    "user_agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    ),
}

# ============================================================
# B. 部门子站域名 -> 单位名（事实口径）
# ============================================================
UNIT_DOMAIN_MAP = {
    "fgw.shaoyang.gov.cn": "邵阳市发展和改革委员会",
    "wjw.shaoyang.gov.cn": "邵阳市卫生健康委员会",
    "www.hunan.gov.cn": "湖南省人民政府办公厅",
    "www.gov.cn": "国务院办公厅",
    "www.stats.gov.cn": "国家统计局",
    "tjj.hunan.gov.cn": "湖南省统计局",
    "wqb.hunan.gov.cn": "湖南省人民政府外事办公室",
    "wsxf.hunan.gov.cn": "湖南省网上信访受理平台",
    "mp.weixin.qq.com": "邵阳市人民政府门户网站微信公众号",
    "www.zyshgzb.gov.cn": "中央社会工作部",
}

# ============================================================
# C. 公开类目 -> 近似单位（代理兜底，仅用于演示排名；口径已标注）
# ============================================================
CATEGORY_UNIT_MAP = {
    "重大会议信息": "邵阳市人民政府办公室",
    "政府重大会议解读": "邵阳市人民政府办公室",
    "通知公告": "邵阳市人民政府办公室",
    "重点民生实事": "邵阳市人民政府办公室",
    "文件库": "邵阳市人民政府办公室",
    "机构简介": "邵阳市人民政府办公室",
    "主动公开事项目录": "邵阳市人民政府办公室",
    "政府工作部门": "邵阳市人民政府办公室",
    "部门法定主动公开矩阵": "邵阳市人民政府办公室",
    "其他单位": "邵阳市人民政府办公室",
    "上级政府及部门规范性文件": "邵阳市人民政府办公室",
    "重大项目": "邵阳市发展和改革委员会",
    "行政事业收费和物价调控": "邵阳市发展和改革委员会",
    "规划计划": "邵阳市发展和改革委员会",
    "财政信息": "邵阳市财政局",
    "部门预决算和“三公”经费": "邵阳市财政局",
    "部门预算绩效": "邵阳市财政局",
    "其他资金": "邵阳市财政局",
    "统计信息": "邵阳市统计局",
    "事业单位年度报告公示": "邵阳市事业单位登记管理局",
    "事业单位登记服务公示": "邵阳市事业单位登记管理局",
    "规范性文件解读": "邵阳市司法局",
    "市直部门规范性文件": "邵阳市司法局",
    "法治政府建设年度报告": "邵阳市司法局",
    "市政府规范性文件": "邵阳市人民政府",
    "市政府办规范性文件": "邵阳市人民政府办公室",
    "政策文件": "邵阳市人民政府",
    "规章库": "邵阳市人民政府",
}

# ============================================================
# 详情页单位提取正则（A）
# ============================================================
# 带「单位」标签的行：信息发布单位/信息提供单位/来源单位 等最长标签排前
_UNIT_LABEL_RE = re.compile(
    r"(?:信息发布单位|信息提供单位|来源单位|发布单位|发文机关|责任单位|发文单位|公开单位)"
    r"\s*[:：]?\s*([\u4e00-\u9fa5（）()]{2,40})"
)
# 信息来源/消息来源 行（来源多为单位名）
_UNIT_SOURCE_RE = re.compile(
    r"(?:信息来源|消息来源)\s*[:：]?\s*([\u4e00-\u9fa5（）()]{2,40})"
)
# 页面 meta 声明（如 publishunit/public_unit/source_unit）
_UNIT_META_RE = re.compile(
    r"<meta[^>]+name=[\"'](?:publishunit|public_unit|source_unit)[\"'][^>]+content=[\"']([^\"']{2,60})[\"']",
    re.IGNORECASE,
)


def host_of(url):
    """取 URL 主机名（小写），解析失败返回空串。"""
    try:
        return (urlparse(url or "").netloc or "").lower()
    except ValueError:
        return ""


def infer_unit(url, category):
    """三级推断（B 域名优先，C 类目兜底）；均无命中返回空串。"""
    host = host_of(url)
    if host in UNIT_DOMAIN_MAP:
        return UNIT_DOMAIN_MAP[host]
    if category:
        return CATEGORY_UNIT_MAP.get(category, "")
    return ""


def extract_unit(text):
    """从详情页文本提取发布单位；找不到返回空串。"""
    text = text or ""
    for pattern in (_UNIT_LABEL_RE, _UNIT_SOURCE_RE, _UNIT_META_RE):
        m = pattern.search(text)
        if m:
            return m.group(1).strip()
    return ""


# ============================================================
# metric_definition 口径修订（与 gov_scraper.py._METRICS_META 同步，修订时两处一起改）
# ============================================================
METRICS_SYNC = [
    {
        "name": "发文量",
        "description": "政务信息公开平台累计发布的政务信息条数，反映信息公开总体规模"
                       "（口径：直接统计全表记录数，栏目壳页与导航噪音已清理不在其中）",
        "calculation_formula": "SELECT COUNT(*) AS cnt FROM gov_info_record",
    },
    {
        "name": "类目占比",
        "description": "各公开类目发文量占全部发文量的比例（分母=全表记录数），反映信息公开的结构分布",
        "calculation_formula": (
            "SELECT category, COUNT(*) AS cnt, "
            "ROUND(COUNT(*) / (SELECT COUNT(*) FROM gov_info_record), 4) AS ratio "
            "FROM gov_info_record WHERE category IS NOT NULL AND category <> '' GROUP BY category"
        ),
    },
    {
        "name": "平均每日发文量",
        "description": "按最早与最晚发布日期区间计算的日均发文量，反映发布时效"
                       "（注意：数据跨度 2005~2026 较长，日均值会被长区间稀释，解读请结合分月趋势）",
        "calculation_formula": (
            "SELECT ROUND(COUNT(*) / NULLIF(DATEDIFF(MAX(publish_date), MIN(publish_date)), 0), 2) AS avg_daily "
            "FROM gov_info_record WHERE publish_date IS NOT NULL"
        ),
    },
    {
        "name": "单位发文量",
        "description": "各公开单位发布的政务信息条数，反映各单位信息公开活跃度"
                       "（口径：详情页真实单位 -> 部门子站域名推断 -> 类目代理兜底；"
                       "类目代理为近似推断，仅作演示参考）",
        "calculation_formula": (
            "SELECT COALESCE(NULLIF(publish_unit,''), category) AS unit, COUNT(*) AS cnt "
            "FROM gov_info_record GROUP BY unit ORDER BY cnt DESC LIMIT 10"
        ),
    },
]

# ============================================================
# 网络与数据库
# ============================================================


def make_session():
    if requests is None:
        raise RuntimeError("缺少依赖 requests，请先执行：pip install requests")
    session = requests.Session()
    session.headers.update({"User-Agent": CONFIG["user_agent"]})
    return session


def fetch(session, url):
    """抓取页面文本（自动纠正编码），非 200 抛错。"""
    resp = session.get(url, timeout=CONFIG["request_timeout"])
    if resp.status_code != 200:
        raise RuntimeError("HTTP %d" % resp.status_code)
    if not resp.encoding or resp.encoding.lower() == "iso-8859-1":
        resp.encoding = resp.apparent_encoding or "utf-8"
    return resp.text


def get_connection():
    if pymysql is None:
        raise RuntimeError("缺少依赖 pymysql，请先执行：pip install pymysql")
    mysql = CONFIG["mysql"]
    return pymysql.connect(
        host=mysql["host"],
        port=mysql["port"],
        user=mysql["user"],
        password=mysql["password"],
        database=mysql["db"],
        charset=mysql["charset"],
        autocommit=False,
    )


def count_empty(conn, cursor):
    cursor.execute("SELECT COUNT(*) FROM gov_info_record WHERE publish_unit IS NULL OR publish_unit = ''")
    row = cursor.fetchone()
    return int(row[0]) if row else 0


def load_missing(conn, cursor, limit=0):
    """取 publish_unit 为空的记录（id, category, source_url），可选 limit。"""
    sql = ("SELECT id, category, source_url FROM gov_info_record "
           "WHERE publish_unit IS NULL OR publish_unit = '' ORDER BY id")
    if limit and limit > 0:
        sql += " LIMIT %d" % int(limit)
    cursor.execute(sql)
    return cursor.fetchall()


# ============================================================
# A. 详情页真实单位提取
# ============================================================


def fill_from_detail(conn, cursor, limit=0, interval=None, dry_run=False):
    """逐条抓详情页提取发布单位；只填空位。返回 (抓取数, 填充数, 失败数)。"""
    if interval is None:
        interval = CONFIG["request_interval"]
    rows = load_missing(conn, cursor, limit)
    total = len(rows)
    if total == 0:
        print("[详情] 无待补记录，跳过（publish_unit 已全部填充）")
        return 0, 0, 0
    session = make_session()
    fetched = filled = failed = 0
    for i, (rid, category, url) in enumerate(rows, 1):
        try:
            html = fetch(session, url)
        except Exception as exc:
            failed += 1
            print("[详情] %d/%d 抓取失败 %s -> %s" % (i, total, url, exc), file=sys.stderr)
            time.sleep(interval)
            continue
        fetched += 1
        unit = extract_unit(html)
        if not unit:
            time.sleep(interval)
            continue
        if dry_run:
            print("[详情][DRY-RUN] id=%s 提取单位=%s" % (rid, unit))
            continue
        cursor.execute(
            "UPDATE gov_info_record SET publish_unit = %s WHERE id = %s "
            "AND (publish_unit IS NULL OR publish_unit = '')",
            (unit, rid),
        )
        filled += cursor.rowcount
        if i % 100 == 0:
            print("[详情] 进度 %d/%d，已填充 %d" % (i, total, filled))
        time.sleep(interval)
    conn.commit()
    print("[详情] 完成：抓取 %d | 提取填充 %d | 失败 %d（未提取的将由域名/类目兜底）"
          % (fetched, filled, failed))
    return fetched, filled, failed


# ============================================================
# B+C. 域名推断 + 类目代理（无需联网）
# ============================================================


def fill_from_inference(conn, cursor, dry_run=False):
    """对仍为空的记录做 B 域名推断 + C 类目代理；只填空位。返回 (域名数, 类目数)。"""
    rows = load_missing(conn, cursor)
    domain_filled = category_filled = 0
    for rid, category, url in rows:
        unit = infer_unit(url, category)
        if not unit:
            continue
        if dry_run:
            print("[推断][DRY-RUN] id=%s %s -> %s" % (rid, url, unit))
            continue
        cursor.execute(
            "UPDATE gov_info_record SET publish_unit = %s WHERE id = %s "
            "AND (publish_unit IS NULL OR publish_unit = '')",
            (unit, rid),
        )
        if cursor.rowcount:
            if host_of(url) in UNIT_DOMAIN_MAP:
                domain_filled += 1
            else:
                category_filled += 1
    conn.commit()
    print("[推断] 完成：域名推断 %d | 类目代理 %d" % (domain_filled, category_filled))
    return domain_filled, category_filled


# ============================================================
# 口径同步（幂等 UPDATE metric_definition）
# ============================================================


def sync_metrics(conn, cursor, dry_run=False):
    cursor.execute("SELECT id FROM table_schema WHERE table_name = 'gov_info_record' LIMIT 1")
    row = cursor.fetchone()
    if not row:
        print("[口径] 未找到 gov_info_record 的 table_schema，跳过 metric_definition 同步")
        return 0
    table_id = row[0]
    updated = 0
    for metric in METRICS_SYNC:
        cursor.execute(
            "UPDATE metric_definition SET description = %s, calculation_formula = %s "
            "WHERE table_id = %s AND name = %s AND status = 1",
            (metric["description"], metric["calculation_formula"], table_id, metric["name"]),
        )
        updated += cursor.rowcount
    conn.commit()
    print("[口径] metric_definition 同步 %d 条（幂等）" % updated)
    return updated


# ============================================================
# CLI
# ============================================================


def main():
    parser = argparse.ArgumentParser(description="政务数据发文单位三级补齐：A 详情页 / B 域名 / C 类目代理")
    parser.add_argument("--mode", choices=["detail", "local", "all"], default="all",
                        help="detail=联网抓详情页提取；local=域名+类目推断（无需联网）；all=先 detail 后 local")
    parser.add_argument("--limit", type=int, default=0, help="最多处理的记录数（0=全部；测试建议小值）")
    parser.add_argument("--interval", type=float, default=CONFIG["request_interval"],
                        help="请求间隔秒数，默认 %.1f" % CONFIG["request_interval"])
    parser.add_argument("--dry-run", action="store_true", help="只统计不落库（detail 模式仍需联网抓取）")
    args = parser.parse_args()

    if args.mode in ("detail", "all") and requests is None:
        print("缺少依赖 requests，detail 模式不可用；请先 pip install requests 或改用 --mode local",
              file=sys.stderr)
        sys.exit(2)
    conn = get_connection()
    cursor = conn.cursor()
    print("补齐前 publish_unit 为空：%d 条" % count_empty(conn, cursor))
    if args.mode in ("detail", "all"):
        fill_from_detail(conn, cursor, args.limit, args.interval, args.dry_run)
    if args.mode in ("local", "all"):
        fill_from_inference(conn, cursor, args.dry_run)
    print("补齐后 publish_unit 为空：%d 条（剩余多为跨站且无类目映射的记录）"
          % count_empty(conn, cursor))
    if not args.dry_run:
        sync_metrics(conn, cursor)
    cursor.close()
    conn.close()


if __name__ == "__main__":
    main()
