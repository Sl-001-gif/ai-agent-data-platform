# -*- coding: utf-8 -*-
"""
3A 政务数据爬虫：站点结构探测脚本（本机联网运行）
============================================================

用途：在配置/修改数据源前，先对目标列表页做一次结构探测，输出：

1. HTTP 状态 / 最终 URL / 编码 / 页面大小
2. 列表容器候选（li/tr 中含链接容器的数量，及 top N 容器的 class/id）
3. 前 N 条链接样本（标题 + 绝对化 href）
4. 分页控件线索（"下一页/下页/尾页"链接、表单 action、内联 script 中分页参数）
5. 原始 HTML 保存到本地文件，供离线分析

用法：
    python probe_site.py https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml
    python probe_site.py URL --out probe_output --limit 20 --timeout 15

依赖：requests beautifulsoup4（与 gov_scraper.py 相同）

合规声明：仅抓取政府门户网站依法主动公开的页面用于结构分析，符合数据使用规范。
"""

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)
# 翻页链接文本线索
_NEXT_TEXT_RE = re.compile(r"下一页|下页|后一页|尾页|末页|下一頁|>")
# 常见分页参数名（script/表单中查找）
_PAGE_PARAM_RE = re.compile(
    r"(page(?:No|Num|Index|Number|Size)?|currentPage|pageIndex|pageSize|pn)\b", re.I)
# JS 分页函数/变量（政府站常用 Sohu CMS 模板，如 createPageHTML/pageCount）
_JS_PAGING_RE = re.compile(
    r"(createPageHTML|createPage|turnPage|goPage|gotoPage|jumpPage|pageCount|totalPage|totalPages|totalRecord)\b",
    re.I)
# 后缀式分页链接：xlist_2.shtml / index_3.html
_PAGE_SUFFIX_RE = re.compile(r"^(.+?)[-_](\d{1,3})\.(s?html?)$", re.I)

_LIST_TAGS = ("li", "tr")


def fetch(session, url, timeout):
    """GET 并返回 (Response, 解码后 HTML 文本)。"""
    resp = session.get(url, timeout=timeout)
    resp.raise_for_status()
    declared = resp.encoding
    if declared is None or declared.lower() in ("iso-8859-1", "ascii"):
        resp.encoding = resp.apparent_encoding or declared
    return resp, resp.text


def describe(node):
    """输出节点 class/id 描述；都没有则返回 '(无 class/id)'。"""
    cls = node.get("class") or []
    cid = node.get("id")
    parts = [str(c) for c in cls] + ([str(cid)] if cid else [])
    return " ".join(parts) if parts else "(无 class/id)"


def probe(session, url, limit, timeout):
    """抓取并打印结构信息，返回 HTML 文本。"""
    resp, html = fetch(session, url, timeout)
    print("=" * 60)
    print("HTTP 状态: %s" % resp.status_code)
    print("最终 URL : %s" % resp.url)
    print("编码     : %s（响应头 %s）" % (resp.encoding, resp.headers.get("Content-Type", "无")))
    print("页面大小 : %d 字节" % len(resp.content))
    soup = BeautifulSoup(html, "html.parser")

    # 1. 列表容器候选统计
    print("-" * 60)
    print("列表容器候选（%s 中含链接容器，展示前 8 个）:" % "/".join(_LIST_TAGS))
    containers = []
    for tag in _LIST_TAGS:
        found = [c for c in soup.find_all(tag) if c.find("a", href=True)]
        print("  <%s> 共 %d 个（其中含链接 %d 个）" % (tag, len(soup.find_all(tag)), len(found)))
        containers.extend(found)
    for c in containers[:8]:
        print("    - class/id: %s | 链接数: %d" % (describe(c), len(c.find_all("a", href=True))))

    # 2. 链接样本（li/tr 容器内，标题≥4字，URL 去重）
    print("-" * 60)
    print("链接样本（前 %d 条）:" % limit)
    seen_urls = set()
    shown = 0
    for c in containers:
        if shown >= limit:
            break
        for a in c.find_all("a", href=True):
            href = (a.get("href") or "").strip()
            low = href.lower()
            if (not href or href.startswith("#") or low.startswith("javascript")
                    or low.startswith("mailto:") or low.startswith("tel:")):
                continue
            title = re.sub(r"\s+", " ", a.get_text() or "").strip()
            if len(title) < 4:
                continue
            abs_url = urljoin(resp.url, href)
            if abs_url in seen_urls:
                continue
            seen_urls.add(abs_url)
            print("  [%02d] %s" % (shown + 1, title))
            print("       -> %s" % abs_url)
            shown += 1
            if shown >= limit:
                break

    # 3. 分页控件线索
    print("-" * 60)
    print("分页控件线索:")
    next_links = [a for a in soup.find_all("a", href=True)
                  if _NEXT_TEXT_RE.search(a.get_text() or "")]
    if next_links:
        for a in next_links[:10]:
            print("  [下一页类链接] 文本=%r href=%s" % (a.get_text().strip(), a.get("href")))
    else:
        print("  未找到文本含 下一页/下页/尾页 的 <a> 链接")
    forms = soup.find_all("form")
    if forms:
        for f in forms[:5]:
            inputs = ["%s=%s" % (i.get("name"), i.get("value") or "")
                      for i in f.find_all("input") if i.get("name")]
            print("  [表单] action=%s inputs=%s" % (f.get("action") or "(无)", ", ".join(inputs[:8])))
    else:
        print("  页面无 <form>")
    page_params = set()
    for script in soup.find_all("script"):
        if script.get("src"):
            continue
        text = script.string or ""
        for m in _PAGE_PARAM_RE.finditer(text):
            page_params.add(m.group(0))
    if page_params:
        print("  [script 中分页参数] %s" % ", ".join(sorted(page_params)))
    else:
        print("  内联 script 中未发现分页参数名")

    js_paging = set()
    for script in soup.find_all("script"):
        if script.get("src"):
            continue
        text = script.string or ""
        for m in _JS_PAGING_RE.finditer(text):
            js_paging.add(m.group(0))
    if js_paging:
        print("  [script 中 JS 分页线索] %s" % ", ".join(sorted(js_paging)))
    else:
        print("  内联 script 中未发现 JS 分页函数")

    pag_links = set()
    for a in soup.find_all("a", href=True):
        href = (a.get("href") or "").strip()
        low = href.lower()
        if not href or low.startswith(("javascript", "#", "mailto:", "tel:")):
            continue
        m = _PAGE_SUFFIX_RE.match(href)
        if m and int(m.group(2)) > 1:
            pag_links.add(href)
        elif "?" in href:
            q = urlparse(href).query
            if any(k + "=" in q for k in ("page", "pageNo", "pageIndex", "currentPage", "pageNum")):
                pag_links.add(href)
    if pag_links:
        print("  [分页链接样本] %s" % " | ".join(sorted(pag_links)[:8]))
    else:
        print("  未发现后缀式(_2.shtml)/参数式(?page=)分页链接")
    print("=" * 60)
    return html




def _build_param_url(base_url, page_no, page_param="page"):
    """按 page_param 参数递增构造 URL（与 gov_scraper.build_page_url 同逻辑）。"""
    from urllib.parse import parse_qs, urlencode, urlunparse
    parsed = urlparse(base_url)
    qs = parse_qs(parsed.query, keep_blank_values=True)
    qs[page_param] = [str(page_no)]
    return urlunparse(parsed._replace(query=urlencode(qs, doseq=True)))


def _item_links(soup, base_url, max_links=50):
    """抽取列表容器内条目链接 URL 集合（标题>=4字），用于跨页对比。"""
    urls = set()
    for tag in _LIST_TAGS:
        for c in soup.find_all(tag):
            for a in c.find_all("a", href=True):
                href = (a.get("href") or "").strip()
                low = href.lower()
                if not href or low.startswith(("#", "javascript", "mailto:", "tel:")):
                    continue
                title = re.sub(r"\s+", " ", a.get_text() or "").strip()
                if len(title) < 4:
                    continue
                urls.add(urljoin(base_url, href))
                if len(urls) >= max_links:
                    return urls
    return urls


def probe_param_paging(session, base_url, timeout, pages=3):
    """抓取 ?page=2..N 与第1页条目链接对比，输出参数翻页是否生效。"""
    print("=" * 60)
    print("参数翻页探测（?page=2..%d，与第1页条目链接对比）:" % pages)
    try:
        resp1, html1 = fetch(session, base_url, timeout)
        page1 = _item_links(BeautifulSoup(html1, "html.parser"), resp1.url)
    except Exception as exc:
        print("  第1页请求失败：%s" % exc, file=sys.stderr)
        return
    print("  第1页条目链接 %d 个" % len(page1))
    for n in range(2, pages + 1):
        url = _build_param_url(base_url, n)
        try:
            resp, html = fetch(session, url, timeout)
        except Exception as exc:
            print("  [第%d页] 请求失败：%s" % (n, exc), file=sys.stderr)
            break
        urls = _item_links(BeautifulSoup(html, "html.parser"), resp.url)
        new = urls - page1
        print("  [第%d页] HTTP %d | 条目 %d 个 | 与第1页相比新增 %d 个 -> %s"
              % (n, resp.status_code, len(urls), len(new),
                 "参数翻页生效" if new else "参数翻页无效（内容与第1页相同）"))
        if not new:
            break


def main():
    parser = argparse.ArgumentParser(description="探测政府网站列表页结构（本机联网运行）")
    parser.add_argument("url", help="列表页 URL，如 https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml")
    parser.add_argument("--out", default="probe_output", help="HTML 保存目录，默认 probe_output")
    parser.add_argument("--limit", type=int, default=15, help="链接样本条数，默认 15")
    parser.add_argument("--timeout", type=int, default=15, help="请求超时秒数，默认 15")
    parser.add_argument("--probe-pages", type=int, default=0,
                        help=">0 时额外抓取 ?page=2..N 与第1页对比，判断参数翻页是否生效（默认 0=不探测）")
    args = parser.parse_args()

    session = requests.Session()
    session.headers.update({
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    try:
        html = probe(session, args.url, args.limit, args.timeout)
    except Exception as exc:
        print("探测失败：%s" % exc, file=sys.stderr)
        sys.exit(1)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    host = urlparse(args.url).netloc.replace(":", "_")
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = out_dir / ("%s_%s.html" % (host, ts))
    out_path.write_text(html, encoding="utf-8")
    print("HTML 已保存：%s" % out_path)

    if args.probe_pages >= 2:
        probe_param_paging(session, args.url, args.timeout, args.probe_pages)


if __name__ == "__main__":
    main()
