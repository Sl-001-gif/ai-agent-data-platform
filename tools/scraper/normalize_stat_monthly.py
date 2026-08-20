# -*- coding: utf-8 -*-
"""stat_monthly 表单归一重建管线（2026-08-19 方案，D1-D4 已确认）。

用法：
  python tools/scraper/normalize_stat_monthly.py --analyze   # 只读：版本/格式指纹 + 归一览 + 冲突预估
  python tools/scraper/normalize_stat_monthly.py --rebuild   # 备份 stat_monthly 后重建 + CSV + 回归锚点

规则（评审稿 docs/plans/2026-08-19-stat-monthly-normalize.md + 落地修正）：
  P-period ：2018年1-02月 -> 2018年1-2月；1-9月累计/9月末 补所属文件年份；D1：9月末 -> YYYY年1-9月
  R-region ：城步苗族自治县 -> 城步县；分市州行 region=市州名；综研室城步县错行修复；板块行 region=板块名
  I-indicator：官方词典归一 + 排名后缀保护 + 单位括号剥离重试；
             投资11/12/投资处1-8 行业行加「投资」后缀；贸易13 分项加「零售额」后缀（消同名不同义）
             noise 兜底：结构化单元格保留（剥 其中： 前缀），不再整行丢弃
  U-unit   ：万元 -> 亿元（地区生产总值/固定资产投资/工业投资/房地产开发投资/社会消费品零售总额）
  M-merge  ：同 (period, region, indicator) 合并，增速优先亿元口径（D3），差异记录冲突
  D-drop   ：仅剔指标名=区县/市州名 的噪音行（分市州/城步县/板块已先行重定向）
  R2-recaliber（--recaliber 可选，2026-08-20 阶段0 数据重处理）：
     --recaliber --analyze ：只读，输出最新期(2025年1-9月)指标全集覆盖率曲线 + 历史独有指标分类预估
     --recaliber --rebuild：备份 stat_monthly_bak_20260820a 后重建，叠加名称变体归一 + 单位统一 + 停发/变体清单 CSV
     陷阱指标（营业收入/利润总额/产品销售收入(主营业务收入)/增加值）只保留原名进 VARIANT 清单，严禁合并
"""
import argparse
import csv
import os
import re
import sys
from collections import Counter, defaultdict

import pymysql

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)
from indicator_governance import canonical, normalize as _norm, REGION_REMAP

DB = dict(host="localhost", port=3306, user="root", password="Admin@123456",
          database="ai_agent_data", charset="utf8mb4")
SRC = "stat_monthly_raw"
DST = "stat_monthly"
BAK = "20260819c"
CSV_PATH = os.path.join(ROOT, "docs", "data", "stat_monthly.csv")
BAK_RECALIBER = "20260820a"          # --recaliber 重建专用备份
LEFT_CSV = os.path.join(ROOT, "docs", "data", "recaliber-leftover.csv")

# ---- recaliber 名称变体映射（2026-08-20 全量数值口径核验后写死；历史期名 → 2025 最新期名） ----
VARIANT_MAP = {
    "金融机构各项存款": "各项存款",
    "金融机构各项贷款": "各项贷款",
    "生产总值(GDP)": "地区生产总值",
    "地区生产总值(GDP)": "地区生产总值",
    "实际利用境外资金": "外商直接投资",
    "卫生健康支出": "卫生健康",
    "全市用电总量(万千瓦小时)投资": "全市用电总量(亿千瓦小时)",   # 值 ÷10000 并入
    "全市用电总量(亿千瓦小时)投资": "全市用电总量(亿千瓦小时)",
    "商品房屋销售额投资": "商品房屋销售额",
    "商品房施工面积投资": "商品房施工面积",
    "商品房竣工面积投资": "商品房竣工面积",
    "商品房销售面积投资": "商品房销售面积",
    "其中、新开工项目个数(个)投资": "本年新开工",
    "新开工项目个数(个)投资": "本年新开工",
    "施工项目个数(个)投资": "施工项目个数",
    "农林牧渔业": "农、林、牧、渔业",
    "全社会客运量(万人)": "客运量(万人)",
    "客运周转量(万人公里)": "旅客周转量(万人公里)",
    "全社会货运量(万吨)": "公路(万吨)",
    "货运周转量(万吨公里)": "公路(万吨公里)",
}
RENAME_UNIT = {   # 改名同时纠正单位（防 merge 首行带错单位）
    "实际利用境外资金": "万美元",
    "卫生健康支出": "万元",
}
# 陷阱指标：与最新期同名但口径/期间语义不同，保留原名 + VARIANT 清单（严禁映射）
VARIANT_KEEP = {
    "营业收入": "2025 工业7 为规模工业营业收入滞后一月副本（1-9月=1-8月累计 1699.51），不并入规模工业营业收入",
    "利润总额": "2018-2024 主表(二)口径≠规模工业利润总额（2024 193.28亿 vs 188.93亿）；2025 工业7 为滞后副本，不合并",
    "产品销售收入(主营业务收入)": "2018-2024 语义≈规模工业营业收入，但 2024-12=2736.15 vs 2025 1-2月=2709.17 差约 1% 且存滞后风险，不合并",
    "增加值": "2018-2024 含绝对值，语义=规模工业增加值（2025 起只发 规模工业增加值），保留原名",
}
# 单位统一（只改 unit；规模工业利润总额 万元→亿元 同时改 value÷10000）
UNIT_RULES = {
    "规模工业利润总额": "亿元",
    "营业收入": "亿元",
    "利润总额": "亿元",
    "全市用电总量(亿千瓦小时)": "亿千瓦小时",
    "亏损面": "%",
    "居民消费价格指数": "%",
    "工业产品销售率": "%",
    "从业人员平均人数(人)": "人",
    "从业人员平均人数(万人)": "万人",
    "施工项目个数": "个",
    "本年新开工": "个",
    "本年投产项目个数": "个",
    "商品房施工面积": "万平方米",
    "商品房竣工面积": "万平方米",
    "商品房销售面积": "万平方米",
    "商品房屋销售额": "亿元",
}
NOISE_PREFIX = ("总计中：", "其中：", "其中:")
NOISE_EXACT = {"市级", "城步县(分县（市、区）规模工业增加值)", "经开区(分县（市、区）规模工业增加值)",
               "邵东县(分县（市、区）规模工业增加值)", "邵阳(分县（市、区）财政总收入)"}

# ---------------------------------------------------------------- period ----
PERIOD_STD = re.compile(r'^(\d{4})年(\d{1,2})-(\d{1,2})月$')
PERIOD_CUM = re.compile(r'^(\d{1,2})-(\d{1,2})月累计$')
PERIOD_END = re.compile(r'^(\d{1,2})月末$')


def norm_period(period, year):
    m = PERIOD_STD.match(period)
    if m:
        return "%d年%d-%d月" % (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    m = PERIOD_CUM.match(period)
    if m:
        return "%d年%d-%d月" % (year, int(m.group(1)), int(m.group(2)))
    m = PERIOD_END.match(period)
    if m:  # D1：9月末 -> 2025年1-9月（并入累计期；当前仅综研室存贷款表）
        return "%d年1-%d月" % (year, int(m.group(1)))
    return None


# ---------------------------------------------------------------- sheet ----
FENSHIZHOU = {
    "分市州GDP": "分市州GDP",
    "分市州固定资产投资": "分市州固定资产投资",
    "分市州出口总额": "分市州出口总额",
    "分市州地方财政收入": "分市州地方财政收入",
    "分市州消费品零售总额": "分市州消费品零售总额",
    "分市州规模工业增加值": "分市州规模工业增加值",
    "分市州进出口总额": "分市州进出口总额",
    "分市州出口": "分市州出口总额",
    "分市州进出口": "分市州进出口总额",
    "分市州地方一般公共预算收入": "分市州地方财政收入",
}
ZONGYANSHI = {"52综研室1": "各项存款", "53综研室2": "各项贷款"}
BAN_KUAI = {"大湘西地区", "洞庭湖地区", "湘南地区", "环长株潭城市群", "长株潭地区"}
TOUZI_SHEETS = {"投资11", "投资12", "33投资处1", "34投资处2", "35投资处4", "36投资处5", "37投资处6", "38投资处7", "主要经济指标完成情况（三）"}
TOUZI_KEEP = {"施工项目个数", "本年投产项目个数", "中央项目", "地方项目", "其中:本年新开工", "其中：本年新开工", "建筑安装工程", "设备工器具购置", "其他费用", "计划总投资额", "本年完成投资额", "全市用电总量（亿千瓦小时）", "其中：工业用电量", "其中:新开工项目个数（个）"}
SHEET_SUFFIX = {"贸易13": "零售额", "主要经济指标完成情况（四）": "零售额"}
RETAIL_ONLY = {   # 只有真正属于零售额的指标才加「零售额」后缀（消歧 vs 增加值）；(四) 表含进出口/外资/资金等非零售项
    "限额以上零售额", "其中:限额以上法人单位零售额", "其中：限额以上法人单位零售额",
    "粮油、食品类", "#粮油、食品类", "饮料类", "烟酒类", "服装、鞋帽、针纺织品类",
    "书报杂志类", "石油制品类", "汽车类", "批发和零售业", "住宿和餐饮业",
    "城镇", "乡村", "其中:城区", "其中：城区",
}
WAN_TO_YI = {"地区生产总值", "固定资产投资", "工业投资", "房地产开发投资", "社会消费品零售总额", "进出口", "出口", "进口"}
POLLUTED = {
    "全市", "双清", "大祥", "北塔", "新邵", "邵阳县", "隆回", "洞口", "绥宁", "新宁", "城步",
    "武冈", "邵东", "市辖区", "市本级", "双清区", "大祥区", "北塔区", "新邵县", "隆回县",
    "洞口县", "绥宁县", "新宁县", "城步苗族自治县", "武冈市", "邵东市", "邵阳", "邵阳市",
    "长沙市", "株洲市", "湘潭市", "衡阳市", "岳阳市", "常德市", "张家界市", "益阳市",
    "郴州市", "永州市", "怀化市", "娄底市", "湘西自治州", "全省合计",
}
REGION_ROW = {   # 分县表区域行被误解析成指标：区域行重定向（_norm 后匹配）
    "邵阳": "邵阳县",        # 邵 阳 / 邵阳（2018-2021 分县表 邵阳县 行，位于新邵与隆回之间）
    "邵东县": "邵东市",      # 2018 撤县设市前旧称
    "城步县": "城步县",      # 城步苗族自治县 简称
    "经开区": "经开区",      # 经 开 区 / 经开区
    "经开": "经开区",
    "邵阳经开区": "邵阳经开区",  # 2025 贸外处2
}

GROWTH_PRIORITY = {
    "固定资产投资（不含跨区项目）": 0,   # D3：增速优先亿元口径
    "固定资产投资(不含跨区项目)": 0,
    "固定资产投资": 1,                   # 主要经济指标完成情况（一）表头
    "分县（市、区）固定资产投资": 2,
    "分县(市、区)固定资产投资": 2,
    "外商直接投资（万美元）": 0,         # 外贸和房地产15 全市值趋势一致（638->673->720.9）
    "一般公共预算收入": 0,               # 2022 全口径：主表优先于分县表全市行
    "财政总收入": 0,                     # 2022 1-2/1-3月 主表优先
    "、财政总收入": 0,                   # 2021 主表优先
    "分县（市、区）财政一般预算收入": 0,  # 2019 地方级：分县表全市行优先（主表值为 2018 陈旧拷贝）
    "分县(市、区)财政一般预算收入": 0,
    "进出口总额（万美元）(上月数)": 0,    # 2018-2020 主表（四）美元口径官方值优先（2019年1-5月 82226 胜 分县 57734）
    "进出口总额(万美元)(上月数)": 0,
    "进出口总额（万美元）": 0,
    "进出口总额(万美元)": 0,
    "进出口总额（亿元）": 0,              # 2021+ 主表亿元口径官方值优先（2025 67.8 胜 分县折算 67.778）
    "进出口总额(亿元)": 0,
    "其中：出口": 0,                      # 主表出口官方值优先
    "其中:出口": 0,
}

# 滞后反转系列：分县（市、区）进出口表在 N月文件载 1-(N-1)月旧值（2023年1-3月：3月文件 260735 实为 1-2月值），
# 真实 1-N月值在 (N+1)月文件。该类同键双源时取更晚文件；其余系列取更早文件。
LAG_DOC_IND = {
    "分县（市、区）进出口总额", "分县（市、区）进出口总额排名",
    "分县(市、区)进出口总额", "分县(市、区)进出口总额排名",
}
TRAIL_PAREN = re.compile(r"[（(](亿元|万元|万美元|％|%)[）)]$")


def touzi_name(ind):
    if "投资" in ind or ind in TOUZI_KEEP:
        return ind
    return ind + "投资"


def canon_indicator(ind):
    """排名后缀保护 + 单位括号剥离重试 + 官方词典归一。"""
    if ind.endswith("排名"):
        base, status = canonical(ind[:-2])
        if base:
            return base + "排名", "remap"
        return _norm(ind), "keep"
    m = TRAIL_PAREN.search(ind)
    if m:
        canon2, st2 = canonical(ind[:m.start()])
        if canon2 and st2 != "noise":
            return canon2, st2
    return canonical(ind)


def sheet_major(rows):
    """(doc_id, sheet) -> 非板块主指标名集合（供板块行复用主指标）。"""
    out = defaultdict(set)
    for r in rows:
        sheet, name = r[9], r[4]
        if _norm(name) in BAN_KUAI or name.endswith("排名") or _norm(name) in REGION_ROW:
            continue
        out[(r[1], sheet)].add(_norm(name))
    return {k: v for k, v in out.items() if len(v) == 1}


def usd_doc_ids(rows):
    """美元口径文件：发布过「进出口总额（万美元）」行或分县进出口 unit=万美元 的文件（2018-2020）。
    2020 年 11 月文件（doc 289）起主表改用亿元口径（进出口总额（亿元）），不在该集合内。"""
    out = set()
    for r in rows:
        name = r[5]
        if "进出口" not in name and "出口" not in name and "进口" not in name:
            continue
        if "万美元" in name or (r[7] or "").strip() == "万美元":
            out.add(r[1])
    return out


def transform(row, doc_year, major_map, usd_docs):
    rid, doc_id, period, region, code, name, value, unit, growth, sheet, src, conf, gen = row
    year = doc_year.get(doc_id)
    ind = _norm(name)
    if not ind:
        return None, ("empty-name", 1)
    rec = dict(doc=doc_id, period=None, region=region.strip().replace(" ", ""),
               indicator=None, code=code, value=value, unit=(unit or "").strip(),
               growth=growth, sheet=sheet, src=src, conf=conf, gen=gen,
               src_ind=name, note=[], fixed=False)

    # ---- sheet 语义先行（这些行跳过通用 canonical） ----
    if sheet in FENSHIZHOU:                       # 分市州对比表
        rec["indicator"] = FENSHIZHOU[sheet]
        rec["region"] = ind
        rec["note"].append("fenshizhou")
        rec["fixed"] = True
    elif sheet in ZONGYANSHI:                     # 综研室存贷款
        rec["indicator"] = ZONGYANSHI[sheet]
        rec["unit"] = "亿元"                      # 分县行源 unit 为空，补全（全市行源即亿元）
        rec["growth"] = None                      # 增速列实为「同比增量(亿元)」，置空防污染
        rec["note"].append("zongyanshi")
        if ind == "城步县":                       # 城步县错行修复
            rec["region"] = "城步县"
            rec["note"].append("chengbu-repair")
        rec["fixed"] = True
    elif ind in BAN_KUAI:                         # 经济板块行
        major = major_map.get((doc_id, sheet))
        if major:
            cm, _st = canonical(next(iter(major)))
            rec["indicator"] = cm or next(iter(major))
            rec["region"] = ind
            rec["note"].append("bankuai")
        else:                                     # 主指标不可推断：sheet 标记防跨表碰撞
            rec["indicator"] = ind + "(" + sheet + ")"
            rec["note"].append("bankuai-unknown")
        rec["fixed"] = True

    if not rec["fixed"] and rec["region"] == "全市" and ind in REGION_ROW:
        major = major_map.get((doc_id, sheet))
        if major:
            cm, _st = canonical(next(iter(major)))
            rec["indicator"] = cm or next(iter(major))
        else:
            rec["indicator"] = ind + "(" + sheet + ")"
        rec["region"] = REGION_ROW[ind]
        rec["note"].append("region-row")
        rec["fixed"] = True

    p = norm_period(period, year)
    if p is None:
        return None, ("period-unknown:" + period, 1)
    rec["period"] = p
    rec["region"] = REGION_REMAP.get(rec["region"], rec["region"])

    # ---- indicator 归一 ----
    if not rec["fixed"]:
        if sheet in TOUZI_SHEETS:
            ind = touzi_name(ind)
        elif sheet in SHEET_SUFFIX:
            suffix = SHEET_SUFFIX[sheet]
            ind = re.sub(r'^按地区分[:：]城镇$', '城镇', ind)
            if suffix not in ind and ind in RETAIL_ONLY:
                ind = ind + suffix
        canon, status = canon_indicator(ind)
        if status == "noise" and not canon:       # 结构化单元格：剥 其中： 前缀后保留
            canon = re.sub(r'^其中[:：]\s*', '', ind) or canon
        if not canon or canon in POLLUTED:
            return None, ("polluted:" + name, 1)
        if status == "noise":
            rec["note"].append("noise-keep")
        rec["indicator"] = canon

    # ---- 进出口/出口/进口 万美元口径拆分 ----
    # 2018-2020 主表（四）为海关美元口径：分县表 unit=万美元；主表 unit 错标亿元，但 出口+进口=进出口 自洽。
    # 「美元口径文件」判定见 usd_doc_ids（2020 年 11 月文件起主表改用亿元口径，不拆分）。
    # 2021+ 人民币亿元口径与美元口径分列，避免同指标混币种。
    is_region_row = "region-row" in rec["note"]
    if (not rec["fixed"] or is_region_row) and rec["indicator"] in ("进出口", "出口", "进口", "进出口（万美元）", "出口（万美元）", "进口（万美元）"):
        base = rec["indicator"][:-5] if rec["indicator"].endswith("（万美元）") else rec["indicator"]
        if rec["doc"] in usd_docs or "万美元" in name or (unit or "").strip() == "万美元":
            if base in ("进出口", "出口", "进口"):
                rec["indicator"] = base + "（万美元）"
                rec["unit"] = "万美元"
                rec["note"].append("usd-split")

    # ---- 2022 全口径一般公共预算收入 -> 财政总收入（2023+ 仅发布地方级；全口径系列 2018-2022 名为 财政总收入）。
    # 按名称匹配：全口径名=一般公共预算收入/分县（市、区）一般公共预算收入（仅 2022 年 4-12 月文件）；
    # 地方级名含「地方」（1、地方一般公共预算收入 / 地方一般公共预算收入 / 分县…地方一般公共预算收入）；
    # 2022 年 2/3 月文件地方级仍用旧名「1、一般预算收入」（无 公共 二字），不匹配即安全。 ----
    if "地方" not in name and "一般公共预算收入" in name and not rec["fixed"]:
        if rec["indicator"] == "一般公共预算收入":
            rec["indicator"] = "财政总收入"
            rec["note"].append("budget-full-caliber")
        elif rec["indicator"] == "一般公共预算收入排名":
            rec["indicator"] = "财政总收入排名"
            rec["note"].append("budget-full-caliber")

    # ---- 单位折算 万元 -> 亿元 ----
    if unit == "万元" and rec["indicator"] in WAN_TO_YI and value is not None:
        rec["value"] = float(value) / 10000.0
        rec["unit"] = "亿元"
        rec["note"].append("wan-to-yi")
    elif rec["indicator"] in WAN_TO_YI and not rec["unit"]:
        rec["unit"] = "亿元"
    return rec, None


def merge_records(recs):
    groups = defaultdict(list)
    for r in recs:
        groups[(r["period"], r["region"], r["indicator"])].append(r)
    merged, conflicts = [], []
    for key, rows in groups.items():
        # 优先级：亿元口径(GROWTH_PRIORITY) > 文件先后（默认更早文件优先：主表 N月文件载 1-N月 真实值，
        # 后续文件的滞后区为同值副本；分县进出口系列在 LAG_DOC_IND 中反转：真实值在 (N+1)月文件）。
        rows.sort(key=lambda r: (GROWTH_PRIORITY.get(r["src_ind"], 9),
                                 r["doc"] if r["src_ind"] in LAG_DOC_IND else -r["doc"]))
        m = dict(rows[0])
        for r in rows[1:]:
            if m["value"] is None and r["value"] is not None:
                m["value"] = r["value"]
            if m["growth"] is None and r["growth"] is not None:
                m["growth"] = r["growth"]
            if not m["unit"]:
                m["unit"] = r["unit"]
            if m["value"] is not None and r["value"] is not None and abs(float(m["value"]) - float(r["value"])) > 0.01:
                conflicts.append((key, m["value"], r["value"], m["src_ind"], r["src_ind"], m["doc"], r["doc"]))
            if m["growth"] is not None and r["growth"] is not None and abs(float(m["growth"]) - float(r["growth"])) > 0.01:
                conflicts.append((key, "g:%s" % m["growth"], "g:%s" % r["growth"], m["src_ind"], r["src_ind"], m["doc"], r["doc"]))
        merged.append(m)
    return merged, conflicts, len(groups)


def fetch_all(conn):
    cur = conn.cursor()
    cur.execute("SELECT id, stat_doc_id, period, region, indicator_code, indicator_name, value, unit, growth_rate, sheet_name, source_type, confidence, generator_type FROM %s" % SRC)
    rows = cur.fetchall()
    cur.execute("SELECT id, YEAR(doc_date) FROM stat_doc")
    doc_year = dict(cur.fetchall())
    cur.close()
    return rows, doc_year


def analyze(conn, recaliber=False):
    rows, doc_year = fetch_all(conn)
    usd_docs = usd_doc_ids(rows)
    major = sheet_major(rows)
    print("== 版本/格式指纹（按文件） ==")
    cur = conn.cursor()
    cur.execute("SELECT r.stat_doc_id, d.doc_date, d.title, COUNT(*) n, COUNT(DISTINCT r.sheet_name) ns, COUNT(DISTINCT r.period) np, COUNT(DISTINCT r.indicator_name) ni FROM %s r JOIN stat_doc d ON d.id=r.stat_doc_id GROUP BY r.stat_doc_id ORDER BY d.doc_date" % SRC)
    for did, doc_date, title, n, ns, np, ni in cur.fetchall():
        cur2 = conn.cursor()
        cur2.execute("SELECT period, COUNT(*) FROM %s WHERE stat_doc_id=%%s GROUP BY period ORDER BY period" % SRC, (did,))
        fmts = " ".join("%s=%d" % (p, c) for p, c in cur2.fetchall()[:12])
        cur2.close()
        print("%-4s %-22s n=%-4d sheets=%-2d periods=%-2d inds=%-3d | %s" % (did, title, n, ns, np, ni, fmts))
    cur.close()

    print()
    print("== 归一预估 ==")
    recs, dropped = [], Counter()
    status = Counter()
    for r in rows:
        rec, err = transform(r, doc_year, major, usd_docs)
        if rec is None:
            dropped[err[0]] += err[1]
        else:
            recs.append(rec)
            status[rec["note"][-1] if rec["note"] else "plain"] += 1
    if recaliber:
        recs, renamed, suspects = apply_recaliber(recs)
    merged, conflicts, ngroups = merge_records(recs)
    print("raw 行数: %d" % len(rows))
    print("转换后保留: %d，丢弃: %d（%s）" % (len(recs), sum(dropped.values()), dict(dropped)))
    print("去重后行数: %d（合并 %d 组）" % (len(merged), len(recs) - len(merged)))
    lag = [c for c in conflicts if c[5] != c[6]]
    real = [c for c in conflicts if c[5] == c[6]]
    print("冲突(同键不同值): %d（跨文件滞后副本 %d / 同文件 %d）" % (len(conflicts), len(lag), len(real)))
    for c in real[:25]:
        print("  %s | %s vs %s | %s / %s | doc%s vs %s" % (c[0], c[1], c[2], c[3], c[4], c[5], c[6]))
    print("特征标记: %s" % dict(status))
    if recaliber:
        print("recaliber 改名: %s ; 疑值保护: %d 行" % (dict(renamed), suspects))
        uni = coverage_report(merged)
        leftover = leftover_classify(merged, uni)
        print("leftover 分类预估: DISCONTINUED %d / VARIANT %d / NOISE %d（共 %d 个历史独有指标）" % (
            sum(1 for r in leftover if r[1] == "DISCONTINUED"),
            sum(1 for r in leftover if r[1] == "VARIANT"),
            sum(1 for r in leftover if r[1] == "NOISE"), len(leftover)))
        for r in leftover:
            print("  %-24s | %-12s | n=%-4d | %-30s | %s" % (r[0], r[1], r[2], r[3], r[4]))


def rebuild(conn, csv_path=CSV_PATH, recaliber=False):
    rows, doc_year = fetch_all(conn)
    usd_docs = usd_doc_ids(rows)
    major = sheet_major(rows)
    recs, dropped = [], Counter()
    for r in rows:
        rec, err = transform(r, doc_year, major, usd_docs)
        if rec is None:
            dropped[err[0]] += err[1]
        else:
            recs.append(rec)
    if recaliber:
        recs, renamed, suspects = apply_recaliber(recs)
    merged, conflicts, ngroups = merge_records(recs)

    cur = conn.cursor()
    bak = BAK_RECALIBER if recaliber else BAK
    cur.execute("DROP TABLE IF EXISTS %s_bak_%s" % (DST, bak))
    cur.execute("CREATE TABLE %s_bak_%s LIKE %s" % (DST, bak, DST))
    cur.execute("INSERT INTO %s_bak_%s SELECT * FROM %s" % (DST, bak, DST))
    cur.execute("DROP TABLE IF EXISTS %s" % DST)
    cur.execute("CREATE TABLE %s LIKE %s" % (DST, SRC))
    sql = ("INSERT INTO %s (stat_doc_id, period, region, indicator_code, indicator_name, value, unit, growth_rate, sheet_name, source_type, confidence, generator_type) "
           "VALUES (%%s,%%s,%%s,%%s,%%s,%%s,%%s,%%s,%%s,%%s,%%s,%%s)" % DST)
    data = [(r["doc"], r["period"], r["region"], r["code"], r["indicator"], r["value"],
             r["unit"] or None, r["growth"], r["sheet"], r["src"], r["conf"], r["gen"]) for r in merged]
    cur.executemany(sql, data)
    conn.commit()
    cur.close()

    with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["stat_doc_id", "period", "region", "indicator_code", "indicator_name",
                    "value", "unit", "growth_rate", "sheet_name", "source_type", "confidence", "generator_type"])
        w.writerows(data)

    print("== 重建完成 ==")
    print("raw: %d -> 保留 %d -> 去重后 %d（合并 %d，冲突 %d）" % (len(rows), len(recs), len(merged), len(recs) - len(merged), len(conflicts)))
    print("丢弃: %s" % dict(dropped))
    if recaliber:
        print("recaliber 改名: %s ; 疑值保护: %d 行（2022年1-9月 用电 929362.78 未标单位）" % (dict(renamed), suspects))
    print("备份: %s_bak_%s ; CSV: %s" % (DST, bak, csv_path))
    lag = [c for c in conflicts if c[5] != c[6]]
    real = [c for c in conflicts if c[5] == c[6]]
    print("冲突: %d（跨文件滞后副本 %d / 同文件 %d）" % (len(conflicts), len(lag), len(real)))
    for c in real[:25]:
        print("  同文件冲突 %s | %s vs %s | %s / %s | doc%s vs %s" % (c[0], c[1], c[2], c[3], c[4], c[5], c[6]))
    if recaliber:
        uni = coverage_report(merged)
        leftover = leftover_classify(merged, uni)
        nd, nv, nn = write_leftover(leftover)
        print("leftover CSV: %s（DISCONTINUED %d / VARIANT %d / NOISE %d）" % (LEFT_CSV, nd, nv, nn))
    return merged


# ------------------------------------------------------------ recaliber ----
def recaliber_rename(rec):
    """名称变体归一 + 单位统一（--recaliber 核心变换：transform 后、merge 前逐条执行）。"""
    old = rec["indicator"]
    new = VARIANT_MAP.get(old)
    if new:
        rec["indicator"] = new
        rec["note"].append("recaliber:%s" % old)
        if old == "全市用电总量(万千瓦小时)投资" and rec["value"] is not None:
            rec["value"] = float(rec["value"]) / 10000.0
        if old in RENAME_UNIT:
            rec["unit"] = RENAME_UNIT[old]
    unit = UNIT_RULES.get(rec["indicator"])
    if unit:
        if rec["indicator"] == "规模工业利润总额" and rec["value"] is not None:
            rec["value"] = float(rec["value"]) / 10000.0
        if rec["indicator"] == "全市用电总量(亿千瓦小时)":
            # 2022年1-9月=929362.78 为官方单位错标疑值（实为万千瓦小时口径），值域保护：仅 ≤1000 才标 亿千瓦小时
            if rec["value"] is not None and float(rec["value"]) > 1000:
                rec["note"].append("recaliber-suspect-value")
                return rec
        rec["unit"] = unit
        rec["note"].append("recaliber-unit")
    return rec


def apply_recaliber(recs):
    renamed = Counter()
    suspects = 0
    for rec in recs:
        before = rec["indicator"]
        recaliber_rename(rec)
        if rec["indicator"] != before:
            renamed[before] += 1
        if "recaliber-suspect-value" in rec["note"]:
            suspects += 1
    return recs, renamed, suspects


def _leftover_row(merged, ind):
    rows = [m for m in merged if m["indicator"] == ind]
    years = sorted(set(m["period"][:4] for m in rows))
    return len(rows), ",".join(years)


def leftover_classify(merged, universe):
    """历史独有指标分类 DISCONTINUED/VARIANT/NOISE（只列不删）。
    仅收录「2025 年无任何发布」的历史独有指标；2025 有发布（含 1-2..1-8 月滞后发布，如 规模工业营业收入）
    或属于最新期指标全集的一律跳过，VARIANT_KEEP 陷阱指标除外（始终收录）。"""
    seen = {}
    has2025 = set(m["indicator"] for m in merged if m["period"].startswith("2025"))
    for m in merged:
        ind = m["indicator"]
        if ind not in VARIANT_KEEP and (ind in universe or ind in has2025):
            continue
        if ind in seen:
            continue
        if ind in VARIANT_KEEP:
            seen[ind] = ("VARIANT", VARIANT_KEEP[ind])
        elif ind.startswith(NOISE_PREFIX) or ind in NOISE_EXACT:
            seen[ind] = ("NOISE", "结构化单元格前缀保留/区域行误解析残留")
        else:
            seen[ind] = ("DISCONTINUED", "2025 年不再发布（只列不删）")
    rows = []
    for ind in sorted(seen):
        n, years = _leftover_row(merged, ind)
        rows.append([ind, seen[ind][0], n, years, seen[ind][1]])
    return rows


def write_leftover(leftover, path=LEFT_CSV):
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["indicator", "status", "rows", "years", "note"])
        w.writerows(leftover)
    return (sum(1 for r in leftover if r[1] == "DISCONTINUED"),
            sum(1 for r in leftover if r[1] == "VARIANT"),
            sum(1 for r in leftover if r[1] == "NOISE"))


def coverage_report(merged):
    """覆盖率曲线：最新期(2025年1-9月)指标全集在各年的存在率（只读）。"""
    uni = set(m["indicator"] for m in merged if m["period"] == "2025年1-9月")
    by_year = defaultdict(set)
    for m in merged:
        yr = m["period"][:4]
        if yr.isdigit():
            by_year[yr].add(m["indicator"])
    print()
    print("== recaliber 覆盖率曲线（最新期 2025年1-9月 指标全集 n=%d；recaliber 前 225，地区生产总值(GDP) 并入后归一） ==" % len(uni))
    for y in ("2025", "2024", "2023", "2022", "2021", "2020"):
        hit = len(uni & by_year.get(y, set()))
        print("  %s: %3d / %d = %6.2f%%" % (y, hit, len(uni), 100.0 * hit / len(uni)))
    return uni


def recaliber_anchors(conn):
    cur = conn.cursor()
    def one(sql):
        cur.execute(sql)
        return cur.fetchall()
    print()
    print("== recaliber 回归锚点 ==")
    for label, sql in [
        ("各项存款 跨期连续（2024年1-12月/2025年1-2月/2025年1-9月）",
         "SELECT period, indicator_name, value FROM %s WHERE indicator_name='各项存款' AND region='全市' AND period IN ('2024年1-12月','2025年1-2月','2025年1-9月') ORDER BY period" % DST),
        ("各项贷款 跨期连续",
         "SELECT period, indicator_name, value FROM %s WHERE indicator_name='各项贷款' AND region='全市' AND period IN ('2024年1-12月','2025年1-2月','2025年1-9月') ORDER BY period" % DST),
        ("地区生产总值 2018-2025 全期序列（全市）",
         "SELECT COUNT(*), MIN(period), MAX(period) FROM %s WHERE indicator_name='地区生产总值' AND region='全市'" % DST),
        ("公路(万吨) 2024年1-9月/2025年1-9月（跨期改名口径）",
         "SELECT period, indicator_name, value, growth_rate FROM %s WHERE indicator_name='公路(万吨)' AND region='全市' AND period IN ('2024年1-9月','2025年1-9月') ORDER BY period" % DST),
        ("规模工业利润总额 单位（万元→亿元）",
         "SELECT period, value, unit FROM %s WHERE indicator_name='规模工业利润总额' AND region='全市' AND period IN ('2024年1-12月','2025年1-8月') ORDER BY period" % DST),
        ("营业收入 vs 规模工业营业收入（不串线：2025年1-8月/1-9月）",
         "SELECT period, indicator_name, value FROM %s WHERE indicator_name IN ('营业收入','规模工业营业收入') AND region='全市' AND period IN ('2025年1-8月','2025年1-9月') ORDER BY indicator_name, period" % DST),
        ("利润总额 vs 规模工业利润总额（不串线：2024年1-12月）",
         "SELECT period, indicator_name, value, unit FROM %s WHERE indicator_name IN ('利润总额','规模工业利润总额') AND region='全市' AND period='2024年1-12月' ORDER BY indicator_name" % DST),
        ("全表重复检查 (period,region,indicator_name)",
         "SELECT COUNT(*) total, COUNT(DISTINCT period, region, indicator_name) uniq FROM %s" % DST),
    ]:
        print("-- %s" % label)
        for r in one(sql):
            print("   " + " | ".join("" if x is None else str(x) for x in r))
    print("-- 备份表 %s_bak_%s" % (DST, BAK_RECALIBER))
    cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=%s", ("%s_bak_%s" % (DST, BAK_RECALIBER),))
    for r in cur.fetchall():
        print("   " + " | ".join("" if x is None else str(x) for x in r))
    cur.close()


def anchors(conn):
    cur = conn.cursor()
    def one(sql):
        cur.execute(sql)
        return cur.fetchall()
    print()
    print("== 回归锚点 ==")
    for label, sql in [
        ("2021年全年 地区生产总值 全市", "SELECT period, indicator_name, region, value, growth_rate FROM %s WHERE period='2021年1-12月' AND indicator_name='地区生产总值' AND region='全市'" % DST),
        ("2025年1-9月 三次产业 全市", "SELECT indicator_name, value, growth_rate FROM %s WHERE period='2025年1-9月' AND indicator_name IN ('第一产业增加值','第二产业增加值','第三产业增加值') AND region='全市' ORDER BY indicator_name" % DST),
        ("固定资产投资 全市 2018年1-8月 / 2025年1-9月", "SELECT period, value, growth_rate FROM %s WHERE indicator_name='固定资产投资' AND region='全市' AND period IN ('2018年1-8月','2025年1-9月') ORDER BY period" % DST),
        ("各项存款/各项贷款 2025年1-9月 全市", "SELECT indicator_name, value, growth_rate FROM %s WHERE period='2025年1-9月' AND indicator_name IN ('各项存款','各项贷款') AND region='全市' ORDER BY indicator_name" % DST),
        ("各项存款 2025年1-9月 分县行数", "SELECT COUNT(*), COUNT(DISTINCT region) FROM %s WHERE period='2025年1-9月' AND indicator_name='各项存款'" % DST),
        ("分市州GDP 长沙 2022年1-12月", "SELECT period, indicator_name, region, value FROM %s WHERE indicator_name='分市州GDP' AND region='长沙市' AND period='2022年1-12月'" % DST),
        ("邵东市 地区生产总值 2023年1-12月", "SELECT period, region, value, growth_rate FROM %s WHERE period='2023年1-12月' AND indicator_name='地区生产总值' AND region='邵东市'" % DST),
        ("社会消费品零售总额 全市 2025年1-9月", "SELECT period, value, growth_rate FROM %s WHERE indicator_name='社会消费品零售总额' AND region='全市' AND period='2025年1-9月'" % DST),
        ("批发和零售业 增加值 vs 零售额 消歧", "SELECT indicator_name, value, growth_rate FROM %s WHERE period='2025年1-9月' AND indicator_name IN ('批发和零售业','批发和零售业零售额') AND region='全市' ORDER BY indicator_name" % DST),
        ("全表 期间格式分布", "SELECT CASE WHEN period REGEXP '^[0-9]{4}年[0-9]{1,2}-[0-9]{1,2}月$' THEN 'STD' ELSE 'OTHER' END cls, COUNT(*) FROM %s GROUP BY cls" % DST),
        ("全表 年度/指标/区县 统计", "SELECT LEFT(period,4) yr, COUNT(*), COUNT(DISTINCT indicator_name), COUNT(DISTINCT region) FROM %s WHERE period REGEXP '^[0-9]{4}年' GROUP BY yr ORDER BY yr" % DST),
    ]:
        print("-- %s" % label)
        for r in one(sql):
            print("   " + " | ".join("" if x is None else str(x) for x in r))
    cur.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--analyze", action="store_true")
    ap.add_argument("--rebuild", action="store_true")
    ap.add_argument("--recaliber", action="store_true",
                    help="数据重处理：名称变体归一+单位统一+停发/变体清单；需搭配 --analyze（只读）/ --rebuild（重建+备份 20260820a）")
    args = ap.parse_args()
    conn = pymysql.connect(**DB, autocommit=False)
    if args.analyze:
        analyze(conn, recaliber=args.recaliber)
    if args.rebuild:
        rebuild(conn, recaliber=args.recaliber)
        anchors(conn)
        if args.recaliber:
            recaliber_anchors(conn)
    if not args.analyze and not args.rebuild:
        ap.print_help()
    conn.close()


if __name__ == "__main__":
    main()