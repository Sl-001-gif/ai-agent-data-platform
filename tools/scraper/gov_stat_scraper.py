# -*- coding: utf-8 -*-
"""邵阳市统计局 3 栏目快速爬虫：统计月报 / 统计公报 / 统计分析。

抓取列表页（createPageHTML 后缀式分页 xlist_N.shtml，按 pageCount 封顶或 --pages 限制），
解析 infoList 条目（标题/链接/发布日期），幂等入库 gov_info_record（source_url 唯一键），
category = 栏目名，publish_unit = 邵阳市统计局。

用法：
    python gov_stat_scraper.py --pages 20        # 每栏目最多 20 页（快速）
    python gov_stat_scraper.py --full            # 全量（按页面内 pageCount 封顶）
"""
import argparse
import re
import ssl
import time
import urllib.request
from urllib.parse import urljoin

try:
    import pymysql
except ImportError:
    pymysql = None

SITE = "https://www.shaoyang.gov.cn"
COLUMNS = {
    "tjyb": ("统计月报", SITE + "/shaoyang/tjyb/xlist.shtml"),
    "stjgb": ("统计公报", SITE + "/shaoyang/stjgb/xlist.shtml"),
    "stjfx": ("统计分析", SITE + "/shaoyang/stjfx/xlist.shtml"),
}
UNIT = "邵阳市统计局"
INTERVAL = 0.5
TIMEOUT = 20
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0"}

ctx = ssl.create_default_context()


def fetch(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=TIMEOUT, context=ctx) as r:
        return r.read().decode("utf-8", "ignore")


def parse_page_count(text):
    # createPageHTML('page_div', 5, 1, 'xlist', 'shtml', 85) -> 2nd arg is real page count
    m = re.search(r"createPageHTML\(([^)]*)\)", text)
    if m:
        args = [a.strip() for a in m.group(1).split(",")]
        if len(args) > 1 and args[1].isdigit():
            return int(args[1])
    return 1


def extract_date(url):
    # t20210227_ -> exact date (stats.gov.cn / tjj.hunan.gov.cn)
    m = re.search(r"/t(20\d{2})(\d{2})(\d{2})_", url)
    if m:
        return "%s-%s-%s" % m.groups()
    # /shaoyang/tjyb/202511/ or /202003/ -> month-level, use 1st day
    m = re.search(r"/(20\d{2})(\d{2})/", url)
    if m:
        return "%s-%s-01" % (m.group(1), m.group(2))
    return None


def parse_items(text, category):
    items = []
    # infoList container
    mlist = re.search(r'<ul[^>]*class="[^"]*infoList[^"]*"[^>]*>(.*?)</ul>', text, re.S)
    body = mlist.group(1) if mlist else text
    for m in re.finditer(r'<a[^>]+href="([^"]+\.(?:shtml|html))"[^>]*>(.*?)</a>', body, re.S):
        href, t = m.group(1), m.group(2)
        title = re.sub(r"<[^>]+>|\s+", " ", t).strip()
        if not title or len(title) < 4:
            continue
        url = urljoin(SITE, href)
        is_internal = "/shaoyang/" in url or "www.shaoyang.gov.cn" in url
        is_external = "stats.gov.cn" in url or "tjj.hunan.gov.cn" in url
        if not (is_internal or is_external):
            continue
        date = extract_date(url)
        if not is_internal and date is None:
            continue
        items.append({"title": title, "url": url, "category": category, "date": date})
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=20, help="每栏目最多列表页数")
    ap.add_argument("--full", action="store_true", help="全量（忽略 --pages 按 pageCount 封顶）")
    args = ap.parse_args()

    if pymysql is None:
        print("缺少依赖 pymysql，请先 pip install pymysql")
        return
    conn = pymysql.connect(host="localhost", port=3306, user="root",
                           password="Admin@123456", db="ai_agent_data",
                           charset="utf8mb4", autocommit=True)
    cur = conn.cursor()
    cur.execute("""CREATE TABLE IF NOT EXISTS gov_info_record (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        title VARCHAR(500) NOT NULL,
        doc_no VARCHAR(100) DEFAULT NULL,
        publish_unit VARCHAR(200) DEFAULT NULL,
        category VARCHAR(100) DEFAULT NULL,
        publish_date DATE DEFAULT NULL,
        source_url VARCHAR(500) NOT NULL UNIQUE,
        summary TEXT DEFAULT NULL,
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
    sql = """INSERT INTO gov_info_record (title, publish_unit, category, publish_date, source_url)
             VALUES (%s, %s, %s, %s, %s)
             ON DUPLICATE KEY UPDATE title = VALUES(title), publish_unit = VALUES(publish_unit),
             category = VALUES(category), publish_date = VALUES(publish_date)"""
    total = 0
    for key, (name, list_url) in COLUMNS.items():
        first = fetch(list_url)
        page_count = parse_page_count(first)
        limit = page_count if args.full else min(page_count, args.pages)
        print("[%s] 栏目=%s 总页数=%s 本次抓 %s 页" % (key, name, page_count, limit))
        for p in range(1, limit + 1):
            url = list_url if p == 1 else list_url.replace("xlist.shtml", "xlist_%d.shtml" % p)
            try:
                text = fetch(url)
            except Exception as e:
                print("  第 %s 页失败: %s" % (p, e))
                continue
            items = parse_items(text, name)
            for it in items:
                try:
                    cur.execute(sql, (it["title"], UNIT, it["category"], it["date"], it["url"]))
                    total += 1
                except Exception as e:
                    print("  入库失败 %s: %s" % (it["url"], e))
            print("  第 %s 页: %s 条（累计 %s）" % (p, len(items), total))
            time.sleep(INTERVAL)
    cur.close()
    conn.close()
    print("完成，共写入/更新 %s 条" % total)


if __name__ == "__main__":
    main()