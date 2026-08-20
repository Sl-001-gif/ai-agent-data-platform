# -*- coding: utf-8 -*-
"""统计指标名治理：清洗 2227 个指标名、去整句噪音、同义归一、生成「规范名+别名」映射。

用法（本机 MySQL：库 ai_agent_data，root（密码用环境变量 MYSQL_PWD 覆盖，勿硬编码），可用环境变量 MYSQL_PWD 覆盖）：
  python tools/indicator_governance.py --dry-run        # 生成映射/字典，不写库
  python tools/indicator_governance.py --apply          # 备份后更新 stat_indicator / stat_monthly
  python tools/indicator_governance.py --dict-sql       # 生成 metric_definition 注入 SQL

输出：
  docs/data/indicator_remap.csv         原指标名 -> 规范名/状态 映射表
  docs/data/stat_indicator_dict.json    规范名 -> {aliases, note, unit} 供 Prompt 注入
  docs/sql/stat-metric-definition.sql   metric_definition 指标口径（元数据 Prompt 直接可用）
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(ROOT, "docs", "data")
SQL_DIR = os.path.join(ROOT, "docs", "sql")
REMAP_CSV = os.path.join(DATA_DIR, "indicator_remap.csv")
DICT_JSON = os.path.join(DATA_DIR, "stat_indicator_dict.json")
METRIC_SQL = os.path.join(SQL_DIR, "stat-metric-definition.sql")

MYSQL = os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
DB = "ai_agent_data"

# ---------------------------------------------------------------------------
# 一、官方规范指标词典（权威来源：邵阳市统计月报目录 + 各数据页标题）
# ---------------------------------------------------------------------------
OFFICIAL_DICT = {
    "地区生产总值": {"aliases": ["GDP", "生产总值", "地区生产总值（GDP）", "全市生产总值", "分县(市、区)GDP", "分县市GDP"], "note": "绝对额(亿元)+同比增速", "unit": "亿元"},
    "第一产业增加值": {"aliases": ["第一产业"], "note": "绝对额(亿元)+增速", "unit": "亿元"},
    "第二产业增加值": {"aliases": ["第二产业"], "note": "绝对额(亿元)+增速", "unit": "亿元"},
    "第三产业增加值": {"aliases": ["第三产业"], "note": "绝对额(亿元)+增速", "unit": "亿元"},
    "规模工业增加值": {"aliases": ["规模以上工业增加值", "规上工业增加值", "规模工业增加值增速", "工业增加值", "分县(市、区)规模工业增加值"], "note": "同比增速(%)", "unit": "%"},
    "规模工业大类行业增加值": {"aliases": ["规模工业大类行业", "分行业增加值", "大类行业增加值"], "note": "分行业同比增速", "unit": "%"},
    "规模工业营业收入": {"aliases": ["规模工业营收", "主营业务收入"], "note": "绝对额(亿元)+增速", "unit": "亿元"},
    "规模工业利润总额": {"aliases": ["规模工业利润"], "note": "绝对额(亿元)+增速", "unit": "亿元"},
    "规模工业产销率": {"aliases": ["产销率"], "note": "产销率(%)", "unit": "%"},
    "固定资产投资": {"aliases": ["固投", "固定资产投资额", "固定资产投资增速", "分县(市、区)固定资产投资", "固定资产投资(不含跨区项目)"], "note": "同比增速(%)", "unit": "%"},
    "各行业固定资产投资": {"aliases": ["分行业固定资产投资", "行业固定资产投资", "各行业投资"], "note": "分行业投资额/增速", "unit": "亿元/%"},
    "施工项目个数": {"aliases": ["施工项目", "施工项目数"], "note": "个数(个)", "unit": "个"},
    "基础设施建设投资": {"aliases": ["基建投资", "基础设施投资"], "note": "增速(%)", "unit": "%"},
    "工业投资": {"aliases": ["工业固定资产投资", "分县(市、区)工业投资", "其中:工业投资", "其中：工业投资"], "note": "增速(%)", "unit": "%"},
    "房地产开发投资": {"aliases": ["房地产投资", "房地产"], "note": "增速(%)", "unit": "%"},
    "高技术产业投资": {"aliases": ["高技术投资"], "note": "增速(%)", "unit": "%"},
    "社会消费品零售总额": {"aliases": ["社零", "社会消费品零售额", "消费品零售总额", "消费品零售"], "note": "绝对额(亿元)+增速", "unit": "亿元"},
    "居民消费价格指数": {"aliases": ["CPI", "居民消费价格指数（%）"], "note": "同比(%)", "unit": "%"},
    "商品零售价格指数": {"aliases": ["商品零售价格指数（%）"], "note": "同比(%)", "unit": "%"},
    "价格指数": {"aliases": [], "note": "旧汇总名，不再使用", "unit": "%"},
    "工业投资": {"aliases": [], "note": "绝对额(万元→亿元)+增速", "unit": "亿元"},
    "产业投资": {"aliases": [], "note": "绝对额(万元)+增速", "unit": "万元"},
    "内资": {"aliases": ["实际利用内资", "分县（市、区）实际利用内资", "分县(市、区)实际利用内资"], "note": "实际利用内资(万元)+增速", "unit": "万元"},
    "进出口": {"aliases": ["进出口总额", "进出口额", "外贸", "对外贸易"], "note": "绝对额(亿元/万美元)+增速", "unit": "亿元"},
    "出口": {"aliases": ["出口额", "出口总额"], "note": "绝对额(亿元/万美元)+增速", "unit": "亿元"},
    "进口": {"aliases": ["进口额", "进口总额"], "note": "绝对额+增速", "unit": "亿元"},
    "进出口（万美元）": {"aliases": ["进出口总额（万美元）", "进出口总额(万美元)", "进出口总额（万美元）(上月数)", "进出口总额(万美元)(上月数)"], "note": "海关美元口径(万美元)，与进出口(亿元)人民币口径分开", "unit": "万美元"},
    "出口（万美元）": {"aliases": ["出口总额（万美元）", "出口总额(万美元)", "其中：出口（万美元）", "其中:出口(万美元)"], "note": "海关美元口径(万美元)，与出口(亿元)人民币口径分开", "unit": "万美元"},
    "进口（万美元）": {"aliases": ["进口总额（万美元）", "进口总额(万美元)"], "note": "海关美元口径(万美元)，与进口(亿元)人民币口径分开", "unit": "万美元"},
    "外商直接投资": {"aliases": ["实际利用外资", "利用外资", "外资", "外商投资"], "note": "绝对额(万美元)+增速", "unit": "万美元"},
    "一般公共预算收入": {"aliases": ["地方一般公共预算收入", "财政收入", "公共财政预算收入", "一般预算收入", "地方财政收入", "财政一般预算收入"], "note": "绝对额(万元)+增速", "unit": "万元"},
    "一般公共预算支出": {"aliases": ["公共财政预算支出", "财政支出", "一般预算支出", "财政总支出"], "note": "绝对额(万元)+增速", "unit": "万元"},
    "金融机构本外币存款余额": {"aliases": ["存款余额", "本外币存款", "金融机构存款余额"], "note": "余额(亿元)", "unit": "亿元"},
    "金融机构本外币贷款余额": {"aliases": ["贷款余额", "本外币贷款", "金融机构贷款余额"], "note": "余额(亿元)", "unit": "亿元"},
    "城乡居民收支": {"aliases": ["城乡收支", "城乡居民收入支出"], "note": "收入/支出(元)", "unit": "元"},
    "全体居民人均可支配收入": {"aliases": ["人均可支配收入", "全体居民收入", "居民人均可支配收入", "全体居民可支配收入", "居民可支配收入"], "note": "绝对额(元)+增速", "unit": "元"},
    "城镇居民人均可支配收入": {"aliases": ["城镇居民收入", "城镇人均可支配收入"], "note": "绝对额(元)+增速", "unit": "元"},
    "农村居民人均可支配收入": {"aliases": ["农村居民收入", "农民人均可支配收入", "农村人均可支配收入"], "note": "绝对额(元)+增速", "unit": "元"},
    "财政总收入": {"aliases": ["、财政总收入", "全市财政总收入"], "note": "全口径财政收入(万元)", "unit": "万元"},
    "税收收入": {"aliases": ["地方税收收入"], "note": "地方级税收收入(万元)+增速", "unit": "万元"},
    "税收收入（全口径）": {"aliases": ["税收入库", "税收入库(万元)", "全口径税收收入"], "note": "全口径税收收入(万元)+增速", "unit": "万元"},
    "非税收入": {"aliases": [], "note": "绝对额(万元)+增速", "unit": "万元"},
}

# 手工错字/截断修正：脏名 -> 规范名（优先级最高）
MANUAL_REMAP = {
    "共财政预算支出": "一般公共预算支出",
    "方财政收入": "一般公共预算收入",
    "方财政公共预算收入": "一般公共预算收入",
    "、一般预算支出": "一般公共预算支出",
    "全市公共财政预算支出": "一般公共预算支出",
    "全市实现财政总收入": "财政总收入",
    "县完成财政总收入": "财政总收入",
    "县实现财政总收入": "财政总收入",
    "全县完成财政总收入": "财政总收入",
    "全县实现财政总收入": "财政总收入",
    "其中:出口": "出口",
    "其中：出口": "出口",
   "全区完成财政总收入": "财政总收入",
    "全县完成工业增加值": "规模工业增加值",
    "企业实现主营业务收入": "规模工业营业收入",
    "全县实现生产总值": "地区生产总值",
    "全县完成生产总值": "地区生产总值",
    "全区完成两税税收总额": "税收收入",
}

# 区域名统一（stat_indicator 已较规范，仅补简称）
REGION_REMAP = {
    "城步苗族自治县": "城步县",
    "城步": "城步县",
    "邵东": "邵东市",
    "新宁": "新宁县",
    "武冈": "武冈市",
    "双清": "双清区",
    "大祥": "大祥区",
    "北塔": "北塔区",
    "新邵": "新邵县",
    "绥宁": "绥宁县",
    "隆回": "隆回县",
    "洞口": "洞口县",
    "邵阳县": "邵阳县",
}

_NOISE_RE = re.compile(r"(达到|完成|实现|占|其中[:：]|分别|同比|增幅|增长速度|1-9月|1-6月)")
_WRAP_RE = re.compile(r"^(分县[（(]市、区[)）]|分县市|分市州|全市|全县|全区|省级|市级|县级|其中[:：]|本年|本季)")
_VERB_RE = re.compile(r"(完成|实现|达到|同比增长|增长|下降|增加|减少)")
_PREFIX_RE = re.compile(r"^(#|、|，|,|（|\(|\s)*(\d+[、.)．]?|[（(]\d+[)）]|#)*\s*")
_TRAIL_RE = re.compile(r"[\s、，,。]+$")


def normalize(name):
    """清洗：去前导编号/符号、统一括号、去空白与尾部符号。"""
    s = str(name).strip()
    s = s.replace("（", "(").replace("）", ")").replace("　", "").replace(" ", "")
    s = _PREFIX_RE.sub("", s)
    s = _TRAIL_RE.sub("", s)
    return s


def canonical(name):
    """返回 (规范名, 状态)。状态：keep(清洗后名即规范) / remap(映射到官方规范名) / noise(整句噪音)。"""
    raw = str(name).strip()
    if not raw:
        return "", "noise"
    if raw in MANUAL_REMAP:
        return MANUAL_REMAP[raw], "remap"

    def lookup(cand):
        cand2 = normalize(cand)
        cand2 = re.sub(r"排名$", "", cand2)
        if cand2 in OFFICIAL_DICT:
            return cand2
        for canon, meta in OFFICIAL_DICT.items():
            if cand2 == canon or cand2 in meta["aliases"]:
                return canon
        return None

    # 1) 直接 / 去包装（全市/全县/分县…/其中：）命中
    for cand in (raw, _WRAP_RE.sub("", raw)):
        hit = lookup(cand)
        if hit:
            return hit, ("keep" if hit == normalize(cand) else "remap")

    # 2) 噪音句：去动词后再试映射，命中保留，否则判 noise
    if _NOISE_RE.search(raw):
        deverb = _VERB_RE.sub("", raw)
        deverb = _WRAP_RE.sub("", deverb)
        for cand in (deverb, re.sub(r"排名$", "", deverb)):
            hit = lookup(cand)
            if hit:
                return hit, "remap"
        return "", "noise"

    # 3) 保留清洗后名
    return normalize(raw), "keep"


def _run_sql(sql, db=DB):
    env = dict(os.environ)
    p = subprocess.run(
        [MYSQL, "-uroot", "--default-character-set=utf8mb4", "-N", "-B", db, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
    if p.returncode != 0:
        raise RuntimeError("mysql 执行失败: %s" % p.stderr.strip())
    return p.stdout


def load_all_names():
    """合并 stat_indicator 与 stat_monthly 的指标名（含行数），避免漏治理。"""
    merged = {}
    for table in ("stat_indicator", "stat_monthly"):
        for k, v in load_distinct(table, "indicator_name").items():
            merged[k] = merged.get(k, 0) + v
    return merged


def load_distinct(table, column):
    rows = _run_sql("SELECT %s, COUNT(*) FROM %s GROUP BY %s" % (column, table, column))
    out = {}
    for line in rows.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            out[parts[0]] = int(parts[1])
        elif len(parts) == 1:
            out[parts[0]] = 1
    return out


def backup_table(table):
    _run_sql("DROP TABLE IF EXISTS %s_bak_%s" % (table, "20260819b"), db=DB)
    _run_sql("CREATE TABLE %s_bak_%s LIKE %s" % (table, "20260819b", table), db=DB)
    _run_sql("INSERT INTO %s_bak_%s SELECT * FROM %s" % (table, "20260819b", table), db=DB)
    return "%s_bak_20260819b" % table


# ---------------------------------------------------------------------------
# 三、主流程
# ---------------------------------------------------------------------------

def build_remap(name_counts):
    remap = {}
    stats = {"keep": 0, "remap": 0, "noise": 0, "total": 0}
    for name, cnt in name_counts.items():
        canon, status = canonical(name)
        remap[name] = {"canonical": canon, "status": status, "rows": cnt}
        stats[status] += 1
        stats["total"] += 1
    return remap, stats


def write_remap_csv(remap):
    os.makedirs(DATA_DIR, exist_ok=True)
    lines = ["indicator_name,canonical,status,rows"]
    for name, info in sorted(remap.items()):
        lines.append("%s,%s,%s,%s" % (name, info["canonical"], info["status"], info["rows"]))
    with open(REMAP_CSV, "w", encoding="utf-8-sig") as f:
        f.write("\n".join(lines) + "\n")


def write_dict_json(remap):
    """规范名 -> {aliases, note, unit}：官方词典 + 治理后保留的其它指标。"""
    canon_map = {}
    for name, info in remap.items():
        if info["status"] in ("keep", "remap") and info["canonical"]:
            canon_map.setdefault(info["canonical"], []).append(name)
    out = {}
    for canon, meta in OFFICIAL_DICT.items():
        aliases = list(meta["aliases"])
        extra = [n for n in canon_map.get(canon, []) if n != canon and n not in aliases
                and "排名" not in n and "分县" not in n and "分市州" not in n]
        out[canon] = {"aliases": aliases + extra, "note": meta["note"], "unit": meta["unit"]}
    # 治理后保留但不在官方词典中的指标（如 用电量、出口交货值 等）
    for canon, names in canon_map.items():
        if canon not in out:
            out[canon] = {"aliases": [n for n in names if n != canon], "note": "", "unit": ""}
    with open(DICT_JSON, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    return out


def apply_to_db(remap):
    backup_table("stat_indicator")
    backup_table("stat_monthly")
    total = 0
    for name, info in remap.items():
        if info["status"] == "noise" or not info["canonical"]:
            continue
        if name == info["canonical"]:
            continue
        _run_sql(
            "UPDATE stat_indicator SET indicator_name = '%s' WHERE indicator_name = '%s'"
            % (_sql_escape(info["canonical"]), _sql_escape(name)), db=DB)
        _run_sql(
            "UPDATE stat_monthly SET indicator_name = '%s' WHERE indicator_name = '%s'"
            % (_sql_escape(info["canonical"]), _sql_escape(name)), db=DB)
        total += 1
    return total


def _sql_escape(s):
    return s.replace("\\", "\\\\").replace("'", "''")


def write_metric_sql(stat_dataset_id=11):
    """生成 metric_definition 注入 SQL：只注入官方核心指标（约 36 个），避免 Prompt 过长。"""
    with open(DICT_JSON, encoding="utf-8") as f:
        d = json.load(f)
    core = {k: v for k, v in d.items() if k in OFFICIAL_DICT}
    lines = ["-- 统计指标口径注入（供元数据 Prompt 使用）", "-- 生成：python tools/indicator_governance.py --dict-sql",
             "-- 幂等：先删后插（按 metric_code 唯一）", ""]
    lines.append("DELETE FROM metric_definition WHERE dataset_id = %d;" % stat_dataset_id)
    lines.append("INSERT INTO metric_definition (name, description, dataset_id, metric_code, metric_type, status, sort) VALUES")
    vals = []
    i = 1
    for canon, meta in core.items():
        aliases = "、".join(meta.get("aliases") or [])
        desc = "统计指标「%s」；口径：%s；单位：%s；别名：%s（分析时用户说法可归一到此指标）" % (
            canon, meta.get("note") or "见官方月报", meta.get("unit") or "-", aliases or "-")
        vals.append("('%s', '%s', %d, 'stat_%03d', '统计指标', 1, %d)" % (
            canon.replace("'", "''"), desc.replace("'", "''"), stat_dataset_id, i, i))
        i += 1
    lines.append(",\n".join(vals) + ";")
    os.makedirs(SQL_DIR, exist_ok=True)
    with open(METRIC_SQL, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="生成映射/字典，不写库")
    ap.add_argument("--apply", action="store_true", help="备份后更新 stat_indicator/stat_monthly")
    ap.add_argument("--dict-sql", action="store_true", help="生成 metric_definition 注入 SQL")
    args = ap.parse_args()

    names = load_all_names()
    remap, stats = build_remap(names)
    write_remap_csv(remap)
    write_dict_json(remap)
    print("指标名总数: %d" % stats["total"])
    print("  保留(keep): %d" % stats["keep"])
    print("  映射(remap): %d" % stats["remap"])
    print("  噪音(noise): %d" % stats["noise"])
    print("映射表: %s" % REMAP_CSV)
    print("字典: %s" % DICT_JSON)
    if args.apply:
        total = apply_to_db(remap)
        print("已更新 %d 个指标名（stat_indicator/stat_monthly 已备份 *_bak_20260819b）" % total)
    if args.dict_sql:
        write_metric_sql()
        print("注入 SQL: %s" % METRIC_SQL)


if __name__ == "__main__":
    main()
