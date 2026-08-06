# -*- coding: utf-8 -*-
"""
L1 单测：统计栏目爬虫解析（分页数/日期提取/条目过滤），纯函数，无需联网与数据库。
运行：python test_gov_stat_scraper.py
"""
import gov_stat_scraper as gs

_TOTAL = [0]
_FAILED = []


def check(name, cond, detail=""):
    _TOTAL[0] += 1
    if cond:
        print("[PASS] %s" % name)
    else:
        print("[FAIL] %s %s" % (name, detail))
        _FAILED.append(name)


def test_parse_page_count():
    check("页数=5（tjyb）",
          gs.parse_page_count("x<script>createPageHTML('page_div',5, 1,'xlist','shtml',85)</script>") == 5)
    check("页数=10（stjgb）",
          gs.parse_page_count("createPageHTML('page_div',10,1,'xlist','shtml',189)") == 10)
    check("页数=11（stjfx）",
          gs.parse_page_count("createPageHTML('page_div',11, 1,'xlist','shtml',204)") == 11)
    check("无分页脚本回退 1", gs.parse_page_count("<html>no pagination</html>") == 1)


def test_extract_date():
    check("国家统计局 t 精确日期",
          gs.extract_date("http://www.stats.gov.cn/tjsj/zxfb/202102/t20210227_1814154.html") == "2021-02-27")
    check("湖南统计局 t 精确日期",
          gs.extract_date("http://tjj.hunan.gov.cn/hntj/tjfx/tjgb/jjfzgb/202003/t20200317_11814367.html") == "2020-03-17")
    check("站内 /年月/ 取当月1日",
          gs.extract_date("https://www.shaoyang.gov.cn/shaoyang/tjyb/202511/abc.shtml") == "2025-11-01")
    check("无日期模式返回 None", gs.extract_date("https://www.shaoyang.gov.cn/shaoyang/xxgk/list.shtml") is None)


def test_parse_items():
    html = """<ul class="infoList">
      <li><a href="/shaoyang/tjyb/202511/aaaa.shtml" title="2025年10月月报卡数据">2025年10月月报卡数据</a></li>
      <li><a href="http://www.stats.gov.cn/tjsj/zxfb/202102/t20210227_1814154.html">中华人民共和国2020年国民经济和社会发展统计公报</a></li>
      <li><a href="https://www.baidu.com/">外部无关链接</a></li>
      <li><a href="/shaoyang/tjyb/202511/short.shtml">短</a></li>
    </ul>
    <div class="nav"><a href="/shaoyang/xxgk/list.shtml">政务公开目录</a></div>"""
    items = gs.parse_items(html, "统计月报")
    check("过滤后仅 2 条", len(items) == 2, "实际=%d %r" % (len(items), items))
    if len(items) == 2:
        check("站内条目标题/日期/类目",
              items[0]["title"] == "2025年10月月报卡数据"
              and items[0]["date"] == "2025-11-01"
              and items[0]["category"] == "统计月报",
              "实际=%r" % items[0])
        check("跨站条目保留且日期精确",
              items[1]["date"] == "2021-02-27"
              and "stats.gov.cn" in items[1]["url"],
              "实际=%r" % items[1])


test_parse_page_count()
test_extract_date()
test_parse_items()
print("========== 结果汇总 ==========")
print("  通过: %d   失败: %d" % (_TOTAL[0] - len(_FAILED), len(_FAILED)))
if _FAILED:
    raise SystemExit(1)