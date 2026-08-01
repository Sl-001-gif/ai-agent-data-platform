# -*- coding: utf-8 -*-
"""
gov_scraper 核心规则回归测试（不联网，bs4 缺失时自动注入桩）

用法：
    python test_gov_scraper.py
覆盖：噪音过滤（误杀/漏杀回归）、容器词元匹配、分页配置归一化、decide_next_url 全分支。
"""
import re
import sys
import types

# 优先真实 bs4；缺失时注入桩（桩只满足编译期 import，解析类测试需真实 bs4）
try:
    import bs4  # noqa: F401
except ImportError:
    _stub_bs4 = types.ModuleType("bs4")
    _stub_bs4.BeautifulSoup = lambda *a, **k: None
    sys.modules["bs4"] = _stub_bs4
try:
    import requests  # noqa: F401
except ImportError:
    sys.modules["requests"] = types.ModuleType("requests")
try:
    import pymysql  # noqa: F401
except ImportError:
    sys.modules["pymysql"] = types.ModuleType("pymysql")

from gov_scraper import (  # noqa: E402
    build_noise_filter,
    build_page_url,
    decide_next_url,
    filter_candidate_link,
    normalize_pagination,
)


class FakeNode(object):
    """极简容器桩：仅提供 filter_candidate_link 需要的 name/class/id/parent。"""

    def __init__(self, name="li", cls=None, cid=None, parent=None):
        self.name = name
        self._cls = cls or []
        self._cid = cid
        self.parent = parent

    def get(self, key):
        if key == "class":
            return self._cls
        if key == "id":
            return self._cid
        return None


class FakeResp(object):
    """极简响应桩：仅提供 fetch_tree/resolve_category_list_url 需要的接口。"""

    def __init__(self, status=200, text=""):
        self.status_code = status
        self.text = text

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError("HTTP %d" % self.status_code)

    def close(self):
        pass


class FakeSession(object):
    """会话桩：默认 404；mapping 精确命中返回对应响应，记录请求 URL/参数。"""

    def __init__(self, response=None, mapping=None):
        self._response = response if response is not None else FakeResp(404, "")
        self._mapping = mapping or {}
        self.calls = []

    def get(self, url, params=None, timeout=None, stream=False):
        self.calls.append((url, dict(params or {})))
        if self._mapping:
            resp = self._mapping.get(url)
            return resp if resp is not None else FakeResp(404, "")
        return self._response


def alink(href, text):
    return types.SimpleNamespace(href=href, get_text=lambda: text)


_PASSED = [0]
_FAILED = [0]


def check(name, cond):
    if cond:
        _PASSED[0] += 1
    else:
        _FAILED[0] += 1
        print("FAIL: %s" % name)


def main():
    rules = build_noise_filter()
    body = FakeNode(name="li")

    # --- 误杀回归：真实政务标题不得被过滤 ---
    for title in (
        "关于切实做好2023年进入主汛期有关工作的通知",
        "某某项目招标详情公告",
        "市政府关于印发进一步优化营商环境若干措施的通知",
        "关于公布政府信息公开工作机构信息的通知",
    ):
        keep, _ = filter_candidate_link(body, alink("/art/x.shtml", title), "/art/x.shtml", title, rules)
        check("真实标题不误杀[%s]" % title[:10], keep is True)

    # --- 漏杀回归：导航噪音必须被过滤 ---
    for title, href in (
        ("网站地图", "/wzdt/x.shtml"),
        ("联系我们", "/lxwm/x.shtml"),
        ("机构设置", "/col/123/index.shtml"),
        ("领导信箱", "/ldxx/x.shtml"),
        ("政策文件", "/xxgk/zcwj/index.shtml"),
    ):
        keep, _ = filter_candidate_link(body, alink(href, title), href, title, rules)
        check("导航噪音被过滤[%s]" % title, keep is False)
    # --- 栏目/专题/友情链接/外部服务（2026-08-01 实测补漏：政策文件/统计信息页正文嵌栏目块） ---
    for title, href in (
        ("统计公报", "/shaoyang/stjgb/xlist.shtml"),
        ("统计月报", "/shaoyang/tjyb/xlist.shtml"),
        ("打好经济增长主动仗", "/shaoyang/jjzzzdz/xlist.shtml"),
        ("国家部委网站", "/shaoyang/guojiabuwei/end_link.shtml"),
        ("政务微博", "https://weibo.com/u/6883973569"),
        ("智能问答", "http://hnweb.shaoyang.gov.cn/v2/shaoyang"),
        ("政务要闻", "/shaoyang/szwyw/xxwdt.shtml"),
    ):
        keep, _ = filter_candidate_link(body, alink(href, title), href, title, rules)
        check("栏目/外部链接被过滤[%s]" % title, keep is False)

    # --- 复跑实测补漏：导航/栏目壳页（政府数据/政民互动/部门子站栏目列表页） ---
    for title, href in (
        ("政府数据", "https://www.shaoyang.gov.cn/shaoyang/zfsj/xsjfb.shtml"),
        ("政民互动", "https://www.shaoyang.gov.cn/shaoyang/zmhd/tyhd_index.shtml"),
        ("环境质量", "https://hbj.shaoyang.gov.cn/syhbj/hjzlzk/lvlist.shtml"),
        ("市本级文件库", "https://www.shaoyang.gov.cn/shaoyang/sbjgfxwj/gfxwjlist.shtml"),
        ("政府领导", "https://www.shaoyang.gov.cn/shaoyang/szfldt/xszf.shtml"),
        ("规范性文件解读", "https://www.shaoyang.gov.cn/shaoyang/gfxwjjd/nzcjd.shtml"),
    ):
        keep, _ = filter_candidate_link(body, alink(href, title), href, title, rules)
        check("导航/栏目壳页被过滤[%s]" % title, keep is False)
    # 误杀回归：跨站真实内容与互动平台真实公告保留
    for title, href in (
        ("国务院关于同意郴州市建设国家可持续发展示范区的批复", "http://www.gov.cn/zhengce/content/2019-05/14/content_5391457.htm"),
        ("关于开展我市2022年《政府工作报告》“金点子”有奖征集活动的公告", "https://www.shaoyang.gov.cn/default/xhtml/tyhdpt/hd_myzj_content.html?conId=4238bc91bc5c4f09a7b6fbfc29892cea&siteNo=shaoyang"),
    ):
        keep, _ = filter_candidate_link(body, alink(href, title), href, title, rules)
        check("跨站真实内容不误杀[%s]" % title[:8], keep is True)

    keep, _ = filter_candidate_link(
        body, alink("/shaoyang/szfbwj/202607/e01b8db73faa46d6bd7516ad98eca8bb.shtml",
                    "邵阳市人民政府办公室关于印发《邵阳市市长质量奖管理办法》的通知"),
        "/shaoyang/szfbwj/202607/e01b8db73faa46d6bd7516ad98eca8bb.shtml",
        "邵阳市人民政府办公室关于印发《邵阳市市长质量奖管理办法》的通知", rules)
    check("真实记录不被误杀[市长质量奖]", keep is True)

    # --- 容器词元匹配：泛词容器不团灭，特异容器仍过滤 ---
    for cls_name, title in (
        (["page-content"], "邵阳市关于某某事项的公告"),
        (["link-list"], "关于印发某某方案的通知"),
        (["content-page"], "关于某某规划的批复"),
    ):
        c = FakeNode(name="div", cls=cls_name)
        c.parent = body
        keep, _ = filter_candidate_link(c, alink("/art/x.shtml", title), "/art/x.shtml", title, rules)
        check("泛词容器不团灭[%s]" % cls_name[0], keep is True)
    c_nav = FakeNode(name="ul", cls=["nav"], cid="menu")
    keep, _ = filter_candidate_link(c_nav, alink("/col/x.shtml", "机构设置"), "/col/x.shtml", "机构设置", rules)
    check("nav 容器导航链接被过滤", keep is False)
    c_pag = FakeNode(name="div", cls=["pagination-box"])
    c_pag.parent = body
    keep, _ = filter_candidate_link(c_pag, alink("/xxgk/2.shtml", "下一页"), "/xxgk/2.shtml", "下一页", rules)
    check("pagination 容器被过滤", keep is False)

    # --- 分页配置归一化 ---
    check("旧字符串 auto 兼容", normalize_pagination("auto")["mode"] == "auto")
    p2 = normalize_pagination({"mode": "page-param", "page_param": "pageNo", "page_start": 2})
    check("结构化 pageNo", p2["mode"] == "page-param" and p2["page_param"] == "pageNo" and p2["page_start"] == 2)
    check("未知模式回退 auto", normalize_pagination("bogus")["mode"] == "auto")
    check("None 回退 auto", normalize_pagination(None)["mode"] == "auto")
    check("pageNo 拼接", "pageNo=3" in build_page_url("https://x/xx.shtml", 3, page_param="pageNo"))

    # --- decide_next_url 全分支 ---
    pag_auto = {"mode": "auto", "page_param": "page", "page_start": 1, "max_pages": None}
    old_find = None
    import gov_scraper as g
    if hasattr(g, "find_next_page_url"):
        old_find = g.find_next_page_url
        g.find_next_page_url = lambda html, base: "https://x/p2.shtml" if "下一页" in html else None
    try:
        n1, u1 = decide_next_url("<a>下一页</a>", "https://x/xx.shtml", "https://x/xx.shtml", pag_auto, 2, 2, 5)
        check("auto 下一页优先", n1 == "https://x/p2.shtml" and not u1)
        n2, u2 = decide_next_url("<div>x</div>", "https://x/xx.shtml", "https://x/xx.shtml", pag_auto, 2, 2, 5)
        check("auto 参数兜底 page=3", n2 is not None and u2 and "page=3" in n2)
        n3, _ = decide_next_url("<div>x</div>", "https://x/xx.shtml", "https://x/xx.shtml", pag_auto, 5, 5, 5)
        check("达上限停止", n3 is None)
        n4, u4 = decide_next_url(
            "<div>x</div>", "https://x/xx.shtml", "https://x/xx.shtml",
            {"mode": "next-link", "page_param": "page", "page_start": 1, "max_pages": None}, 2, 2, 5)
        check("next-link 无下一页不兜底", n4 is None and not u4)
        n5, u5 = decide_next_url(
            "<div>x</div>", "https://x/xx.shtml", "https://x/xx.shtml",
            {"mode": "page-param", "page_param": "page", "page_start": 1, "max_pages": None}, 2, 2, 5)
        check("page-param 直连参数", n5 is not None and u5 and "page=3" in n5)
        # --- 分页自动发现链：后缀式（createPageHTML 签名B）与 pageCount 上限 ---
        sig_b = ("<div><script>createPageHTML('page_div',10, 1,'xlist','shtml',189);</script></div>")
        r_sig = g.detect_suffix_pagination(sig_b, "https://www.shaoyang.gov.cn/shaoyang/stjgb/xlist.shtml")
        check("detect 签名B 后缀前缀",
              r_sig is not None and r_sig[0] == "https://www.shaoyang.gov.cn/shaoyang/stjgb/xlist_"
              and r_sig[1] == ".shtml" and r_sig[2] == 10)
        n8, u8 = g.decide_next_url(
            sig_b, "https://www.shaoyang.gov.cn/shaoyang/stjgb/xlist.shtml",
            "https://www.shaoyang.gov.cn/shaoyang/stjgb/xlist.shtml", pag_auto, 1, 1, 10)
        check("auto 后缀式(签名B)翻页", n8 == "https://www.shaoyang.gov.cn/shaoyang/stjgb/xlist_2.shtml" and u8)
        sig_1page = ("<div><script>createPageHTML('page_div',1, 1,'xlist','shtml',5);</script></div>")
        n9, _u9 = g.decide_next_url(
            sig_1page, "https://www.shaoyang.gov.cn/shaoyang/zcwjf/xlist.shtml",
            "https://www.shaoyang.gov.cn/shaoyang/zcwjf/xlist.shtml", pag_auto, 1, 1, 5)
        check("auto pageCount=1 单页停止", n9 is None)
    finally:
        if old_find is not None:
            g.find_next_page_url = old_find

    # --- tree 模式：fetch_tree JSONP 解包 + resolve_category_list_url 变体探测 ---
    from gov_scraper import fetch_tree, resolve_category_list_url
    import json as _json
    src_tree = {
        "name": "测试树",
        "list_url": "https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml",
        "tree": {
            "api": "/u/channel/treeNew/shaoyang",
            "params": {"level": "1", "channelId": "979dc6eb921b418a9355bc6eb2eac8f4"},
        },
    }
    nodes_payload = {
        "list": [
            {"name": "统计信息", "url": "/shaoyang/stjgb/xlist.shtml", "level": 2},
            {"name": "占位类目", "url": "/shaoyang/xxx/null.shtml", "level": 2},
        ]
    }
    jsonp_null = "null(" + _json.dumps(nodes_payload, ensure_ascii=False) + ");"
    sess = FakeSession(FakeResp(200, jsonp_null))
    got = fetch_tree(sess, src_tree)
    check("fetch_tree JSONP null 包裹解包", len(got) == 2 and got[0]["name"] == "统计信息")
    check("fetch_tree 请求 URL/参数正确",
          sess.calls and sess.calls[0][0].endswith("/u/channel/treeNew/shaoyang")
          and sess.calls[0][1]["channelId"] == "979dc6eb921b418a9355bc6eb2eac8f4")
    callback_jsonp = "JsonpCallBack(" + _json.dumps(nodes_payload, ensure_ascii=False) + ")"
    got2 = fetch_tree(FakeSession(FakeResp(200, callback_jsonp)), src_tree)
    check("fetch_tree 回调包裹解包", len(got2) == 2)
    got3 = fetch_tree(FakeSession(FakeResp(200, _json.dumps(nodes_payload, ensure_ascii=False))), src_tree)
    check("fetch_tree 纯 JSON 解包", len(got3) == 2)
    got4 = fetch_tree(FakeSession(FakeResp(200, 'null({"list": []})')), src_tree)
    check("fetch_tree 空列表返回空", got4 == [])
    try:
        fetch_tree(FakeSession(FakeResp(200, "x")), {"name": "无树", "list_url": "https://x/"})
        check("fetch_tree 缺 tree.api 抛错", False)
    except RuntimeError:
        check("fetch_tree 缺 tree.api 抛错", True)

    tbase = "https://www.shaoyang.gov.cn"
    real_url = resolve_category_list_url("/shaoyang/stjgb/xlist.shtml", tbase, FakeSession(), 0)
    check("resolve 真实 URL 原样返回", real_url == tbase + "/shaoyang/stjgb/xlist.shtml")
    check("resolve 空 URL 返回 None", resolve_category_list_url("", tbase, FakeSession(), 0) is None)
    check("resolve javascript: 返回 None",
          resolve_category_list_url("javascript:void(0)", tbase, FakeSession(), 0) is None)
    probe_map = {tbase + "/shaoyang/xxx/xlist.shtml": FakeResp(200, "<html>ok</html>")}
    probe1 = resolve_category_list_url("/shaoyang/xxx/null.shtml", tbase, FakeSession(mapping=probe_map), 0)
    check("resolve null.shtml 首变体 xlist 命中", probe1 == tbase + "/shaoyang/xxx/xlist.shtml")
    probe_map2 = {tbase + "/shaoyang/yyy/xxgkList.shtml": FakeResp(200, "<html>ok</html>")}
    probe2 = resolve_category_list_url("/shaoyang/yyy/null.shtml", tbase, FakeSession(mapping=probe_map2), 0)
    check("resolve null.shtml 变体 xxgkList 命中", probe2 == tbase + "/shaoyang/yyy/xxgkList.shtml")
    all404 = resolve_category_list_url("/shaoyang/zzz/null.shtml", tbase, FakeSession(), 0)
    check("resolve null.shtml 全 404 返回 None", all404 is None)
    print("结果: %d 通过, %d 失败" % (_PASSED[0], _FAILED[0]))
    return 1 if _FAILED[0] else 0


if __name__ == "__main__":
    sys.exit(main())
