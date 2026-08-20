# -*- coding: utf-8 -*-
"""统计管道测试：L1 纯函数单测 + L2 入库集成（DB 不可用时自动跳过）。

运行：python test_stat_scraper.py
覆盖：xlsx 自适应表头解析（真实样本+合成三种表形）、期间/区县/指标规范化、
正文规则抽取、详情页正文抽取、幂等去重、入库幂等。
"""
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stat_scraper as ss

_TOTAL = [0]
_FAILED = []


def check(name, cond, detail=""):
    _TOTAL[0] += 1
    if cond:
        print("[PASS] %s" % name)
    else:
        print("[FAIL] %s %s" % (name, detail))
        _FAILED.append(name)


# ============================================================
# L1 纯函数
# ============================================================
def test_region_normalize():
    check("region 新宁->新宁县", ss.REGION_MAP["新宁"] == "新宁县")
    check("region 邵东->邵东市", ss.REGION_MAP["邵东"] == "邵东市")
    check("region 全市保留", ss.REGION_MAP["全市"] == "全市")
    check("region 城步->城步苗族自治县", ss.REGION_MAP["城步"] == "城步苗族自治县")


def test_period_normalize():
    check("期间 2025年1-9月 原样", ss.normalize_period("2025年1-9月", "2025") == "2025年1-9月")
    check("期间 1-9月 补年份", ss.normalize_period("1-9月", "2025") == "2025年1-9月")
    check("期间 1-2月 补年份", ss.normalize_period("1-2月", "2025") == "2025年1-2月")
    check("期间 2025年 原样", ss.normalize_period("2025年", "2025") == "2025年")
    check("期间 带空格清洗", ss.normalize_period(" 2025年1-9月 ", "2025") == "2025年1-9月")


def test_clean_name():
    check("清洗前导编号", ss.clean_indicator_name("一、地区生产总值（GDP）") == "地区生产总值（GDP）")
    check("清洗括号编号", ss.clean_indicator_name("（一）增值税") == "增值税")
    check("清洗空白", ss.clean_indicator_name(" 规模工业增加值 ") == "规模工业增加值")


def test_to_number():
    check("千分位", ss._to_number("1,974.6101") == 1974.6101)
    check("百分号后缀", ss._to_number("4.958%") == 4.958)
    check("负数", ss._to_number("-6.249") == -6.249)
    check("空值", ss._to_number("") is None)
    check("占位符", ss._to_number("--") is None)


def test_unit_from_header():
    check("单位 亿元", ss._unit_from_header("绝对额(亿元)") == "亿元")
    check("单位 万元", ss._unit_from_header("绝对额（万元）") == "万元")
    check("单位 %", ss._unit_from_header("增速(%)") == "%")
    check("无单位", ss._unit_from_header("指标") == "")


def _build_wb(sheets_data):
    import openpyxl
    wb = openpyxl.Workbook()
    wb.remove(wb.active)
    for name, rows in sheets_data.items():
        ws = wb.create_sheet(name)
        for row in rows:
            ws.append(row)
    buf = io.BytesIO()
    wb.save(buf)
    buf.seek(0)
    return buf


def test_synthetic_shapes():
    buf = _build_wb({
        "地区生产总值": [
            ["地区生产总值", "", "", ""],
            ["", "", "", ""],
            ["地  区", "2025年1-9月", "", ""],
            ["", "绝对额(万元)", "增速(%)", "排名"],
            ["全市", "19746101", "4.958", ""],
            ["新宁", "1041963.13", "4.5", "10"],
            ["邵东", "1655627", "6.4", "1"],
        ],
        "财政19": [
            ["一般公共预算收入", "", "", ""],
            ["", "", "", ""],
            ["项  目", "1-9月", "", ""],
            ["", "绝对额(万元)", "增长（%）", ""],
            ["地方一般公共预算收入", "861488", "-6.2490273813242", ""],
            ["增值税", "148678", "7.03497329129051", ""],
        ],
        "工业8": [
            ["规模工业大类行业增加值", "", ""],
            ["", "", ""],
            ["行  业", "1-9月", ""],
            ["", "增速（%）", ""],
            ["煤炭开采和洗选业", "-37.0531114637521", ""],
            ["黑色金属矿采选业", "-9.90008626967747", ""],
        ],
        "81图5公共财政收入": [
            ["湖南省一般公共预算收入及增速", "", "", ""],
            ["年份", "", "绝对额", "增速"],
            ["2021年", "1-5月", "1961.0209666533", "17.7181740659892"],
        ],
    })
    rows = ss.parse_xlsx_workbook(buf.read(), "2025年9月月报卡数据")
    by = {(r["indicator_name"], r["region"], r["period"]): r for r in rows}
    gdp_newning = by.get(("地区生产总值", "新宁县", "2025年1-9月"))
    check("A 区县表: 新宁县 GDP", gdp_newning is not None, "not found")
    check("A 区县表: 新宁 value 万元", gdp_newning and gdp_newning["value"] == 1041963.13,
          str(gdp_newning))
    check("A 区县表: 新宁 增速", gdp_newning and gdp_newning["growth_rate"] == 4.5,
          str(gdp_newning))
    check("A 区县表: 邵东市 归一化", any(r["region"] == "邵东市" for r in rows), "")
    rank_newning = next((r for r in rows
                         if r["indicator_name"] == "地区生产总值排名" and r["region"] == "新宁县"), None)
    check("A 区县表: 排名独立指标", rank_newning is not None and rank_newning["value"] == 10
          and rank_newning["unit"] == "名", str(rank_newning))
    fiscal = by.get(("地方一般公共预算收入", "全市", "2025年1-9月"))
    check("B 指标表: 财政 1-9月 补年份", fiscal is not None, "not found")
    check("B 指标表: 财政 value/增速", fiscal and fiscal["value"] == 861488
          and round(fiscal["growth_rate"], 2) == -6.25, str(fiscal))
    coal = next((r for r in rows if r["indicator_name"] == "煤炭开采和洗选业"), None)
    check("C 增速表: 仅增速", coal is not None and coal.get("value") is None
          and round(coal["growth_rate"], 2) == -37.05, str(coal))
    check("跳过湖南省年份表", all(r["region"] != "湖南省" for r in rows) and len(rows) > 0, "")


def test_period_year_from_column_header():
    # 2024年2月月报卡的「分县（市、区）GDP」表头为 2023年 + 1-12月（上年全年），
    # 期间应取列头年份 2023，而非文档标题年份 2024。
    buf = _build_wb({
        "分县（市、区）GDP": [
            ["分县（市、区）GDP", "", "", ""],
            ["计量单位：万元", "", "", ""],
            ["", "2023年", "累计比", "去年同期"],
            ["", "", "增减%", ""],
            ["", "1-12月", "增减%", "去年同期%"],
            ["全市", "27314151", "4.806", ""],
            ["邵东", "7633164.87218397", "5.5", ""],
        ],
        "分县（市、区）社会消费品零售总额": [
            ["分县（市、区）社会消费品零售总额", "", "", "", ""],
            ["计量单位：万元", "", "", "", ""],
            ["", "2024年", "累计比", "去年同期", ""],
            ["", "", "增减%", "", ""],
            ["", "1-2月", "增减%", "去年同期%", "位"],
            ["全市", "234005", "-10.2843", "", ""],
            ["邵东", "8004.47", "21.696", "8", ""],
        ],
    })
    rows = ss.parse_xlsx_workbook(buf.read(), "2024年2月月报卡数据")
    gdp = next((r for r in rows if r["indicator_name"] == "分县（市、区）GDP"
                and r["region"] == "邵东市"), None)
    check("分县GDP: 取列头年份 2023", gdp is not None and gdp["period"] == "2023年1-12月",
          str(gdp))
    retail = next((r for r in rows if r["indicator_name"] == "分县（市、区）社会消费品零售总额"
                   and r["region"] == "邵东市"), None)
    check("分县社零: 取列头年份 2024", retail is not None and retail["period"] == "2024年1-2月",
          str(retail))
    check("分县GDP: 数值保留", gdp is not None and round(gdp["value"], 2) == 7633164.87,
          str(gdp))


def test_period_fallback_from_title_month():
    # 2021 年及更早期月报卡「分县」表头无任何期间信息（本月止累计型），
    # 期间按文档标题月份兜底：2021年12月月报卡 -> 2021年1-12月。
    buf = _build_wb({
        "分县（市、区）GDP": [
            ["分县（市、区）GDP", "", "", ""],
            ["计量单位：万元", "", "", ""],
            ["", "本月止", "累计比", "增速"],
            ["", "", "上年同", ""],
            ["", "累    计", "期±%", "排位"],
            ["全市", "24615348", "8.5", ""],
            ["邵东", "6852100.6634", "9.4", "1"],
        ],
    })
    rows = ss.parse_xlsx_workbook(buf.read(), "2021年12月月报卡数据")
    gdp = next((r for r in rows if r["indicator_name"] == "分县（市、区）GDP"
                and r["region"] == "邵东市"), None)
    check("无表头期间: 按标题月份兜底 1-12月", gdp is not None and gdp["period"] == "2021年1-12月",
          str(gdp))


def test_real_sample():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata", "yuebao_202509.xlsx")
    if not os.path.exists(path):
        check("真实样本夹具存在", False, "缺少 testdata/yuebao_202509.xlsx")
        return
    with open(path, "rb") as f:
        rows = ss.parse_xlsx_workbook(f.read(), "2025年9月月报卡数据")
    by = {(r["indicator_name"], r["region"], r["period"]): r for r in rows}
    gdp = by.get(("地区生产总值（GDP）", "全市", "2025年1-9月")) or by.get(
        ("地区生产总值", "全市", "2025年1-9月"))
    check("真实样本: 全市 GDP", gdp is not None, "keys=%d" % len(by))
    check("真实样本: GDP 数值单位", gdp and gdp["value"] == 1974.6101 and gdp["unit"] == "亿元",
          str(gdp))
    check("真实样本: GDP 增速", gdp and gdp["growth_rate"] == 4.958, str(gdp))
    xinning = next((r for r in rows if r["region"] == "新宁县"), None)
    check("真实样本: 新宁县区域行", xinning is not None, "")
    rank = next((r for r in rows if r["indicator_name"] == "地区生产总值排名" and r["region"] == "新宁县"), None)
    check("真实样本: 新宁县 GDP 排名", rank is not None and rank["value"] == 10 and rank["unit"] == "名",
          str(rank))
    fiscal = next((r for r in rows
                  if r["sheet_name"] == "财政19" and r["indicator_name"] == "地方一般公共预算收入"
                  and r["region"] == "全市"), None)
    check("真实样本: 一般公共预算收入", fiscal is not None and fiscal["value"] == 861488
          and round(fiscal["growth_rate"], 2) == -6.25, str(fiscal))
    check("真实样本: 无湖南省/年份表混入", all("湖南省" not in r["region"] for r in rows), "")
    check("真实样本: 非全市区域均为官方全名",
          all(r["region"] in set(ss.REGION_MAP.values()) for r in rows), "")
    sheets = {r["sheet_name"] for r in rows}
    check("真实样本: 覆盖 38 个数据 sheet", len(sheets) >= 30, str(len(sheets)))


def test_region_in_name_not_header():
    buf = _build_wb({
        "核算5": [
            ["地区生产总值", "", "", ""],
            ["", "", "", ""],
            ["指  标", "2025年1-9月", "", ""],
            ["", "绝对额(亿元)", "增速(%)", ""],
            ["一、地区生产总值（GDP）", "1974.6101", "4.958", ""],
            ["按行业分", "", "", ""],
            ["  农、林、牧、渔业", "319.407944725647", "4.4", ""],
        ],
    })
    rows = ss.parse_xlsx_workbook(buf.read(), "2025年9月月报卡数据")
    gdp = next((r for r in rows if r["indicator_name"] == "地区生产总值（GDP）"), None)
    check("回归: 指标名含地区不作表头", gdp is not None and gdp["value"] == 1974.6101
          and gdp["unit"] == "亿元" and gdp["growth_rate"] == 4.958, str(gdp))


def test_rule_extract():
    text = ("初步核算，2025年全市完成地区生产总值2883.5亿元、增长0.2%。其中，"
            "第一产业完成增加值467.4亿元、增长3.9%，第二产业完成增加值940.0亿元、下降8.4%，"
            "第三产业完成增加值1476.1亿元、增长5.6%。全市社会消费品零售总额达到980.5亿元、增长5.8%。")
    rows = ss.rule_extract_indicators(text, "BULLETIN", "2025年")
    by = {r["indicator_code"]: r for r in rows}
    gdp = by.get("地区生产总值（GDP）")
    check("规则: GDP 数值/增速", gdp and gdp["value"] == 2883.5 and gdp["unit"] == "亿元"
          and gdp["growth_rate"] == 0.2, str(gdp))
    check("规则: 一产", by.get("第一产业增加值") and by["第一产业增加值"]["value"] == 467.4
          and by["第一产业增加值"]["growth_rate"] == 3.9, str(by.get("第一产业增加值")))
    check("规则: 二产 下降为负", by.get("第二产业增加值") and by["第二产业增加值"]["growth_rate"] == -8.4,
          str(by.get("第二产业增加值")))
    check("规则: 三产", by.get("第三产业增加值") and by["第三产业增加值"]["value"] == 1476.1, "")
    check("规则: 社零", by.get("社会消费品零售总额") and by["社会消费品零售总额"]["value"] == 980.5, "")
    check("规则: 去重", len(rows) == 5, str(len(rows)))


def test_extract_doc_content():
    html = ('<html><body><div class="wenz"><UCAPCONTENT>'
            "<p>2025年全市完成地区生产总值<font>2883.5</font>亿元、增长0.2%。</p>"
            '<p style="text-align:center;"><a href="abc/files/1.xlsx">2025年9月月报卡数据</a></p>'
            "</UCAPCONTENT></div>"
            '<div class="info_ewm">扫一扫在手机打开当前页</div></body></html>')
    text, attach = ss.extract_doc_content(html)
    check("正文: 数字跨标签拼接", "2883.5亿元" in text, text)
    check("正文: 去导航噪音", "扫一扫" not in text, text)
    check("附件: 相对链接", attach == "abc/files/1.xlsx", str(attach))


# ============================================================
# L2 入库集成（MySQL 不可用时跳过）
# ============================================================
def _db_conn():
    try:
        import pymysql
        return pymysql.connect(**ss.DB)
    except Exception:
        return None


def test_db_integration():
    conn = _db_conn()
    if conn is None:
        check("L2: MySQL 可用", False, "跳过（无数据库）")
        _FAILED.pop()  # 数据库不可用不算失败
        _TOTAL[0] -= 1
        return
    cur = conn.cursor()
    fake_gid = 90000001
    try:
        # stat_doc upsert 幂等
        sql = ("INSERT INTO stat_doc (gov_record_id, category, title, doc_date, source_url, "
               "content, parse_status) VALUES (%s,'统计月报','测试月报','2025-09-01','http://t/1',%s,%s) "
               "ON DUPLICATE KEY UPDATE content=VALUES(content), parse_status=VALUES(parse_status)")
        cur.execute(sql, (fake_gid, "正文1", "DONE"))
        cur.execute(sql, (fake_gid, "正文2", "XLSX_DONE"))
        cur.execute("SELECT COUNT(*) FROM stat_doc WHERE gov_record_id=%s", (fake_gid,))
        n = cur.fetchone()[0]
        cur.execute("SELECT content, parse_status FROM stat_doc WHERE gov_record_id=%s", (fake_gid,))
        content, status = cur.fetchone()
        conn.commit()
        check("L2: stat_doc upsert 幂等", n == 1 and content == "正文2" and status == "XLSX_DONE",
              "n=%s content=%s status=%s" % (n, content, status))
        # stat_indicator INSERT IGNORE 幂等
        cur.execute("SELECT id FROM stat_doc WHERE gov_record_id=%s", (fake_gid,))
        doc_id = cur.fetchone()[0]
        rows = [dict(indicator_name="测试指标", period="2025年1-9月", region="新宁县",
                     value=100, unit="万元", sheet_name="测试表", source_type="XLSX",
                     confidence="high", generator_type="RULE")]
        ss.insert_indicator_rows(cur, doc_id, rows, "stat_indicator")
        ss.insert_indicator_rows(cur, doc_id, rows, "stat_indicator")  # 重跑不重复
        cur.execute("SELECT COUNT(*) FROM stat_indicator WHERE stat_doc_id=%s", (doc_id,))
        check("L2: stat_indicator 幂等", cur.fetchone()[0] == 1, "duplicated")
        conn.commit()
    finally:
        cur.execute("DELETE FROM stat_indicator WHERE stat_doc_id IN "
                    "(SELECT id FROM stat_doc WHERE gov_record_id=%s)", (fake_gid,))
        cur.execute("DELETE FROM stat_doc WHERE gov_record_id=%s", (fake_gid,))
        conn.commit()
        cur.close()
        conn.close()


# ============================================================
def main():
    test_region_normalize()
    test_period_normalize()
    test_clean_name()
    test_to_number()
    test_unit_from_header()
    test_synthetic_shapes()
    test_period_year_from_column_header()
    test_period_fallback_from_title_month()
    test_real_sample()
    test_region_in_name_not_header()
    test_rule_extract()
    test_extract_doc_content()
    test_db_integration()
    print("\n共 %d 项，失败 %d 项" % (_TOTAL[0], len(_FAILED)))
    if _FAILED:
        print("失败项: %s" % ", ".join(_FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
