# -*- coding: utf-8 -*-
"""
3A 政务数据爬虫：邵阳/新宁县政务信息公开数据抓取与入库
============================================================

功能：
1. 遍历政府信息公开目录列表页（优先找页面内"下一页"链接，找不到则按 ?page=N 递增，最多 max_pages 页）
2. 用 BeautifulSoup 解析列表条目：标题、原文链接、发布日期、公开单位/公开类目/文号、摘要（200 字）
3. 内存 seen 集合按 URL 去重；入库按 source_url 唯一键幂等（INSERT ... ON DUPLICATE KEY UPDATE）
4. 写入业务表 gov_info_record，并幂等写入元数据：dataset / table_schema / table_field / metric_definition

用法：
    python gov_scraper.py --pages 5 --source shaoyang

依赖：
    pip install requests beautifulsoup4 pymysql

合规声明：数据来源均为政府门户网站依法主动公开的政务信息，符合数据使用规范。
"""

import argparse
import os
import re
import sys
import time
from urllib.parse import parse_qs, urlencode, urljoin, urlparse, urlunparse

import requests
from bs4 import BeautifulSoup

try:
    import pymysql
except ImportError:  # 未安装时给出友好提示；py_compile 等语法检查不受影响
    pymysql = None

# ============================================================
# CONFIG：数据库连接与数据源配置（均可用环境变量覆盖）
# ============================================================
CONFIG = {
    # MySQL 连接（环境变量：MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB）
    "mysql": {
        "host": os.environ.get("MYSQL_HOST", "localhost"),
        "port": int(os.environ.get("MYSQL_PORT", "3306")),
        "user": os.environ.get("MYSQL_USER", "root"),
        "password": os.environ.get("MYSQL_PASSWORD", "Admin@123456"),
        "db": os.environ.get("MYSQL_DB", "ai_agent_data"),
        "charset": "utf8mb4",
    },
    # 分页上限（命令行 --pages 可覆盖）
    "max_pages": 5,
    # 请求间隔（秒），礼貌抓取，避免给目标网站造成压力（环境变量：SCRAPER_INTERVAL）
    "request_interval": float(os.environ.get("SCRAPER_INTERVAL", "1.0")),
    "request_timeout": 15,
    "user_agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    ),
    # 数据源列表：--source 可选值
    "sources": {
        "shaoyang": {
            "name": "邵阳市人民政府门户网站-信息公开目录",
            "list_url": "https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml",
            "pagination": "auto",  # auto = 优先页面内"下一页"链接，找不到则 ?page=N 递增
        },
        "xinning": {
            "name": "新宁县人民政府门户网站-信息公开目录",
            # TODO(用户提供)：新宁县官网信息公开目录实际地址，获取后替换下方占位符，详见 README.md
            "list_url": "https://www.xinning.gov.cn/xxgk/xxzwgkList.shtml",
            "pagination": "auto",
        },
    },
}

# 非条目链接标题黑名单（"下一页""更多"等）
_TITLE_BLACKLIST = {"更多", "下一页", "下页", "尾页", "首页", "进入", "详情"}

# gov_info_record 字段元数据：(field_name, field_type, field_comment, business_meaning)
_FIELDS_META = [
    ("title", "VARCHAR(500)", "标题", "政务信息公开条目的标题，用于展示与检索"),
    ("doc_no", "VARCHAR(100)", "文号", "发文机关公文编号，如 邵政发〔2023〕12号；无文号则留空"),
    ("publish_unit", "VARCHAR(200)", "公开单位", "发布该条政务信息的单位名称"),
    ("category", "VARCHAR(100)", "公开类目", "信息公开目录所属类目，如 政策文件/通知公告/统计信息"),
    ("publish_date", "DATE", "发布日期", "信息正式发布日期，统一为 YYYY-MM-DD"),
    ("source_url", "VARCHAR(500)", "原文链接", "政府门户网站原文地址，作为记录唯一键用于幂等去重"),
    ("summary", "TEXT", "摘要", "列表条目文本截断 200 字后的内容摘要"),
]

# 指标口径定义（metric_definition）
_METRICS_META = [
    {
        "name": "发文量",
        "description": "政务信息公开平台累计发布的政务信息条数，反映信息公开总体规模",
        "calculation_formula": "SELECT COUNT(*) AS cnt FROM gov_info_record",
    },
    {
        "name": "类目占比",
        "description": "各公开类目发文量占全部发文量的比例，反映信息公开的结构分布",
        "calculation_formula": (
            "SELECT category, COUNT(*) AS cnt, "
            "COUNT(*) / (SELECT COUNT(*) FROM gov_info_record) AS ratio "
            "FROM gov_info_record WHERE category IS NOT NULL AND category <> '' GROUP BY category"
        ),
    },
    {
        "name": "平均每日发文量",
        "description": "按最早与最晚发布日期区间计算的日均发文量，反映发布时效",
        "calculation_formula": (
            "SELECT COUNT(*) / DATEDIFF(MAX(publish_date), MIN(publish_date)) AS avg_daily "
            "FROM gov_info_record WHERE publish_date IS NOT NULL"
        ),
    },
    {
        "name": "单位发文量",
        "description": "各公开单位发布的政务信息条数，反映各单位信息公开活跃度",
        "calculation_formula": (
            "SELECT publish_unit, COUNT(*) AS cnt FROM gov_info_record "
            "WHERE publish_unit IS NOT NULL AND publish_unit <> '' GROUP BY publish_unit"
        ),
    },
]

# ============================================================
# 文本提取工具
# ============================================================
_DATE_RE = re.compile(r"(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})日?")
_DOC_NO_RE = re.compile(
    r"[\u4e00-\u9fa5A-Za-z]{0,20}[〔\[(（]\s*\d{4}\s*[〕\]）)]\s*[0-9零一二三四五六七八九十百]{1,8}\s*号"
)
_DOC_NO_SIMPLE_RE = re.compile(r"[〔\[(（]\s*\d{4}\s*[〕\]）)]\s*\d+号")
_UNIT_RE = re.compile(r"(?:发布单位|发文机关|责任单位|发文单位|公开单位)\s*[:：]?\s*([\u4e00-\u9fa5（）()]{2,40})")
_CATEGORY_RE = re.compile(r"(?:公开类别|信息类别|类目名称|栏目名称|所属类目)\s*[:：]?\s*([\u4e00-\u9fa5（）()]{2,30})")


def normalize_text(text):
    """合并空白字符并去除首尾空格。"""
    return re.sub(r"\s+", " ", (text or "")).strip()


def extract_date(text):
    """提取 YYYY-MM-DD / YYYY年MM月DD日 / YYYY.MM.DD / YYYY/MM/DD 日期，找不到返回空串。"""
    m = _DATE_RE.search(text or "")
    if m:
        year, month, day = m.groups()
        return "%04d-%02d-%02d" % (int(year), int(month), int(day))
    return ""


def extract_doc_no(text):
    """提取公文文号，如 邵政办发〔2023〕1号；找不到返回空串。"""
    m = _DOC_NO_RE.search(text or "")
    if m:
        return m.group(0).strip()
    m = _DOC_NO_SIMPLE_RE.search(text or "")
    return m.group(0).strip() if m else ""


def extract_unit(text):
    """提取公开/发文单位；找不到返回空串。"""
    m = _UNIT_RE.search(text or "")
    return m.group(1).strip() if m else ""


def extract_category(text):
    """提取公开类目；找不到返回空串。"""
    m = _CATEGORY_RE.search(text or "")
    return m.group(1).strip() if m else ""


# ============================================================
# 网络层
# ============================================================
def make_session():
    session = requests.Session()
    session.headers.update({
        "User-Agent": CONFIG["user_agent"],
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    return session


def fetch(session, url):
    """GET 页面并返回解码后的 HTML 文本。"""
    resp = session.get(url, timeout=CONFIG["request_timeout"])
    resp.raise_for_status()
    # 政府网站常见 GBK/GB2312 编码且响应头常不带 charset，此时按实际编码探测
    declared = resp.encoding
    if declared is None or declared.lower() in ("iso-8859-1", "ascii"):
        resp.encoding = resp.apparent_encoding or declared
    return resp.text


# ============================================================
# 列表页解析与分页
# ============================================================
def parse_list(html, base_url):
    """解析列表页条目：标题/链接/日期/单位/类目/文号/摘要。返回记录 dict 列表。"""
    soup = BeautifulSoup(html, "html.parser")
    records = []
    for container in soup.find_all(["li", "tr"]):
        links = []
        for a in container.find_all("a", href=True):
            href = (a.get("href") or "").strip()
            if not href or href.startswith("#") or href.lower().startswith("javascript"):
                continue
            title = normalize_text(a.get_text())
            if len(title) < 4 or title in _TITLE_BLACKLIST:
                continue
            links.append((a, href, title))
        if not links:
            continue
        # 取容器内文本最长的链接作为标题链接（兼容"类目链接+标题链接"并存的结构）
        link, href, title = max(links, key=lambda x: len(x[2]))
        text = normalize_text(container.get_text(" "))
        records.append({
            "title": title,
            "doc_no": extract_doc_no(text),
            "publish_unit": extract_unit(text),
            "category": extract_category(text),
            "publish_date": extract_date(text),
            "source_url": urljoin(base_url, href),
            "summary": text[:200],
        })
    return records


def find_next_page_url(html, base_url):
    """优先找页面里的"下一页"链接；找不到返回 None。"""
    soup = BeautifulSoup(html, "html.parser")
    for a in soup.find_all("a", href=True):
        text = normalize_text(a.get_text())
        href = (a.get("href") or "").strip()
        if text in ("下页", "后一页", ">") or "下一页" in text:
            if href and not href.lower().startswith("javascript"):
                return urljoin(base_url, href)
    return None


def build_page_url(base_url, page_no):
    """无"下一页"链接时按 ?page=N 递增构造分页 URL（保留原查询参数）。"""
    parsed = urlparse(base_url)
    qs = parse_qs(parsed.query, keep_blank_values=True)
    qs["page"] = [str(page_no)]
    return urlunparse(parsed._replace(query=urlencode(qs, doseq=True)))


# ============================================================
# 数据库层
# ============================================================
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


def init_table(cursor):
    """建业务表 gov_info_record（幂等）。"""
    cursor.execute(
        """
        CREATE TABLE IF NOT EXISTS gov_info_record (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            title VARCHAR(500),
            doc_no VARCHAR(100),
            publish_unit VARCHAR(200),
            category VARCHAR(100),
            publish_date DATE,
            source_url VARCHAR(500) NOT NULL UNIQUE,
            summary TEXT,
            create_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='政府信息公开记录'
        """
    )


def ensure_metadata(conn, cursor):
    """幂等写入 dataset / table_schema / table_field / metric_definition。"""
    mysql = CONFIG["mysql"]

    # 1. dataset（同名存在则跳过）
    cursor.execute("SELECT id FROM dataset WHERE name = %s", ("邵阳政务信息公开数据",))
    row = cursor.fetchone()
    if row:
        dataset_id = row[0]
        print("[元数据] dataset 已存在（id=%s），跳过" % dataset_id)
    else:
        cursor.execute(
            """
            INSERT INTO dataset (name, description, db_type, db_host, db_port, db_name, db_username, db_password, status)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                "邵阳政务信息公开数据",
                "数据来源：邵阳市人民政府门户网站及新宁县人民政府门户网站信息公开目录，"
                "抓取整理两级政府依法主动公开的政务信息；所有资料均为官方依法公开内容，符合数据使用规范。"
                "由 tools/scraper/gov_scraper.py 抓取入库，用于政务公开统计分析。",
                "MYSQL",
                mysql["host"],
                mysql["port"],
                mysql["db"],
                mysql["user"],
                mysql["password"],
                1,
            ),
        )
        dataset_id = cursor.lastrowid
        print("[元数据] 新建 dataset（id=%s）" % dataset_id)

    # 2. table_schema（同 dataset 同 table_name 存在则跳过）
    cursor.execute(
        "SELECT id FROM table_schema WHERE dataset_id = %s AND table_name = %s",
        (dataset_id, "gov_info_record"),
    )
    row = cursor.fetchone()
    if row:
        table_id = row[0]
        print("[元数据] table_schema 已存在（id=%s），跳过" % table_id)
    else:
        cursor.execute(
            "INSERT INTO table_schema (dataset_id, table_name, table_comment, status) VALUES (%s, %s, %s, %s)",
            (dataset_id, "gov_info_record", "政府信息公开记录", 1),
        )
        table_id = cursor.lastrowid
        print("[元数据] 新建 table_schema（id=%s）" % table_id)

    # 3. table_field（同 table 同 field_name 存在则跳过）
    inserted_fields = 0
    for field_name, field_type, field_comment, business_meaning in _FIELDS_META:
        cursor.execute(
            "SELECT id FROM table_field WHERE table_id = %s AND field_name = %s",
            (table_id, field_name),
        )
        if cursor.fetchone():
            continue
        cursor.execute(
            """
            INSERT INTO table_field (table_id, field_name, field_type, field_comment, business_meaning, is_metric)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (table_id, field_name, field_type, field_comment, business_meaning, 0),
        )
        inserted_fields += 1
    print("[元数据] table_field 共 %d 个，本次新增 %d 个" % (len(_FIELDS_META), inserted_fields))

    # 4. metric_definition（同 table 同名存在则跳过）
    inserted_metrics = 0
    for metric in _METRICS_META:
        cursor.execute(
            "SELECT id FROM metric_definition WHERE table_id = %s AND name = %s",
            (table_id, metric["name"]),
        )
        if cursor.fetchone():
            continue
        cursor.execute(
            """
            INSERT INTO metric_definition (name, description, calculation_formula, table_id, status)
            VALUES (%s, %s, %s, %s, %s)
            """,
            (metric["name"], metric["description"], metric["calculation_formula"], table_id, 1),
        )
        inserted_metrics += 1
    print("[元数据] metric_definition 共 %d 个，本次新增 %d 个" % (len(_METRICS_META), inserted_metrics))
    conn.commit()


def insert_record(conn, cursor, record):
    """按 source_url 唯一键幂等写入；返回 (inserted, updated, unchanged)。"""
    cursor.execute(
        """
        INSERT INTO gov_info_record (title, doc_no, publish_unit, category, publish_date, source_url, summary)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            title = VALUES(title),
            doc_no = VALUES(doc_no),
            publish_unit = VALUES(publish_unit),
            category = VALUES(category),
            publish_date = VALUES(publish_date),
            summary = VALUES(summary)
        """,
        (
            record["title"],
            record["doc_no"] or None,
            record["publish_unit"] or None,
            record["category"] or None,
            record["publish_date"] or None,
            record["source_url"],
            record["summary"] or None,
        ),
    )
    # pymysql rowcount：1=新增，2=内容有变化被更新，0=内容未变化
    rowcount = cursor.rowcount
    if rowcount == 1:
        return 1, 0, 0
    if rowcount == 2:
        return 0, 1, 0
    return 0, 0, 1


# ============================================================
# 抓取主流程
# ============================================================
def supplement_from_detail(session, record, interval):
    """列表页缺发布日期时访问详情页补充一次（顺带补文号/单位/类目）。"""
    if record.get("publish_date"):
        return
    try:
        html = fetch(session, record["source_url"])
    except Exception as exc:
        print("  [详情补充失败] %s -> %s" % (record["source_url"], exc), file=sys.stderr)
        return
    if not record.get("publish_date"):
        record["publish_date"] = extract_date(html)
    if not record.get("doc_no"):
        record["doc_no"] = extract_doc_no(html)
    if not record.get("publish_unit"):
        record["publish_unit"] = extract_unit(html)
    if not record.get("category"):
        record["category"] = extract_category(html)
    time.sleep(interval)


def crawl(conn, cursor, session, source, max_pages, interval):
    """遍历列表页抓取并入库。返回 (总新增, 总更新, 总未变, 总失败)。"""
    seen = set()
    total_inserted = total_updated = total_unchanged = total_failed = 0
    page_no = 1
    current_url = source["list_url"]
    next_url = current_url

    while page_no <= max_pages and next_url:
        try:
            html = fetch(session, next_url)
        except Exception as exc:
            print("[第%d页] 请求失败：%s -> %s" % (page_no, next_url, exc), file=sys.stderr)
            break

        items = parse_list(html, next_url)
        if not items:
            print("[第%d页] 列表页无有效条目，视为已到末页，停止翻页。" % page_no)
            break

        new_items, dup_in_run = [], 0
        for item in items:
            if item["source_url"] in seen:
                dup_in_run += 1
                continue
            seen.add(item["source_url"])
            new_items.append(item)

        print("[第%d页] %s" % (page_no, source["name"]))
        print("  解析 %d 条 | 去重后新增 %d 条 | 页内/已见重复 %d 条 | URL: %s"
              % (len(items), len(new_items), dup_in_run, next_url))

        for item in new_items:
            supplement_from_detail(session, item, interval)
            try:
                inserted, updated, unchanged = insert_record(conn, cursor, item)
            except Exception as exc:
                total_failed += 1
                print("  [入库失败] %s -> %s" % (item["source_url"], exc), file=sys.stderr)
                conn.rollback()
                continue
            total_inserted += inserted
            total_updated += updated
            total_unchanged += unchanged
            time.sleep(interval)
        conn.commit()

        # 下一页：优先页面内"下一页"链接，否则 ?page=N 递增
        next_url = find_next_page_url(html, next_url)
        used_fallback = False
        if not next_url and page_no < max_pages:
            next_url = build_page_url(source["list_url"], page_no + 1)
            used_fallback = True
        if next_url == current_url:
            break
        if used_fallback and new_items == 0:
            # 站点忽略 ?page 参数返回相同内容时提前结束，避免空跑
            print("  分页参数未生效（下一页无新内容），停止翻页。")
            break
        current_url = next_url
        page_no += 1
        time.sleep(interval)

    print("=" * 60)
    print("抓取完成：解析唯一链接 %d 条" % len(seen))
    print("最终入库总数：新增 %d 条 | 更新 %d 条 | 内容未变化 %d 条 | 失败跳过 %d 条"
          % (total_inserted, total_updated, total_unchanged, total_failed))
    return total_inserted, total_updated, total_unchanged, total_failed


def parse_args():
    parser = argparse.ArgumentParser(description="3A 政务数据爬虫：邵阳/新宁县政务信息公开数据抓取与入库")
    parser.add_argument("--pages", type=int, default=CONFIG["max_pages"],
                        help="分页抓取上限，默认 %d" % CONFIG["max_pages"])
    parser.add_argument("--source", choices=sorted(CONFIG["sources"].keys()), default="shaoyang",
                        help="数据源，可选：" + "/".join(sorted(CONFIG["sources"].keys())) + "，默认 shaoyang")
    return parser.parse_args()


def main():
    args = parse_args()
    source = CONFIG["sources"][args.source]
    print("数据源：%s" % source["name"])
    print("起始列表页：%s" % source["list_url"])
    print("分页上限：%d 页 | 请求间隔：%ss" % (args.pages, CONFIG["request_interval"]))
    session = make_session()
    conn = get_connection()
    try:
        with conn.cursor() as cursor:
            init_table(cursor)
            ensure_metadata(conn, cursor)
            crawl(conn, cursor, session, source, args.pages, CONFIG["request_interval"])
    finally:
        conn.close()
    print("完成。")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已手动中断。", file=sys.stderr)
        sys.exit(130)