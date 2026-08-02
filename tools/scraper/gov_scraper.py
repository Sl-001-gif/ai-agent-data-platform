# -*- coding: utf-8 -*-
"""
3A 政务数据爬虫：邵阳市政务信息公开数据抓取与入库
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
import json
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
            # pagination.mode：
            #   auto       = 优先页面内"下一页"链接，缺失时按 page_param 递增（默认）
            #   next-link  = 只认页面内"下一页"链接，不用参数翻页（?page=N 已知失效时用）
            #   page-param = 直接用 page_param 参数翻页，不找"下一页"链接
            #   none       = 只抓首页，不翻页
            "pagination": {"mode": "auto", "page_param": "page", "page_start": 1},
            # 数据源类型：list=普通列表页；tree=目录树（拉树后逐类目抓列表）
            "type": "tree",
            # 目录树接口（JSONP）：入口页内联脚本提取；节点 url 为各类目列表页（null.shtml 按 xlist 规律探测）
            "tree": {
                "api": "/u/channel/treeNew/shaoyang",
                "params": {
                    "level": "1",
                    "pid": "",
                    "channelId": "979dc6eb921b418a9355bc6eb2eac8f4",
                    "catecode": "",
                    "open": "0",
                    "target": "new",
                    "published_file_name": "/zwgk",
                    "template_name": "zwgk_right2,zwgk_right_tt,zwgk_right,zwgk_right_jz,zwgk_r_1,zwgk_right_gwy,zwgk_right_gkbm1,zwgk_right_gkbm2,zwgk_right_gknb1,zwgk_right_gknb2,zwgk_right_xxgk2",
                },
                # 专题栏目（宣传专题，不属于信息公开目录类目）——树节点占位探测会复活其列表页，故显式跳过
                "skip_names": [
                    "打好经济增长主动仗", "打好科技创新攻坚仗", "打好优化发展环境持久仗",
                    "打好防范化解风险阻击仗", "打好安全生产翻身仗", "打好重点民生保障仗",
                ],
            },

        },

    },
    # 噪音链接过滤规则：配置项为"追加"到内置默认集合并去重；
    # whitelist 为空列表 = 不启用白名单；print_reasons=True 等价命令行 --verbose
    "noise_filter": {
        "min_title_len": 4,
        "title_blacklist": [],        # 标题子串黑名单（追加）
        "title_exact_blacklist": [],  # 标题精确黑名单（追加）
        "href_blacklist": [],         # href 子串黑名单（追加，匹配前统一小写）
        "href_whitelist": [],         # href 子串白名单（非空时启用：href 必须命中其一）
        "container_blacklist": [],    # 容器 class/id 子串黑名单（追加，含祖先节点）
        "container_whitelist": [],    # 容器 class/id 子串白名单（非空时启用）
        "print_reasons": False,       # True 时打印每条被过滤链接及原因
    },
}

# ============================================================
# 噪音链接过滤：内置默认规则（CONFIG["noise_filter"] 可追加，见 build_noise_filter）
# ============================================================
# 标题子串黑名单：命中即过滤（保守收录，避免误伤含"信息公开/政务公开"字样的真实标题）
_TITLE_NAV_BLACKLIST = (
    "下一页", "下页", "尾页", "末页",
    "更多»", "更多>>", "更多...",
    "网站地图", "站点地图", "联系我们", "设为首页", "加入收藏", "收藏本站", "返回首页",
    "无障碍浏览", "适老化", "长者模式", "移动版", "手机版", "繁体版", "简体版", "English",
    "网站帮助",
    "政府信息公开指南", "政府信息公开制度", "政府信息公开年报", "法定主动公开内容",
    "网站工作年度报表", "政府网站标识码", "备案号", "版权声明", "隐私声明",
)
# 标题精确黑名单：链接文本与之一致才过滤（"政策文件/通知公告"等类目导航链接）
_TITLE_NAV_EXACT_BLACKLIST = (
    "更多", "首页", "进入", "详情", "网站首页",
    "政务公开", "信息公开", "政策文件", "通知公告", "政策解读",
    "办事服务", "互动交流", "数据开放", "政府公报", "走进邵阳", "认识新宁",
    "机构设置", "领导信息", "依申请公开", "信息公开年报", "政务服务", "政府信息公开",
    "领导信箱", "在线访谈", "民意征集", "网上调查", "常见问题", "使用帮助",
    "政务要闻", "统计公报", "统计月报", "统计分析", "政务微博", "智能问答",
    "国家部委网站", "全国各省政府网站", "本省市州政府网", "县市区政府网", "市直单位政府网站",
    "打好经济增长主动仗", "打好科技创新攻坚仗", "打好优化发展环境持久仗",
    "打好防范化解风险阻击仗", "打好安全生产翻身仗", "打好重点民生保障仗",
    "English", "繁體版", "简体版", "登录", "注册", "微博", "微信", "APP", "客户端", "订阅", "邮箱",
    "政府数据", "政民互动",
)
# href 子串黑名单（匹配前统一小写）：拼音缩写导航路径等
_HREF_NAV_BLACKLIST = (
    "/wzsy", "/wzdt", "/sitemap", "/lxwm", "/gywm", "/about", "/contact",
    "/login", "/register", "/user", "/member", "/search", "/rss", "/feed",
    "/wjdc", "/zxfk", "/tsjb", "/ldxx", "/yqlj", "/friend", "/shouye",
    "/xlist", "zwgk_right", "/end_link",
    "list.shtml", "nzcjd.shtml", "zdjcyg.shtml", "dfxfghgz.shtml", "wjk_jump.shtml",
    "xszf.shtml", "nzfjg.shtml", "xxgkjbml.shtml", "tyhd_index.shtml", "xsjfb.shtml",
    "yshjzqt.shtml", "rlist_c.shtml",
)
# 容器 class/id 子串黑名单（含祖先节点）：导航/页脚/页头/翻页容器内的链接一律过滤
_CONTAINER_NAV_BLACKLIST = (
    "nav", "menu", "footer", "header", "pagination", "breadcrumb", "crumb",
    "toolbar", "banner", "sitemap", "friend", "yqlj",
)

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
        "description": "政务信息公开平台累计发布的政务信息条数，反映信息公开总体规模（口径：直接统计全表记录数，栏目壳页与导航噪音已清理不在其中）",
        "calculation_formula": "SELECT COUNT(*) AS cnt FROM gov_info_record",
    },
    {
        "name": "类目占比",
        "description": "各公开类目发文量占全部发文量的比例（分母=全表记录数），反映信息公开的结构分布",
        "calculation_formula": (
            "SELECT category, COUNT(*) AS cnt, "
            "COUNT(*) / (SELECT COUNT(*) FROM gov_info_record) AS ratio "
            "FROM gov_info_record WHERE category IS NOT NULL AND category <> '' GROUP BY category"
        ),
    },
    {
        "name": "平均每日发文量",
        "description": "按最早与最晚发布日期区间计算的日均发文量，反映发布时效（注意：数据跨度 2005~2026 较长，日均值会被长区间稀释，解读请结合分月趋势）",
        "calculation_formula": (
            "SELECT ROUND(COUNT(*) / NULLIF(DATEDIFF(MAX(publish_date), MIN(publish_date)), 0), 2) AS avg_daily "
            "FROM gov_info_record WHERE publish_date IS NOT NULL"
        ),
    },
    {
        "name": "单位发文量",
        "description": "各公开单位发布的政务信息条数，反映各单位信息公开活跃度（口径：详情页真实单位 -> 部门子站域名推断 -> 类目代理兜底；类目代理为近似推断，仅作演示参考）",
        "calculation_formula": (
            "SELECT COALESCE(NULLIF(publish_unit,''), category) AS unit, COUNT(*) AS cnt "
            "FROM gov_info_record GROUP BY unit ORDER BY cnt DESC LIMIT 10"
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
_UNIT_RE = re.compile(
    r"(?:信息发布单位|信息提供单位|来源单位|发布单位|发文机关|责任单位|发文单位|公开单位)"
    r"\s*[:：]?\s*([\u4e00-\u9fa5（）()]{2,40})"
)
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
def build_noise_filter(config=None):
    """合并内置默认规则与 CONFIG["noise_filter"] 的追加项，返回过滤规则 dict。"""
    cfg = (config or CONFIG).get("noise_filter", {}) or {}
    return {
        "min_title_len": int(cfg.get("min_title_len", 4)),
        "title_blacklist": _dedupe(_TITLE_NAV_BLACKLIST + tuple(cfg.get("title_blacklist") or ())),
        "title_exact_blacklist": _dedupe(_TITLE_NAV_EXACT_BLACKLIST + tuple(cfg.get("title_exact_blacklist") or ())),
        "href_blacklist": _dedupe(_HREF_NAV_BLACKLIST + tuple(cfg.get("href_blacklist") or ())),
        "href_whitelist": tuple(cfg.get("href_whitelist") or ()),
        "container_blacklist": _dedupe(_CONTAINER_NAV_BLACKLIST + tuple(cfg.get("container_blacklist") or ())),
        "container_whitelist": tuple(cfg.get("container_whitelist") or ()),
    }


def _dedupe(items):
    """保持顺序去重。"""
    seen = set()
    out = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return tuple(out)


def _match_any(text, patterns):
    """返回 text 中命中的第一个子串模式（忽略大小写）；未命中返回 None。"""
    lowered = text.lower()
    for p in patterns:
        if p.lower() in lowered:
            return p
    return None


def _match_token_any(text, patterns):
    """按词元（非字母数字切分）整词比对；命中返回第一个模式，未命中返回 None。
    用于容器 class/id 匹配，避免 "page" 命中 "page-content" 等泛词导致误杀。"""
    tokens = set(re.split(r"[^0-9a-z]+", text.lower()))
    for p in patterns:
        if p.lower() in tokens:
            return p
    return None


def _container_signature(container):
    """收集容器自身及祖先（至 body 止）的 class/id，小写合并，用于容器特征匹配。"""
    parts = []
    node = container
    while node is not None and getattr(node, "name", None) not in (None, "body"):
        for key in ("class", "id"):
            val = node.get(key)
            if isinstance(val, list):
                parts.extend(str(v) for v in val)
            elif val:
                parts.append(str(val))
        node = getattr(node, "parent", None)
    return " ".join(parts).lower()


def _is_usable_href(href):
    """href 基础过滤：非空、非锚点、非 javascript/mailto/tel。"""
    low = href.lower()
    if not href or href.startswith("#") or low.startswith("javascript"):
        return False
    if low.startswith("mailto:") or low.startswith("tel:"):
        return False
    return True


def filter_candidate_link(container, a, href, title, rules=None):
    """可组合噪音过滤：返回 (keep, reasons)；keep=False 时 reasons 说明每条过滤原因。"""
    rules = rules or build_noise_filter()
    reasons = []
    if len(title) < rules["min_title_len"]:
        reasons.append("标题过短(<%d字)" % rules["min_title_len"])
    p = _match_any(title, rules["title_blacklist"])
    if p:
        reasons.append("标题命中黑名单词[%s]" % p)
    if title in rules["title_exact_blacklist"]:
        reasons.append("标题精确命中黑名单[%s]" % title)
    p = _match_any(href, rules["href_blacklist"])
    if p:
        reasons.append("URL命中黑名单特征[%s]" % p)
    if rules["href_whitelist"] and not _match_any(href, rules["href_whitelist"]):
        reasons.append("URL未命中白名单特征")
    sig = _container_signature(container)
    p = _match_token_any(sig, rules["container_blacklist"])
    if p:
        reasons.append("容器class/id命中黑名单[%s]" % p)
    if rules["container_whitelist"] and not _match_token_any(sig, rules["container_whitelist"]):
        reasons.append("容器class/id未命中白名单")
    return (not reasons), reasons


def parse_list(html, base_url, rules=None, verbose=False):
    """解析列表页条目：标题/链接/日期/单位/类目/文号/摘要。返回记录 dict 列表。

    rules 为 build_noise_filter() 产物；verbose=True 时把每条被过滤的链接及原因打到 stderr。
    """
    rules = rules or build_noise_filter()
    soup = BeautifulSoup(html, "html.parser")
    records = []
    for container in soup.find_all(["li", "tr"]):
        links = []
        for a in container.find_all("a", href=True):
            href = (a.get("href") or "").strip()
            if not _is_usable_href(href):
                continue
            title = normalize_text(a.get_text())
            keep, reasons = filter_candidate_link(container, a, href, title, rules)
            if not keep:
                if verbose:
                    print("  [过滤] %s | %s | 原因: %s"
                          % (title or "(空标题)", href, " / ".join(reasons)), file=sys.stderr)
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


# ============================================================
# 分页自动发现（后缀式 createPageHTML/_2.shtml、参数名发现）
# ============================================================
# 锚点 href 提取（纯正则，避免依赖 bs4）
_ANCHOR_HREF_RE = re.compile(r"<a\s[^>]*href\s*=\s*[\"']([^\"']+)[\"']", re.I)
# 后缀式分页链接：xlist_2.shtml / index_3.html（[-_]+ 分隔 + 1-4 位数字）
_SUFFIX_PAGE_RE = re.compile(r"^(.*?)([-_]+)(\d{1,4})\.(s?html?)$", re.I)
# Sohu CMS 分页函数：createPageHTML(pageCount, cur, first, per, total, "前缀", "后缀")
_CREATE_PAGE_RE = re.compile(r"createPageHTML\s*\(\s*([^)]*?)\)", re.I)
# 常见分页参数名（参数翻页发现用）
_KNOWN_PAGE_PARAMS = ("page", "pageno", "pageindex", "pagenum", "currentpage", "pn")


def _suffix_base_from_url(current_url, prefix, ext):
    """按当前列表页 URL 与 createPageHTML 的 prefix/ext 参数推导后缀分页前缀。"""
    parsed = urlparse(current_url)
    path = parsed.path or ""
    idx = path.rfind("/")
    if idx == -1:
        return None
    return "%s://%s%s%s_" % (parsed.scheme, parsed.netloc, path[:idx + 1], prefix)

def detect_suffix_pagination(html, base_url):
    """检测后缀式分页（xlist_2.shtml / createPageHTML），返回
    (绝对前缀URL, 扩展名, pageCount)；未检测到返回 None。
    纯正则实现，不依赖 bs4。"""
    base = None
    ext = ".shtml"
    count = 0
    m = _CREATE_PAGE_RE.search(html or "")
    if m:
        args = [a.strip().strip("\"'") for a in m.group(1).split(",")]
        ints = [int(a) for a in args if a.isdigit()]
        strs = [a for a in args if a and not a.lstrip("-").isdigit()]
        if ints:
            count = ints[0]
        prefix = None
        for s in strs:
            low = s.lower()
            if low in (".shtml", ".html", ".htm", "shtml", "html", "htm"):
                ext = "." + low.lstrip(".")
            elif "/" in s and not low.endswith((".shtml", ".html", ".htm")):
                base = s
            elif low not in ("page_div",):
                prefix = s
        if base:
            base = urljoin(base_url, base)
        elif prefix:
            base = _suffix_base_from_url(base_url, prefix, ext)
    for href in _ANCHOR_HREF_RE.findall(html or ""):
        href = href.strip()
        if href.lower().startswith("javascript"):
            continue
        m2 = _SUFFIX_PAGE_RE.match(href)
        if not m2:
            continue
        no = int(m2.group(3))
        if no > 1:
            if no > count:
                count = no
            if base is None:
                base = urljoin(base_url, m2.group(1) + m2.group(2))
    if base is None:
        return None
    return base, ext, count


def build_suffix_url(base_url, page_no, ext=".shtml"):
    """构造后缀式分页 URL：<base><page_no><ext>。"""
    return "%s%d%s" % (base_url, page_no, ext)


def discover_page_param(html, base_url):
    """从页面内部分页链接发现真实参数名（如 ?pageNo=2），返回参数名或 None。"""
    for href in _ANCHOR_HREF_RE.findall(html or ""):
        href = href.strip()
        low = href.lower()
        if low.startswith(("javascript", "#", "mailto:", "tel:")):
            continue
        query = urlparse(urljoin(base_url, href)).query
        for key in parse_qs(query):
            if key.lower() in _KNOWN_PAGE_PARAMS:
                return key
    return None

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


def normalize_pagination(cfg):
    """把 sources[*].pagination 归一为结构化 dict；兼容旧写法字符串 "auto"。"""
    if cfg is None:
        cfg = {}
    if isinstance(cfg, str):
        cfg = {"mode": cfg}
    mode = str(cfg.get("mode", "auto")).lower()
    if mode not in ("auto", "next-link", "page-param", "none"):
        print("[警告] 未知分页模式 %r，回退为 auto" % mode, file=sys.stderr)
        mode = "auto"
    return {
        "mode": mode,
        "page_param": str(cfg.get("page_param", "page")),
        "page_start": int(cfg.get("page_start", 1)),
        "max_pages": int(cfg["max_pages"]) if cfg.get("max_pages") else None,
    }


def build_page_url(base_url, page_no, page_param="page"):
    """按 page_param 参数递增构造分页 URL（保留原查询参数）。"""
    parsed = urlparse(base_url)
    qs = parse_qs(parsed.query, keep_blank_values=True)
    qs[page_param] = [str(page_no)]
    return urlunparse(parsed._replace(query=urlencode(qs, doseq=True)))


def decide_next_url(html, current_url, base_url, pagination, param_page, page_no, max_pages):
    """按分页配置决定下一页 URL。返回 (next_url, used_page_param)；
    used_page_param=True 表示本次走参数翻页（调用方需同步 param_page += 1）。"""
    mode = pagination["mode"]
    if mode == "none":
        return None, False
    if mode in ("auto", "next-link"):
        next_url = find_next_page_url(html, current_url) if html else None
        if next_url:
            return next_url, False
        if mode == "next-link" or page_no >= max_pages:
            return None, False
        # auto 分页自动发现链：后缀式(createPageHTML/_2.shtml) → 参数名发现 → 默认 page 参数
        suffix = detect_suffix_pagination(html, current_url) if html else None
        if suffix:
            base, ext, page_count = suffix
            if page_no == 1:
                print("  [分页] 检测到后缀式分页：%sN%s（pageCount=%d）" % (base, ext, page_count), file=sys.stderr)
            if page_count and param_page + 1 > page_count:
                return None, False
            return build_suffix_url(base, param_page + 1, ext), True
        param_name = discover_page_param(html, current_url) if html else None
        if param_name and param_name != pagination["page_param"] and page_no == 1:
            print("  [分页] 检测到真实分页参数名：%s" % param_name, file=sys.stderr)
        return build_page_url(base_url, param_page + 1, param_name or pagination["page_param"]), True
    # page-param：直接用参数翻页，不找"下一页"链接
    if page_no >= max_pages:
        return None, False
    return build_page_url(base_url, param_page + 1, pagination["page_param"]), True


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
                "数据来源：邵阳市人民政府门户网站信息公开目录，"
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


def crawl(conn, cursor, session, source, max_pages, interval, verbose=False,
             list_url=None, category_override=None):
    """遍历列表页抓取并入库。返回 (总新增, 总更新, 总未变, 总失败)。

    分页行为由 sources[*].pagination 配置驱动（见 normalize_pagination / decide_next_url）。
    """
    seen = set()
    total_inserted = total_updated = total_unchanged = total_failed = 0
    pagination = normalize_pagination(source.get("pagination"))
    page_max = pagination.get("max_pages") or max_pages
    page_param = pagination["page_param"]
    page_start = pagination["page_start"]
    rules = build_noise_filter(CONFIG)
    # noise_filter.print_reasons=True 等价命令行 --verbose（README 与实现一致）
    if (CONFIG.get("noise_filter") or {}).get("print_reasons"):
        verbose = True

    page_no = 1            # 已抓页数（用于 page_max 上限判断）
    param_page = page_start  # 参数翻页模式下的当前页码
    base_url = list_url or source["list_url"]
    current_url = base_url
    if pagination["mode"] == "page-param":
        current_url = build_page_url(current_url, param_page, page_param)
    next_url = current_url

    while page_no <= page_max and next_url:
        try:
            html = fetch(session, next_url)
        except Exception as exc:
            print("[第%d页] 请求失败：%s -> %s" % (page_no, next_url, exc), file=sys.stderr)
            break

        items = parse_list(html, next_url, rules, verbose)
        if not items:
            print("[第%d页] 列表页无有效条目，视为已到末页，停止翻页。" % page_no)
            if page_no == 1:
                print("  [提示] 若页面实际存在条目，可能是解析器未匹配或站点为 JS 渲染，"
                      "请先运行 probe_site.py 核实列表结构后调整 noise_filter/解析规则。", file=sys.stderr)
            break

        if category_override:
            for item in items:
                if not item.get("category"):
                    item["category"] = category_override

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

        # 下一页：按数据源分页配置决定（auto=优先"下一页"链接，缺失时 page_param 递增）
        next_url, used_page_param = decide_next_url(
            html, next_url, base_url, pagination, param_page, page_no, page_max)
        if used_page_param:
            param_page += 1
        if not next_url and page_no < page_max and pagination["mode"] in ("auto", "next-link"):
            print("  [提示] 未找到\"下一页\"链接且未使用参数翻页（可能已到末页，或分页为 JS 渲染/特殊结构）。"
                  "若确认还有更多页，请运行 probe_site.py 核实后配置 pagination.mode/page_param。", file=sys.stderr)
        if next_url == current_url:
            break
        if used_page_param and new_items == 0:
            # 站点忽略 page 参数返回相同内容时提前结束，避免空跑
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



def fetch_tree(session, source):
    """拉取目录树 JSONP，返回节点 dict 列表（[{name,url,level,id,...}, ...]）。"""
    tree = source.get("tree") or {}
    api = tree.get("api")
    if not api:
        raise RuntimeError("数据源 %s 未配置 tree.api" % source.get("name"))
    params = dict(tree.get("params") or {})
    url = urljoin(source["list_url"], api)
    last_exc = None
    for attempt in range(3):
        try:
            resp = session.get(url, params=params, timeout=CONFIG["request_timeout"])
            resp.raise_for_status()
            text = resp.text.strip()
            break
        except Exception as exc:
            last_exc = exc
            if attempt < 2:
                print("[树] 目录树接口请求失败（第 %d 次）：%s，%ds 后重试"
                      % (attempt + 1, exc, 3 * (attempt + 1)), file=sys.stderr)
                time.sleep(3 * (attempt + 1))
    else:
        raise last_exc
    m = re.match(r"^[^(]*\((.*)\)\s*;?\s*$", text, re.S)  # 去 JSONP 包裹 null(...) / callback(...)
    payload = json.loads(m.group(1)) if m else json.loads(text)
    return payload.get("list") or []


def resolve_category_list_url(node_url, base_url, session, interval):
    """把树节点 URL 解析为可抓列表页：
    - 真实 URL 直接用（urljoin 绝对化）
    - /xxx/null.shtml 占位：依次探测 xlist/xxgkList/list/index 变体，返回首个 200
    - 全部失败/无 URL 返回 None
    """
    if not node_url or node_url.strip().lower().startswith("javascript"):
        return None
    url = urljoin(base_url, node_url)
    if url.endswith("/null.shtml"):
        stem = url[: -len("null.shtml")]
        for cand in ("xlist.shtml", "xxgkList.shtml", "xxgklist.shtml", "list.shtml", "index.shtml"):
            candidate = stem + cand
            try:
                resp = session.get(candidate, timeout=CONFIG["request_timeout"], stream=True)
                ok = resp.status_code == 200
                resp.close()
                if ok:
                    return candidate
            except Exception:
                pass
            time.sleep(interval)
        return None
    return url


def crawl_tree(conn, cursor, session, source, max_pages, interval, verbose=False, categories=None):
    """树模式：拉目录树 → 逐类目抓列表（跨站与占位解析失败类目跳过并打日志）。"""
    try:
        nodes = fetch_tree(session, source)
    except Exception as exc:
        print("[树] 目录树接口请求失败：%s（可稍后重跑，脚本幂等）" % exc, file=sys.stderr)
        return 0, 0, 0, 0
    print("[树] 共获取 %d 个目录节点" % len(nodes))
    if categories:
        wanted = set(categories)
        nodes = [n for n in nodes if n.get("name") in wanted]
        print("[树] 按 --categories 筛选后 %d 个：%s" % (len(nodes), "、".join(n["name"] for n in nodes)))
    skip_names = set((source.get("tree") or {}).get("skip_names") or [])
    if skip_names:
        nodes = [n for n in nodes if n.get("name") not in skip_names]
        print("[树] 按 skip_names 剔除专题类目后 %d 个" % len(nodes))
    if not nodes:
        print("[树] 无可用目录节点，终止。", file=sys.stderr)
        return 0, 0, 0, 0
    base_url = source["list_url"]
    site_root = urlparse(base_url).netloc
    domain = site_root.split(".", 1)[-1]
    seen_cat = set()
    totals = [0, 0, 0, 0]
    for node in nodes:
        name = (node.get("name") or "").strip()
        if not name or name in seen_cat:
            continue
        seen_cat.add(name)
        url = resolve_category_list_url(node.get("url") or "", base_url, session, interval)
        if not url:
            print("[树] 跳过类目 %s（占位 URL 无可用列表页）" % name)
            continue
        netloc = urlparse(url).netloc
        if netloc != site_root and not netloc.endswith("." + domain):
            print("[树] 跳过类目 %s（跨站 %s，如需抓取请调整规则）" % (name, netloc))
            continue
        print("=" * 60)
        print("[树] 抓取类目：%s -> %s" % (name, url))
        r = crawl(conn, cursor, session, source, max_pages, interval, verbose,
                  list_url=url, category_override=name)
        for i in range(4):
            totals[i] += r[i]
    print("=" * 60)
    print("树模式抓取完成：新增 %d | 更新 %d | 未变 %d | 失败 %d" % tuple(totals))
    return tuple(totals)


def parse_args():
    parser = argparse.ArgumentParser(description="3A 政务数据爬虫：邵阳市政务信息公开数据抓取与入库")
    parser.add_argument("--pages", type=int, default=CONFIG["max_pages"],
                        help="分页抓取上限，默认 %d" % CONFIG["max_pages"])
    parser.add_argument("--source", choices=sorted(CONFIG["sources"].keys()), default="shaoyang",
                        help="数据源，可选：" + "/".join(sorted(CONFIG["sources"].keys())) + "，默认 shaoyang")
    parser.add_argument("--categories", default="",
                        help="tree 模式：只抓指定类目（逗号分隔，如 政策文件,统计信息）；默认全部")
    parser.add_argument("--verbose", action="store_true",
                        help="打印每条被过滤的噪音链接及原因（等价 noise_filter.print_reasons=True）")
    return parser.parse_args()


def main():
    args = parse_args()
    source = CONFIG["sources"][args.source]
    if not source.get("confirmed", True):
        print("[警告] 数据源 %s 的 list_url 未经确认：%s" % (args.source, source["list_url"]), file=sys.stderr)
        print("       请先在本机运行 probe_site.py 核验真实目录页 URL 与分页结构，再开始抓取。", file=sys.stderr)
    pagination = normalize_pagination(source.get("pagination"))
    print("数据源：%s" % source["name"])
    print("起始列表页：%s" % source["list_url"])
    print("分页模式：%s | 分页上限：%d 页 | 请求间隔：%ss"
          % (pagination["mode"], args.pages, CONFIG["request_interval"]))
    if pagination["mode"] in ("auto", "page-param"):
        print("分页参数：%s（起始 %d）" % (pagination["page_param"], pagination["page_start"]))
    session = make_session()
    conn = get_connection()
    try:
        with conn.cursor() as cursor:
            init_table(cursor)
            ensure_metadata(conn, cursor)
            if source.get("type") == "tree":
                categories = [c.strip() for c in args.categories.split(",") if c.strip()] if args.categories else None
                crawl_tree(conn, cursor, session, source, args.pages, CONFIG["request_interval"],
                           verbose=args.verbose, categories=categories)
            else:
                crawl(conn, cursor, session, source, args.pages, CONFIG["request_interval"], verbose=args.verbose)
    finally:
        conn.close()
    print("完成。")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已手动中断。", file=sys.stderr)
        sys.exit(130)
