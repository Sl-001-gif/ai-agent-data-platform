# -*- coding: utf-8 -*-
"""统计月报指标分类字典生成器（含 --tree 挂树模式，阶段1）。

无参数（原功能，行为不变）：
    输入：tools/_indicator_dump.tsv（mysql 导出 TSV，字段 indicator_code/names/sheets/units/has_v/has_g/cnt）
    输出：docs/sql/stat-indicator-dict.sql（建表 + 339 个 indicator_code 全量映射 + 单位括号修正）
    并追加「阶段1 挂树回填段」（ALTER stat_indicator_dict.category_id + 幂等回填 UPDATE）。

--tree（阶段1 新增）：
    叶子：stat_monthly 最新期（2025年1-9月）224 个 DISTINCT indicator_name（含代表 sheet）
    归类：10 大类 × 34 中类（按 sheet/名称规则，2025 新名校准；未命中兜底 综合对比/主要经济指标）
    输出：docs/sql/stat-indicator-category.sql（建表 + 幂等 upsert：大类→中类→叶子 三级节点）
    同步：docs/sql/stat-indicator-dict.sql 追加挂树回填段（与默认模式同款，幂等可重跑）
    报告：docs/data/stat-tree-leftover.csv（未挂链叶子 + 未匹配 dict 行）+ 控制台分类/回填统计
"""
import os
import sys
import csv

try:
    import pymysql
except Exception:
    pymysql = None

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "tools", "_indicator_dump.tsv")
OUT = os.path.join(ROOT, "docs", "sql", "stat-indicator-dict.sql")
TREE_OUT = os.path.join(ROOT, "docs", "sql", "stat-indicator-category.sql")
LEFT_CSV = os.path.join(ROOT, "docs", "data", "stat-tree-leftover.csv")

DB = dict(host="localhost", port=3306, user="root", password="Admin@123456",
          database="ai_agent_data", charset="utf8mb4")
LATEST_PERIOD = "2025年1-9月"
DICT_TABLE = "stat_indicator_dict"
TREE_TABLE = "stat_indicator_category"

# ============================================================
# 一、原字典生成（默认模式，逻辑保持不变）
# ============================================================

SHEET_CAT = {
    "核算5": "经济核算", "23核算处1": "经济核算", "24核算处2": "经济核算",
    "25核算处3": "经济核算", "26核算处": "经济核算",
    "工业7": "工业经济", "工业8": "工业经济", "30工业处1": "工业经济",
    "31工业处3": "工业经济", "工业处2": "工业经济",
    "投资11": "固定资产投资", "投资12": "固定资产投资",
    "33投资处1": "固定资产投资", "34投资处2": "固定资产投资", "35投资处4": "固定资产投资",
    "36投资处5": "固定资产投资", "37投资处6": "固定资产投资", "38投资处7": "固定资产投资",
    "贸易13": "消费市场", "42贸外处1": "消费市场",
    "43贸外处2": "外贸外资", "45贸外处4": "外贸外资", "46贸外处5": "外贸外资",
    "财政19": "财政收支", "财政20": "财政收支", "54综研室3": "财政收支", "55综研室4": "财政收支",
    "金融21": "金融运行", "52综研室1": "金融运行", "53综研室2": "金融运行",
    "城乡收支22": "居民收支", "全体收入49": "居民收支", "城镇收入50": "居民收支", "农村收入51": "居民收支",
    "交邮10": "交通运输",
}


def first_name(code, names):
    return names.split("|")[0]


def has(keys, text):
    return any(k in text for k in keys)


def classify(code, names, sheets):
    n = first_name(code, names)
    s0 = sheets.split("|")[0]
    if s0 in SHEET_CAT:
        cat = SHEET_CAT[s0]
    elif s0 == "外贸和房地产15":
        if has(["价格指数"], n) or has(["商品房", "房地产"], n):
            cat = "价格与房地产"
        else:
            cat = "外贸外资"
    elif s0.startswith("分县（市、区）"):
        if has(["规模工业"], n) or (has(["工业"], n) and not has(["投资"], n)):
            cat = "工业经济"
        elif has(["外资", "内资", "进出口", "出口", "外商"], n):
            cat = "外贸外资"
        elif has(["社会消费品", "零售"], n):
            cat = "消费市场"
        elif has(["可支配收入"], n):
            cat = "居民收支"
        elif has(["财政", "预算收入"], n):
            cat = "财政收支"
        elif has(["GDP", "生产总值", "增加值"], n):
            cat = "经济核算"
        elif has(["固定", "投资"], n):
            cat = "固定资产投资"
        else:
            if has(["规模工业", "工业"], s0):
                cat = "工业经济"
            elif has(["GDP", "生产总值"], s0):
                cat = "经济核算"
            elif has(["投资"], s0):
                cat = "固定资产投资"
            elif has(["零售"], s0):
                cat = "消费市场"
            elif has(["外资", "内资", "进出口"], s0):
                cat = "外贸外资"
            elif has(["收入"], s0):
                cat = "居民收支"
            elif has(["财政"], s0):
                cat = "财政收支"
            else:
                cat = "综合对比"
    elif "主要经济指标完成情况" in s0:
        if has(["GDP", "生产总值", "增加值"], n) or "核算" in n:
            cat = "经济核算"
        elif has(["规模工业", "工业", "亏损", "营业收入", "利润", "利税", "产销", "两金", "从业", "产品销售收入"], n):
            cat = "工业经济"
        elif has(["固定", "投资", "施工项目", "新开工", "计划总投资", "项目"], n):
            cat = "固定资产投资"
        elif has(["社会消费品", "零售", "粮油", "食品类", "书报", "类"], n):
            cat = "消费市场"
        elif has(["进出口", "出口", "外资", "外商"], n):
            cat = "外贸外资"
        elif has(["财政", "预算收入", "预算支出", "税收", "税", "上划", "非税"], n):
            cat = "财政收支"
        elif has(["可支配收入", "人均", "收入", "乡村", "城镇", "消费支出"], n):
            cat = "居民收支"
        elif has(["存款", "贷款", "银行"], n):
            cat = "金融运行"
        elif has(["用电量", "客运", "货运", "交通运输"], n):
            cat = "交通运输"
        else:
            cat = "综合对比"
    else:
        cat = "综合对比"

    if cat == "经济核算":
        sub = "产业增加值" if has(["第一产业", "第二产业", "第三产业"], n) else ("GDP" if has(["生产总值", "GDP"], n) else ("分县GDP" if has(["分县"], n) else "增加值"))
    elif cat == "工业经济":
        if has(["规模工业增加值"], n):
            sub = "规模工业增加值"
        elif has(["排名"], n):
            sub = "分县排名"
        elif has(["增加值"], n):
            sub = "行业增加值"
        elif has(["营业收入", "利润", "利税", "亏损", "产销", "两金", "成本", "从业"], n):
            sub = "经济效益"
        else:
            sub = "工业经济"
    elif cat == "固定资产投资":
        if has(["排名"], n):
            sub = "分县投资"
        elif has(["第一产业", "第二产业", "第三产业", "工业", "高技术", "基础设施", "民生", "生态", "房地产", "制造业", "采矿业", "电力", "投资"], n) and "投资" in n:
            sub = "产业投资"
        else:
            sub = "固定资产投资"
    elif cat == "消费市场":
        sub = "社会消费品零售总额" if has(["社会消费品零售总额"], n) else ("限额以上零售" if has(["限额"], n) else "消费市场")
    elif cat == "外贸外资":
        if has(["外商直接投资", "实际利用外资", "境外资金"], n):
            sub = "外商直接投资"
        elif has(["进出口"], n):
            sub = "进出口"
        elif has(["出口"], n):
            sub = "出口"
        elif has(["内资"], n):
            sub = "实际利用内资"
        else:
            sub = "外贸外资"
    elif cat == "财政收支":
        if has(["支出"], n):
            sub = "财政支出"
        elif has(["税收", "增值税", "所得税", "印花税", "房产税", "车船税", "耕地占用税", "土地增值税", "土地使用税", "资源税", "城建税", "营业税", "车辆购置税"], n):
            sub = "税收收入"
        else:
            sub = "财政收入"
    elif cat == "金融运行":
        sub = "存款" if has(["存款"], n) else "贷款"
    elif cat == "居民收支":
        if has(["可支配收入"], n):
            sub = "城镇居民收入" if has(["城镇"], n) else ("农村居民收入" if has(["农村", "农民"], n) else "全体居民收入")
        elif has(["消费支出"], n):
            sub = "消费支出"
        else:
            sub = "收入结构"
    elif cat == "交通运输":
        sub = "用电量" if has(["用电"], n) else ("客运量" if has(["客运"], n) else ("货运量" if has(["货运"], n) else "交通运输"))
    elif cat == "价格与房地产":
        sub = "价格指数" if has(["价格指数"], n) else "房地产"
    else:
        sub = "主要经济指标"
    return cat, sub


def unit_fix(code, names, units, cat, sub):
    n = first_name(code, names)
    if "排名" in n or n.endswith("排名"):
        return "名次"
    if cat == "外贸外资":
        if has(["外商直接投资", "外资", "境外资金"], n):
            return "万美元"
        if has(["进出口", "出口", "进口"], n):
            return "万美元"
    if cat == "金融运行":
        return "亿元"
    if cat == "居民收支":
        return "元"
    if cat == "财政收支":
        return "万元"
    if cat == "消费市场" and "社会消费品零售总额" in n:
        return "万元"
    if "用电量" in n:
        return "万千瓦小时" if "万千瓦" in n else "亿千瓦小时"
    bad = ["名", "市、区", "不含跨区项目", "排名", "None", "NULL"]
    cleaned = [u for u in units.split("|") if u not in bad]
    return "|".join(sorted(set(cleaned))) if cleaned else None


# ============================================================
# 二、阶段1 树定义与名称匹配
# ============================================================

TREE_COLORS = {
    "经济核算": "#2f6fed", "工业经济": "#e8743b", "固定资产投资": "#7b5cd6",
    "消费市场": "#d65c8b", "外贸外资": "#2a9d8f", "财政收支": "#e9a23b",
    "金融运行": "#3b82c4", "居民收支": "#6bbf59", "交通运输": "#3f8fa3",
    "综合对比": "#8d99ae",
}
# 10 大类 × 34 中类（slug 拼入 code 保证全表唯一；叶子挂在二级中类下，level=3）
TREE_SUBS = {
    "经济核算": [("gdp", "GDP"), ("cyzjz", "产业增加值"), ("hyzjz", "行业增加值"), ("nyzcz", "农业总产值")],
    "工业经济": [("gmzjz", "规模工业增加值"), ("gmcz", "规模工业产值"), ("xjy", "经济效益"), ("hyzjz", "行业增加值")],
    "固定资产投资": [("tzze", "固定资产投资总额"), ("cytz", "产业投资"), ("jg", "投资结构与项目")],
    "消费市场": [("shzp", "社会消费品零售总额"), ("xes", "限额以上零售"), ("fqy", "分区域零售"), ("fpl", "分品类零售"), ("fhy", "分行业零售")],
    "外贸外资": [("jck", "进出口"), ("wszj", "外商直接投资"), ("fqy", "分区域外贸"), ("fdc", "房地产")],
    "财政收支": [("sr", "财政收入"), ("ss", "税收收入"), ("zc", "财政支出")],
    "金融运行": [("ck", "存款"), ("dk", "贷款")],
    "居民收支": [("qt", "全体居民收入"), ("cz", "城镇居民收入"), ("nc", "农村居民收入"), ("jg", "收入结构"), ("xf", "消费支出")],
    "交通运输": [("ydl", "用电量"), ("ky", "客运"), ("hy", "货运")],
    "综合对比": [("jgzs", "价格指数")],
}


def norm(s):
    """名称归一：全角括号/冒号转半角、去空格（与 SQL 侧 REPLACE 链保持一致）。"""
    return s.replace("（", "(").replace("）", ")").replace("：", ":").replace(" ", "")


# 阶段0 已数值核验的名称映射（历史期名 -> 2025 最新期名）
VARIANT_MAP = {
    "金融机构各项存款": "各项存款",
    "金融机构各项贷款": "各项贷款",
    "生产总值(GDP)": "地区生产总值",
    "地区生产总值(GDP)": "地区生产总值",
    "实际利用境外资金": "外商直接投资",
    "卫生健康支出": "卫生健康",
    "全市用电总量(万千瓦小时)投资": "全市用电总量(亿千瓦小时)",
    "全市用电总量(亿千瓦小时)投资": "全市用电总量(亿千瓦小时)",
    "商品房屋销售额投资": "商品房屋销售额",
    "商品房施工面积投资": "商品房施工面积",
    "商品房竣工面积投资": "商品房竣工面积",
    "商品房销售面积投资": "商品房销售面积",
    "其中、新开工项目个数(个)投资": "本年新开工",
    "新开工项目个数(个)投资": "本年新开工",
    "施工项目个数(个)投资": "施工项目个数",
    "农林牧渔业": "农、林、牧、渔业",
    "全社会客运量(万人)": "公路(万人)",
    "客运周转量(万人公里)": "公路(万人公里)",
    "全社会货运量(万吨)": "公路(万吨)",
    "货运周转量(万吨公里)": "公路(万吨公里)",
}
# 阶段1 补验别名（dict 名(归一化后) -> 2025 叶子名）
ALIAS_MATCH_EXTRA = {
    "两金占用(应收账款和产成品存货)": "两金占用(应收帐款和产成品存货)",   # 账/帐 异体字，同指标
    "从业人员平均人数(万人)": "从业人员平均人数(人)",                     # 单位变体，同系列
    "全市用电总量(万千瓦小时)": "全市用电总量(亿千瓦小时)",                # 单位 ÷10000，同系列
    "客运量(万人)": "公路(万人)",                                        # 2025 新名（交邮10 同 sheet）
    "旅客周转量(万人公里)": "公路(万人公里)",
    "书报杂志类": "书报杂志类零售额",
    "汽车类": "汽车类零售额",
    "烟酒类": "烟酒类零售额",
    "石油制品类": "石油制品类零售额",
    "粮油、食品类": "粮油、食品类零售额",
    "服装、鞋帽、针纺织品类": "服装、鞋帽、针纺织品类零售额",
    "饮料类": "饮料类零售额",
    "规模工业利润总额": "利润总额",        # 2025 工业7 利润总额=规模工业利润总额滞后副本（阶段0 判定）
    "规模工业营业收入": "营业收入",        # 2025 工业7 营业收入=规模工业营业收入新名
    "城区": "城区零售额",                  # 其中：城区 -> 城区零售额（raw 同值 240.8197 核验）
    "新开工项目个数(个)": "本年新开工",
}
# 挂树回填段静态别名清单（dict 名(归一化) -> 叶子名）
ALIAS_UPDATE_PAIRS = list(VARIANT_MAP.items()) + list(ALIAS_MATCH_EXTRA.items()) + [
    # 单位括号剥离（同指标）
    ("亏损面(%)", "亏损面"),
    ("居民消费价格指数(%)", "居民消费价格指数"),
    ("施工项目个数(个)", "施工项目个数"),
    ("地区生产总值(万元)", "地区生产总值"),
    ("社会消费品零售总额(亿元)", "社会消费品零售总额"),
    ("外商直接投资(万美元)", "外商直接投资"),
    ("实际利用境外资金(万美元)", "外商直接投资"),
    # 其中：前缀 + 别名
    ("其中:新开工项目个数(个)", "本年新开工"),
    ("其中、新开工项目个数(个)", "本年新开工"),
    ("其中:城区", "城区零售额"),
]
UNIT_STRIP = ["(%)", "(人)", "(万美元)", "(亿元)", "(万元)", "(个)"]
PREFIXES = ("其中:", "其中、")


def classify_leaf(name, sheet):
    """224 叶子 -> (大类, 中类)。规则按 sheet 分派 + 2025 新名校准。"""
    if sheet == "核算5" or sheet.startswith("23核算处") or sheet.startswith("24核算处") \
            or sheet.startswith("25核算处") or sheet.startswith("26核算处"):
        if name in ("地区生产总值", "地区生产总值排名"):
            return "经济核算", "GDP"
        if name in ("第一产业增加值", "第二产业增加值", "第三产业增加值"):
            return "经济核算", "产业增加值"
        if name == "农业总产值(现价)":
            return "经济核算", "农业总产值"
        return "经济核算", "行业增加值"
    if sheet == "工业8":
        return "工业经济", "行业增加值"
    if sheet == "工业7":
        if name in ("规模工业增加值", "规模工业增加值排名"):
            return "工业经济", "规模工业增加值"
        if name in ("规模工业总产值", "规模以下工业总产值", "工业产品销售产值(现价)"):
            return "工业经济", "规模工业产值"
        return "工业经济", "经济效益"
    if sheet == "30工业处1":
        return "工业经济", "规模工业增加值"
    if sheet in ("投资11", "投资12", "33投资处1", "35投资处4", "36投资处5", "37投资处6", "38投资处7"):
        if name in ("固定资产投资", "固定资产投资排名"):
            return "固定资产投资", "固定资产投资总额"
        if name in ("中央项目", "地方项目", "国有投资", "民间投资", "非国有投资",
                    "建筑安装工程", "设备工器具购置", "其他费用",
                    "施工项目个数", "本年投产项目个数", "本年新开工"):
            return "固定资产投资", "投资结构与项目"
        return "固定资产投资", "产业投资"
    if sheet in ("贸易13", "42贸外处1"):
        if name in ("社会消费品零售总额", "社会消费品零售总额排名"):
            return "消费市场", "社会消费品零售总额"
        if name in ("限额以上零售额", "限额以上法人单位零售额"):
            return "消费市场", "限额以上零售"
        if name in ("城镇零售额", "城区零售额", "乡村零售额"):
            return "消费市场", "分区域零售"
        if name in ("批发和零售业零售额", "住宿和餐饮业零售额"):
            return "消费市场", "分行业零售"
        return "消费市场", "分品类零售"
    if sheet in ("45贸外处4", "46贸外处5"):
        return "外贸外资", "分区域外贸"
    if sheet in ("外贸和房地产15", "房地产15(派生)"):
        if name in ("进出口", "出口", "进口"):
            return "外贸外资", "进出口"
        if name == "外商直接投资":
            return "外贸外资", "外商直接投资"
        if name in ("住宅", "住宅销售面积", "住宅销售额",
                    "商品房销售面积", "商品房施工面积", "商品房竣工面积", "商品房屋销售额"):
            return "外贸外资", "房地产"
        if name == "居民消费价格指数":
            return "综合对比", "价格指数"
        return "综合对比", "主要经济指标"
    if sheet in ("财政19", "财政20", "54综研室3", "55综研室4"):
        if name in ("一般公共预算收入", "一般公共预算收入排名", "税收收入", "非税收入", "专项收入",
                    "罚没收入", "行政性收费", "国有资源(资产)有偿使用收入", "政府住房基金收入"):
            return "财政收支", "财政收入"
        if name in ("个人所得税", "企业所得税", "印花税", "土地增值税", "城市维护建设税",
                    "城镇土地使用税", "契税", "房产税", "耕地占用税", "资源税", "车船税", "环境保护税"):
            return "财政收支", "税收收入"
        return "财政收支", "财政支出"
    if sheet in ("金融21", "52综研室1", "53综研室2"):
        return "金融运行", "存款" if "存款" in name else "贷款"
    if sheet in ("城乡收支22", "全体收入49", "城镇收入50", "农村收入51"):
        if "可支配收入" in name:
            if "城镇" in name:
                return "居民收支", "城镇居民收入"
            if "农村" in name or "农民" in name:
                return "居民收支", "农村居民收入"
            return "居民收支", "全体居民收入"
        if "消费支出" in name:
            return "居民收支", "消费支出"
        return "居民收支", "收入结构"
    if sheet == "交邮10":
        if "用电" in name:
            return "交通运输", "用电量"
        if "(万人)" in name or "(万人公里)" in name:
            return "交通运输", "客运"
        return "交通运输", "货运"
    if sheet == "43贸外处2":
        if "外商直接投资" in name or "实际利用外资" in name:
            return "外贸外资", "外商直接投资"
        return "综合对比", "主要经济指标"
    return "综合对比", "主要经济指标"


def _match_name(n, category, leaf_set):
    if category == "固定资产投资" and n + "投资" in leaf_set:
        return n + "投资"
    if n in leaf_set:
        return n
    for pre in PREFIXES:
        if n.startswith(pre):
            inner = n[len(pre):]
            if inner in leaf_set:
                return inner
            if inner in ALIAS_MATCH_EXTRA and ALIAS_MATCH_EXTRA[inner] in leaf_set:
                return ALIAS_MATCH_EXTRA[inner]
    for u in UNIT_STRIP:
        if n.endswith(u):
            inner = n[:-len(u)]
            if inner in leaf_set:
                return inner
            if inner in ALIAS_MATCH_EXTRA and ALIAS_MATCH_EXTRA[inner] in leaf_set:
                return ALIAS_MATCH_EXTRA[inner]
            if inner in VARIANT_MAP and VARIANT_MAP[inner] in leaf_set:
                return VARIANT_MAP[inner]
    if n in VARIANT_MAP and VARIANT_MAP[n] in leaf_set:
        return VARIANT_MAP[n]
    if n in ALIAS_MATCH_EXTRA and ALIAS_MATCH_EXTRA[n] in leaf_set:
        return ALIAS_MATCH_EXTRA[n]
    return None


def match_leaf(code, name, category, leaf_set):
    """dict 行 -> 叶子名。规则与 docs/sql/stat-indicator-dict.sql 挂树回填段等价。"""
    n = norm(name)
    if code.endswith("排名"):
        cand = n if n.endswith("排名") else n + "排名"
        return cand if cand in leaf_set else None
    return _match_name(n, category, leaf_set)


# ============================================================
# 三、SQL 生成
# ============================================================

def _q(s):
    return s.replace("'", "''")


def gen_tree_sql(leaves, cat_of):
    """生成 stat-indicator-category.sql（建表 + 幂等 upsert，三级节点）。"""
    subs_by_cat = TREE_SUBS
    # 一级 code
    cat_code = {}
    for i, cat in enumerate(TREE_SUBS, start=1):
        cat_code[cat] = "c%02d" % i
    # 二级 code
    l2_code = {}
    for cat, subs in subs_by_cat.items():
        for slug, sname in subs:
            l2_code[(cat, sname)] = "%s_%s" % (cat_code[cat], slug)
    # 三级 code（按名称排序，稳定）
    leaf_code = {}
    for i, name in enumerate(leaves, start=1):
        leaf_code[name] = "ind_%04d" % i

    rows1 = []
    for cat, subs in subs_by_cat.items():
        rows1.append("(NULL, '%s', '%s', 1, %d, '%s', 1)" % (
            _q(cat), cat_code[cat], list(TREE_SUBS).index(cat) + 1, TREE_COLORS[cat]))
    rows2 = []
    for cat, subs in subs_by_cat.items():
        for idx, (slug, sname) in enumerate(subs, start=1):
            rows2.append("SELECT '%s' AS code, '%s' AS name, %d AS sort, '%s' AS pcode" % (
                l2_code[(cat, sname)], _q(sname), idx, cat_code[cat]))
    rows3 = []
    for i, name in enumerate(leaves, start=1):
        cat, sub = cat_of[name]
        rows3.append("SELECT '%s' AS code, '%s' AS name, %d AS sort, '%s' AS pcode" % (
            leaf_code[name], _q(name), i, l2_code[(cat, sub)]))

    L = []
    L.append("-- 统计月报指标分类树（阶段1：10 大类 × 34 中类 × %d 叶子）" % len(leaves))
    L.append("-- 叶子全集 = stat_monthly.%s 的 %d 个 DISTINCT indicator_name；生成: tools/scraper/build_stat_category.py --tree" % (LATEST_PERIOD, len(leaves)))
    L.append("-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT ... ON DUPLICATE KEY UPDATE，连跑两次结果一致")
    L.append("CREATE TABLE IF NOT EXISTS %s (" % TREE_TABLE)
    L.append("    id BIGINT AUTO_INCREMENT PRIMARY KEY,")
    L.append("    parent_id BIGINT NULL COMMENT '父节点ID（大类=NULL，中类=大类id，叶子=中类id）',")
    L.append("    name VARCHAR(300) NOT NULL COMMENT '节点名称（大类/中类/指标名）',")
    L.append("    code VARCHAR(64) NOT NULL COMMENT '稳定编码（uk_code，可重跑）',")
    L.append("    level TINYINT NOT NULL COMMENT '1=大类 2=中类 3=叶子指标',")
    L.append("    sort INT DEFAULT 0 COMMENT '同级排序',")
    L.append("    color VARCHAR(20) NULL COMMENT '大类色值',")
    L.append("    status TINYINT DEFAULT 1,")
    L.append("    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,")
    L.append("    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,")
    L.append("    UNIQUE KEY uk_code (code),")
    L.append("    KEY idx_parent_level (parent_id, level)")
    L.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计月报指标分类树（大类×中类×叶子）';")
    L.append("")
    L.append("-- 一级：大类（10）")
    L.append("INSERT INTO %s (parent_id, name, code, level, sort, color, status) VALUES" % TREE_TABLE)
    L.append(",\n".join(rows1))
    L.append("ON DUPLICATE KEY UPDATE name=VALUES(name), level=1, sort=VALUES(sort), color=VALUES(color), status=1;")
    L.append("")
    L.append("-- 二级：中类（34，parent 按 code 关联大类）")
    L.append("INSERT INTO %s (parent_id, name, code, level, sort, color, status) " % TREE_TABLE)
    L.append("SELECT p.id, v.name, v.code, 2, v.sort, NULL, 1")
    L.append("FROM (%s) v" % "\nUNION ALL\n".join(rows2))
    L.append("JOIN %s p ON p.code = v.pcode" % TREE_TABLE)
    L.append("ON DUPLICATE KEY UPDATE name=VALUES(name), parent_id=VALUES(parent_id), level=2, sort=VALUES(sort), status=1;")
    L.append("")
    L.append("-- 三级：叶子指标（%d，parent 按 code 关联中类）" % len(leaves))
    L.append("INSERT INTO %s (parent_id, name, code, level, sort, color, status) " % TREE_TABLE)
    L.append("SELECT p.id, v.name, v.code, 3, v.sort, NULL, 1")
    L.append("FROM (%s) v" % "\nUNION ALL\n".join(rows3))
    L.append("JOIN %s p ON p.code = v.pcode" % TREE_TABLE)
    L.append("ON DUPLICATE KEY UPDATE name=VALUES(name), parent_id=VALUES(parent_id), level=3, sort=VALUES(sort), status=1;")
    L.append("")
    return "\n".join(L) + "\n"


def gen_backfill_sql():
    """dict.sql 挂树回填段（ALTER category_id + 幂等回填 UPDATE，可重跑结果一致）。"""
    n4 = "REPLACE(REPLACE(REPLACE(REPLACE(d.indicator_name, '（', '('), '）', ')'), '：', ':'), ' ', '')"
    n3 = "REPLACE(REPLACE(REPLACE(d.indicator_name, '（', '('), '）', ')'), ' ', '')"
    L = []
    L.append("")
    L.append("-- ===== 阶段1 挂树回填（stat_indicator_category.category_id，幂等可重跑） =====")
    L.append("-- 前置：先执行 docs/sql/stat-indicator-category.sql 建树；本段可重复执行，结果一致")
    L.append("-- category_id 匹配规则与 build_stat_category.py --tree 等价：精确名 -> 投资类行业+投资 -> 其中：前缀 -> 别名(VARIANT_MAP/阶段0核验) -> 排名专链")
    L.append("SET @__cc := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='%s' AND COLUMN_NAME='category_id');" % DICT_TABLE)
    L.append("SET @__cc_sql := IF(@__cc = 0, 'ALTER TABLE %s ADD COLUMN category_id BIGINT NULL COMMENT ''分类树叶子ID(stat_indicator_category.level=3)'' AFTER sheet_name', 'SELECT 1');" % DICT_TABLE)
    L.append("PREPARE __cc_stmt FROM @__cc_sql;")
    L.append("EXECUTE __cc_stmt;")
    L.append("DEALLOCATE PREPARE __cc_stmt;")
    L.append("")
    L.append("-- 1) 精确名回填（名称归一后与叶子同名；固定资产投资类行业名+投资 有叶子的先跳过）")
    L.append("UPDATE %s d JOIN %s t ON t.level = 3 AND t.name = %s" % (DICT_TABLE, TREE_TABLE, n4))
    L.append("SET d.category_id = t.id")
    L.append("WHERE NOT (d.category = '固定资产投资' AND EXISTS (")
    L.append("    SELECT 1 FROM %s t2 WHERE t2.level = 3 AND t2.name = CONCAT(%s, '投资')));" % (TREE_TABLE, n3))
    L.append("")
    L.append("-- 2) 固定资产投资类：行业短名 + 投资 归位（制造业->制造业投资、采矿业->采矿业投资、租赁和商务服务业->...投资 等）")
    L.append("UPDATE %s d JOIN %s t ON t.level = 3 AND t.name = CONCAT(%s, '投资')" % (DICT_TABLE, TREE_TABLE, n3))
    L.append("SET d.category_id = t.id WHERE d.category = '固定资产投资';")
    L.append("")
    L.append("-- 3) 其中：/其中:/其中、 前缀剥离回填")
    L.append("UPDATE %s d JOIN %s t ON t.level = 3 AND t.name = REPLACE(REPLACE(REPLACE(REPLACE(d.indicator_name, '其中：', ''), '其中:', ''), '其中、', ''), ' ', '')" % (DICT_TABLE, TREE_TABLE))
    L.append("SET d.category_id = t.id")
    L.append("WHERE d.indicator_name LIKE '其中：%' OR d.indicator_name LIKE '其中:%' OR d.indicator_name LIKE '其中、%';")
    L.append("")
    L.append("-- 4) 别名回填（dict 名(归一化) -> 2025 叶子名；VARIANT_MAP + 阶段0/阶段1 数值核验）")
    for src, dst in ALIAS_UPDATE_PAIRS:
        L.append("UPDATE %s d JOIN %s t ON t.level = 3 AND t.name = '%s'" % (DICT_TABLE, TREE_TABLE, _q(dst)))
        L.append("SET d.category_id = t.id WHERE %s = '%s';" % (n4, _q(src)))
    L.append("")
    L.append("-- 5) 排名行专链（indicator_code 含排名：只挂 {名称}排名 叶子；先清空防串值叶子）")
    L.append("UPDATE %s SET category_id = NULL WHERE indicator_code LIKE '%%排名';" % DICT_TABLE)
    L.append("UPDATE %s d JOIN %s t ON t.level = 3 AND t.name = IF(RIGHT(%s, 2) = '排名', %s, CONCAT(%s, '排名'))" % (DICT_TABLE, TREE_TABLE, n4, n4, n4))
    L.append("SET d.category_id = t.id WHERE d.indicator_code LIKE '%排名';")
    L.append("")
    return "\n".join(L) + "\n"


def append_backfill():
    """把挂树回填段幂等追加/替换到 dict.sql 末尾（默认模式与 --tree 共用）。"""
    with open(OUT, encoding="utf-8") as f:
        text = f.read()
    marker = "-- ===== 阶段1 挂树回填"
    idx = text.find(marker)
    if idx != -1:
        text = text[:idx].rstrip() + "\n"
    text += gen_backfill_sql()
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)


# ============================================================
# 四、主流程
# ============================================================

def main():
    rows = []
    with open(SRC, encoding="utf-8") as f:
        f.readline()
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 7:
                continue
            code, names, sheets, units, has_v, has_g, cnt = parts[:7]
            rows.append(dict(code=code, names=names, sheets=sheets, units=units, cnt=int(cnt)))
    vals = []
    for r in rows:
        cat, sub = classify(r["code"], r["names"], r["sheets"])
        unit = unit_fix(r["code"], r["names"], r["units"], cat, sub)
        name = first_name(r["code"], r["names"]).replace("'", "''")
        sheet = r["sheets"].split("|")[0].replace("'", "''")
        unit_sql = "'" + unit.replace("'", "''") + "'" if unit else "NULL"
        vals.append("('%s', '%s', '%s', '%s', %s, '%s')" % (
            r["code"].replace("'", "''"), name, cat, sub, unit_sql, sheet))
    lines = [
        "-- 统计月报指标分类字典（对齐源 Excel 树状结构：大类 × 中类 × 具体指标）",
        "-- 生成: tools/scraper/build_stat_category.py，共 %d 个 indicator_code" % len(vals),
        "CREATE TABLE IF NOT EXISTS stat_indicator_dict (",
        "    id BIGINT AUTO_INCREMENT PRIMARY KEY,",
        "    indicator_code VARCHAR(200) NOT NULL COMMENT '统计月报指标编码',",
        "    indicator_name VARCHAR(300) NOT NULL COMMENT '指标名称',",
        "    category VARCHAR(50) NOT NULL COMMENT '一级大类（对齐 Excel 主题域）',",
        "    sub_category VARCHAR(50) NOT NULL COMMENT '二级中类',",
        "    unit_fixed VARCHAR(50) NULL COMMENT '标准化单位（覆写脏 unit）',",
        "    sheet_name VARCHAR(200) NOT NULL COMMENT '来源 sheet',",
        "    status TINYINT DEFAULT 1,",
        "    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,",
        "    UNIQUE KEY uk_code (indicator_code)",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计月报指标分类字典';",
        "INSERT INTO stat_indicator_dict (indicator_code, indicator_name, category, sub_category, unit_fixed, sheet_name) VALUES",
        ",\n".join(vals) + ";",
        "",
        "-- 单位括号口径修正（与入库态一致）",
        "UPDATE stat_indicator_dict SET unit_fixed='亿元' WHERE indicator_code LIKE '%（亿元）%' OR indicator_code LIKE '%(亿元)%';",
        "UPDATE stat_indicator_dict SET unit_fixed='万美元' WHERE indicator_code LIKE '%（万美元）%' OR indicator_code LIKE '%(万美元)%';",
        "UPDATE stat_indicator_dict SET unit_fixed='万元' WHERE indicator_code LIKE '%（万元）%' OR indicator_code LIKE '%(万元)%';",
        "UPDATE stat_indicator_dict SET unit_fixed='亿千瓦小时' WHERE indicator_code LIKE '%（亿千瓦小时）%' OR indicator_code LIKE '%(亿千瓦小时)%';",
        "UPDATE stat_indicator_dict SET unit_fixed='万千瓦小时' WHERE indicator_code LIKE '%（万千瓦小时）%' OR indicator_code LIKE '%(万千瓦小时)%';",
        "UPDATE stat_indicator_dict SET unit_fixed='亿元' WHERE indicator_code IN ('利用省外境内资金（亿元、人民币）','利用市外境内资金（亿元、人民币）');",
        "",
    ]
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    append_backfill()
    print("写入 %s 共 %d 行，并追加挂树回填段" % (OUT, len(vals)))


def main_tree():
    if pymysql is None:
        raise SystemExit("缺少 pymysql：--tree 需要连接 MySQL 读取 stat_monthly 叶子")
    conn = pymysql.connect(**DB)
    try:
        cur = conn.cursor()
        cur.execute("SELECT indicator_name, MAX(sheet_name) FROM stat_monthly "
                    "WHERE period=%s GROUP BY indicator_name ORDER BY indicator_name", (LATEST_PERIOD,))
        leaf_sheet = dict(cur.fetchall())
        cur.execute("SELECT indicator_code, indicator_name, category FROM %s ORDER BY indicator_code" % DICT_TABLE)
        dict_rows = cur.fetchall()
    finally:
        conn.close()

    leaves = sorted(leaf_sheet)
    leaf_set = set(leaves)
    cat_of = {}
    for name in leaves:
        cat_of[name] = classify_leaf(name, leaf_sheet[name])
    assert len(cat_of) == len(leaves)

    # 分类统计
    from collections import Counter, defaultdict
    cat_cnt = Counter(v[0] for v in cat_of.values())
    sub_cnt = Counter(v for v in cat_of.values())

    # dict 回填匹配（与 SQL 段等价）
    matched = []
    unmatched = []
    for code, name, category in dict_rows:
        leaf = match_leaf(code, name, category, leaf_set)
        if leaf:
            matched.append((code, name, leaf))
        else:
            unmatched.append((code, name, category))
    linked_leaves = set(m for _, _, m in matched)
    unlinked_leaves = [l for l in leaves if l not in linked_leaves]

    # 生成 SQL 产物
    with open(TREE_OUT, "w", encoding="utf-8") as f:
        f.write(gen_tree_sql(leaves, cat_of))
    append_backfill()

    # 报告 CSV
    with open(LEFT_CSV, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["type", "indicator_name", "indicator_code", "category", "note"])
        for name in unlinked_leaves:
            w.writerow(["LEAF_UNLINKED", name, "", "", "2025叶子在 dict 中无对应行（挂树成功，category_id 回填目标为空）"])
        for code, name, category in unmatched:
            w.writerow(["DICT_UNMATCHED", name, code, category, "dict 行未匹配到 2025 叶子（保留原样）"])

    print("== 阶段1 树表生成 ==")
    print("叶子数: %d（period=%s）" % (len(leaves), LATEST_PERIOD))
    print("10 大类叶子分布: " + " | ".join("%s=%d" % (k, cat_cnt[k]) for k in TREE_SUBS))
    print("兜底（综合对比/主要经济指标）: %d" % sub_cnt.get(("综合对比", "主要经济指标"), 0))
    print("34 中类: %d 个" % len(sub_cnt))
    print("SQL 产物: %s" % TREE_OUT)
    print("")
    print("== 阶段1 dict.category_id 回填 ==")
    print("dict 共 %d 行：匹配 %d / 未匹配 %d" % (len(dict_rows), len(matched), len(unmatched)))
    print("叶子 %d：已挂链 %d / 未挂链 %d" % (len(leaves), len(linked_leaves), len(unlinked_leaves)))
    print("未挂链叶子清单:")
    for l in unlinked_leaves:
        print("  ", l)
    print("未匹配 dict 行清单（%d 行，详见 %s）:" % (len(unmatched), LEFT_CSV))
    for code, name, category in sorted(unmatched, key=lambda x: x[1]):
        print("   %-32s | %s" % (name, code))
    print("报告 CSV: %s" % LEFT_CSV)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--tree":
        main_tree()
    else:
        main()