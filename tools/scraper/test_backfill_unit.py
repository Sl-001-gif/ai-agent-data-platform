# -*- coding: utf-8 -*-
"""
L1 单测：发文单位提取正则 + 域名/类目推断映射（纯函数，无需联网/数据库）。
运行：python test_backfill_unit.py
"""
import backfill_unit as bu

_TOTAL = [0]
_FAILED = []


def check(name, cond, detail=""):
    _TOTAL[0] += 1
    if cond:
        print("[PASS] %s" % name)
    else:
        print("[FAIL] %s %s" % (name, detail))
        _FAILED.append(name)


def test_host_of():
    check("host_of 子站",
          bu.host_of("https://fgw.shaoyang.gov.cn/syfgw/jfgl/201506/x.shtml") == "fgw.shaoyang.gov.cn",
          "实际=%s" % bu.host_of("https://fgw.shaoyang.gov.cn/syfgw/jfgl/201506/x.shtml"))
    check("host_of 门户",
          bu.host_of("https://www.shaoyang.gov.cn/shaoyang/xxgk/1.shtml") == "www.shaoyang.gov.cn")
    check("host_of 空返回空串", bu.host_of("") == "")


def test_domain_map():
    check("fgw -> 市发改委",
          bu.infer_unit("https://fgw.shaoyang.gov.cn/syfgw/jfgl/1.shtml", "行政事业收费和物价调控")
          == "邵阳市发展和改革委员会")
    check("wjw -> 市卫健委",
          bu.infer_unit("https://wjw.shaoyang.gov.cn/xxgk/1.shtml", "通知公告") == "邵阳市卫生健康委员会")
    check("hunan -> 省政府办公厅",
          bu.infer_unit("http://www.hunan.gov.cn/hnszf/xxgk/1.html", "上级政府及部门规范性文件")
          == "湖南省人民政府办公厅")
    check("gov.cn -> 国务院办公厅",
          bu.infer_unit("http://www.gov.cn/xxgk/1.html", "上级政府及部门规范性文件") == "国务院办公厅")


def test_category_map():
    check("统计信息 -> 市统计局",
          bu.infer_unit("https://www.shaoyang.gov.cn/shaoyang/xxgk/1.shtml", "统计信息") == "邵阳市统计局")
    check("财政信息 -> 市财政局",
          bu.infer_unit("https://www.shaoyang.gov.cn/shaoyang/xxgk/2.shtml", "财政信息") == "邵阳市财政局")
    check("重大会议信息 -> 市政府办",
          bu.infer_unit("https://www.shaoyang.gov.cn/shaoyang/xxgk/3.shtml", "重大会议信息")
          == "邵阳市人民政府办公室")
    check("规范性文件解读 -> 市司法局",
          bu.infer_unit("https://www.shaoyang.gov.cn/shaoyang/xxgk/4.shtml", "规范性文件解读")
          == "邵阳市司法局")


def test_unknown_returns_empty():
    check("未知域名+未知类目 -> 空", bu.infer_unit("https://example.com/a.shtml", "未知类目") == "")
    check("未知域名+空类目 -> 空", bu.infer_unit("https://example.com/a.shtml", "") == "")


def test_domain_takes_priority():
    check("域名推断优先于类目代理",
          bu.infer_unit("https://fgw.shaoyang.gov.cn/a.shtml", "统计信息") == "邵阳市发展和改革委员会")


def test_extract_unit_labels():
    check("发布单位", bu.extract_unit("发布单位：邵阳市财政局") == "邵阳市财政局")
    check("发文机关", bu.extract_unit("发文机关：邵阳市人民政府办公室") == "邵阳市人民政府办公室")
    check("公开单位（无冒号）", bu.extract_unit("公开单位 邵阳市司法局") == "邵阳市司法局")
    check("信息发布单位", bu.extract_unit("信息发布单位:邵阳市发展和改革委员会") == "邵阳市发展和改革委员会")
    check("来源单位", bu.extract_unit("来源单位：邵阳市统计局") == "邵阳市统计局")


def test_extract_unit_source():
    check("信息来源", bu.extract_unit("信息来源：邵阳市生态环境局") == "邵阳市生态环境局")
    check("消息来源", bu.extract_unit("消息来源:邵阳市应急管理局") == "邵阳市应急管理局")


def test_extract_unit_none():
    check("无标签返回空串", bu.extract_unit("<html><body>正文内容无单位标签</body></html>") == "")


def test_metrics_sync_complete():
    check("口径同步包含 4 项", len(bu.METRICS_SYNC) == 4, "实际=%d" % len(bu.METRICS_SYNC))
    names = {m["name"] for m in bu.METRICS_SYNC}
    check("口径名称正确", names == {"发文量", "类目占比", "平均每日发文量", "单位发文量"},
          "实际=%s" % sorted(names))
    unit_metric = [m for m in bu.METRICS_SYNC if m["name"] == "单位发文量"][0]
    check("单位发文量口径含类目回退",
          "COALESCE(NULLIF(publish_unit" in unit_metric["calculation_formula"])
    avg_metric = [m for m in bu.METRICS_SYNC if m["name"] == "平均每日发文量"][0]
    check("日均发文量口径防除零",
          "NULLIF(DATEDIFF" in avg_metric["calculation_formula"])


def main():
    test_host_of()
    test_domain_map()
    test_category_map()
    test_unknown_returns_empty()
    test_domain_takes_priority()
    test_extract_unit_labels()
    test_extract_unit_source()
    test_extract_unit_none()
    test_metrics_sync_complete()
    print("TOTAL=%d FAILED=%d" % (_TOTAL[0], len(_FAILED)))
    if _FAILED:
        for name in _FAILED:
            print("  FAILURE: %s" % name)
        raise SystemExit(1)
    print("结论：全部通过")


if __name__ == "__main__":
    main()
