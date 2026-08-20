# -*- coding: utf-8 -*-
"""align_stat_monthly.py — stat_monthly 对齐 2025-09 最新月报口径（幂等、备份先行、可回滚）
用法: python align_stat_monthly.py --dry-run   # 只打印计划
      python align_stat_monthly.py --apply     # 执行（自动备份）
输出: tools/scraper/align_stat_monthly.log
"""
import os, sys, subprocess, datetime

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "ai_agent_data"
BAK = "stat_monthly_bak_20260820"
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "align_stat_monthly.log")

DRY = "--apply" not in sys.argv

def log(msg):
    line = f"[{datetime.datetime.now().isoformat(timespec='seconds')}] {msg}"
    print(line)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")

def q(sql, args=None):
    env = dict(os.environ)
    env["MYSQL_PWD"] = "Admin@123456"
    full = sql if args is None else sql % args
    p = subprocess.run([MYSQL, "-uroot", "--default-character-set=utf8mb4", "-N", "-B", DB, "-e", full],
                       capture_output=True, text=True, encoding="utf-8", env=env, errors="replace")
    if p.returncode != 0:
        raise RuntimeError(f"SQL失败: {p.stderr.strip()} | {full[:300]}")
    return p.stdout

def esc(v):
    if v is None: return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "\\'") + "'"

def run(sql, desc):
    if DRY:
        log(f"[计划] {desc}\n    SQL: {sql[:400]}")
        return 0
    rows = q(sql)
    log(f"[执行] {desc}")
    return len(rows.strip().splitlines()) if rows.strip() else 0

# ---------- 备份 ----------
def backup():
    exists = q(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{DB}' AND table_name='{BAK}'")
    if exists.strip() == "0":
        run(f"CREATE TABLE {BAK} AS SELECT * FROM stat_monthly", f"备份 stat_monthly -> {BAK}")
    else:
        log(f"[跳过] 备份表 {BAK} 已存在")

# ---------- 通用改名/合并 ----------
def rename_dedupe(old, new, unit=None, div=False, sub="", extra_where=""):
    """把 old 改名 new；若 (period,region) 在 new 已存在且同值则删除；unit 指定则更新；div 指定则 value>10000 时 /10000。"""
    rows = q(f"SELECT id, period, region, value FROM stat_monthly WHERE indicator_name={esc(old)} {extra_where} {sub}")
    if not rows.strip():
        log(f"[跳过] {old} 无残留行")
        return
    ids = [r.split("\t")[0] for r in rows.strip().splitlines()]
    id_list = ",".join(ids)
    # 删除已在 new 中存在同 (period,region,value) 的行
    dup = q(f"""SELECT a.id FROM stat_monthly a JOIN stat_monthly b
                ON a.period=b.period AND a.region=b.region AND a.value=b.value AND IFNULL(a.growth_rate,-9999)=IFNULL(b.growth_rate,-9999)
                WHERE a.indicator_name={esc(old)} {extra_where} AND b.indicator_name={esc(new)} {sub}""")
    if dup.strip():
        dup_ids = ",".join(dup.strip().splitlines())
        run(f"DELETE FROM stat_monthly WHERE id IN ({dup_ids})", f"{old}->{new}: 删除 {len(dup_ids.split(','))} 行重复(已存在同值)")
        ids = [i for i in ids if i not in dup_ids.split(",")]
        id_list = ",".join(ids)
    if not ids:
        return
    val = ""
    if div:
        val = ", value = CASE WHEN value > 10000 THEN ROUND(value/10000, 4) ELSE value END"
    unit_sql = f", unit = {esc(unit)}" if unit else ""
    run(f"UPDATE stat_monthly SET indicator_name = {esc(new)}, indicator_code = {esc(new.replace(' ',''))}{val}{unit_sql} WHERE id IN ({id_list})",
        f"{old}->{new}: 改名 {len(ids)} 行" + ("（值>10000 除10000）" if div else "") + (f"，单位={unit}" if unit else ""))

# ---------- 1. 外资合并 ----------
def p0_foreign():
    rename_dedupe("实际利用境外资金", "外商直接投资", unit="万美元")

# ---------- 2. 双名去重 ----------
def p0_dupnames():
    rename_dedupe("地区生产总值(GDP)", "地区生产总值")
    rename_dedupe("生产总值(GDP)", "地区生产总值")
    rename_dedupe("一般公共预算支出合计", "一般公共预算支出")

# ---------- 3. 客运货运改名 + 2025 派生 ----------
def p1_transport():
    rename_dedupe("全社会客运量(万人)", "客运量(万人)", unit="万人")
    rename_dedupe("全社会货运量(万吨)", "货运量(万吨)", unit="万吨")
    rename_dedupe("客运周转量(万人公里)", "旅客周转量(万人公里)", unit="万人公里")
    rename_dedupe("货运周转量(万吨公里)", "货物周转量(万吨公里)", unit="万吨公里")
    if DRY:
        return
    # 2025 派生合计：客运量 = 公路+水运；旅客周转量 = 公路+水运；货运量 = 公路(+水运)；货物周转量 = 公路(+水运)
    for new, parts, unit in [
        ("客运量(万人)", ["公路(万人)", "水运(万人)"], "万人"),
        ("旅客周转量(万人公里)", ["公路(万人公里)", "水运(万人公里)"], "万人公里"),
        ("货运量(万吨)", ["公路(万吨)", "水运(万吨)"], "万吨"),
        ("货物周转量(万吨公里)", ["公路(万吨公里)", "水运(万吨公里)"], "万吨公里"),
    ]:
        periods = q("SELECT DISTINCT period FROM stat_monthly WHERE period LIKE '2025年%' AND indicator_name=" + esc(parts[0]))
        for period in [p.strip() for p in periods.strip().splitlines() if p.strip()]:
            exists = q(f"SELECT COUNT(*) FROM stat_monthly WHERE period={esc(period)} AND region='全市' AND indicator_name={esc(new)}")
            if exists.strip() != "0":
                continue
            sums = {}
            for part in parts:
                r = q(f"SELECT value FROM stat_monthly WHERE period={esc(period)} AND region='全市' AND indicator_name={esc(part)} LIMIT 1")
                if r.strip():
                    sums[part] = float(r.strip().splitlines()[0].split("\t")[0])
            if not sums:
                continue
            total = sum(sums.values())
            # 同比增速：对比上年同期派生值
            prev_period = period.replace("2025年", "2024年")
            prev = q(f"SELECT value FROM stat_monthly WHERE period={esc(prev_period)} AND region='全市' AND indicator_name={esc(new)} LIMIT 1")
            growth = "NULL"
            if prev.strip():
                try:
                    pv = float(prev.strip().splitlines()[0].split("\t")[0])
                    if pv:
                        growth = str(round((total - pv) / pv * 100, 2))
                except ValueError:
                    pass
            doc_row = q(f"SELECT stat_doc_id FROM stat_monthly WHERE period={esc(period)} AND region='全市' AND indicator_name={esc(parts[0])} LIMIT 1").strip()
            doc_id = doc_row.splitlines()[0].split(chr(9))[0] if doc_row else '0'
            sql = (f"INSERT INTO stat_monthly (period, region, indicator_code, indicator_name, value, growth_rate, unit, sheet_name, source_type, stat_doc_id) "
                   f"VALUES ({esc(period)}, '全市', {esc(new.replace(' ',''))}, {esc(new)}, {total}, {growth}, {esc(unit)}, '交邮10(派生)', 'XLSX', {esc(doc_id)})")
            run(sql, f"派生 {new} {period} = {total}（{'+'.join(str(round(v,4)) for v in sums.values())}）增速={growth}")

# ---------- 4. 命名污染 ----------
def p1_pollution():
    rename_dedupe("全市用电总量(万千瓦小时)投资", "全市用电总量(亿千瓦小时)", unit="亿千瓦小时", div=True)
    rename_dedupe("全市用电总量(亿千瓦小时)投资", "全市用电总量(亿千瓦小时)", unit="亿千瓦小时", div=True)
    rename_dedupe("商品房屋销售额投资", "商品房屋销售额", unit="亿元")
    rename_dedupe("商品房施工面积投资", "商品房施工面积", unit="万平方米")
    rename_dedupe("商品房竣工面积投资", "商品房竣工面积", unit="万平方米")
    rename_dedupe("商品房销售面积投资", "商品房销售面积", unit="万平方米")
    rename_dedupe("施工项目个数(个)投资", "施工项目个数", unit="个")
    rename_dedupe("新开工项目个数(个)投资", "新开工项目个数", unit="个")
    rename_dedupe("其中、新开工项目个数(个)投资", "其中:新开工项目个数", unit="个")
    rename_dedupe("待售面积投资", "待售面积", unit="万平方米")
    # 工业用电量：单位修正 + 值>10000 除 10000
    rows = q("SELECT COUNT(*) FROM stat_monthly WHERE indicator_name='工业用电量' AND (unit IS NULL OR unit='' OR unit!='亿千瓦小时')")
    if rows.strip() != "0":
        run("UPDATE stat_monthly SET unit='亿千瓦小时', value = CASE WHEN value > 10000 THEN ROUND(value/10000,4) ELSE value END WHERE indicator_name='工业用电量'",
            "工业用电量：单位→亿千瓦小时，值>10000 除 10000")

# ---------- 5. 贸外处省级区域行 ----------
def p1_region_rows():
    rows = q("SELECT id, indicator_name, period, region, value FROM stat_monthly WHERE indicator_name LIKE '%贸外处%'")
    if not rows.strip():
        log("[跳过] 无贸外处行")
        return
    for line in rows.strip().splitlines():
        parts = line.split("\t")
        rid, name, period, region, value = parts[0], parts[1], parts[2], parts[3], parts[4]
        if "(45贸外处4)" in name:
            new_name, new_region = "进出口", name.split("(")[0]
        elif "(46贸外处5)" in name:
            new_name, new_region = "出口", name.split("(")[0]
        else:
            log(f"[跳过] 未知贸外处行: {name}")
            continue
        run(f"UPDATE stat_monthly SET indicator_name={esc(new_name)}, indicator_code={esc(new_name)}, region={esc(new_region)}, unit='万元' WHERE id={rid}",
            f"贸外处: {name}(region={region}) -> {new_name}(region={new_region}) 万元")

# ---------- 6. 单位补全 ----------
UNIT_MAP = {
    "地区生产总值": "亿元", "第一产业增加值": "亿元", "第二产业增加值": "亿元", "第三产业增加值": "亿元",
    "规模工业增加值": "%", "规模工业总产值": "%", "规模以下工业总产值": "%", "工业产品销售产值(现价)": "亿元",
    "出口交货值": "亿元", "规模工业亏损面": "%", "规模工业营业收入": "亿元", "规模工业产品销售成本": "亿元",
    "规模工业两金占用": "亿元", "规模工业利税总额": "亿元", "规模工业利润总额": "亿元",
    "规模工业亏损企业亏损额": "亿元", "规模工业从业人员平均人数": "人",
    "工业用电量": "亿千瓦小时", "全市用电总量(亿千瓦小时)": "亿千瓦小时",
    "客运量(万人)": "万人", "货运量(万吨)": "万吨", "旅客周转量(万人公里)": "万人公里", "货物周转量(万吨公里)": "万吨公里",
    "固定资产投资": "%", "国有投资": "%", "非国有投资": "%", "民间投资": "%", "中央项目投资": "%", "地方项目投资": "%",
    "第一产业投资": "%", "第二产业投资": "%", "第三产业投资": "%", "工业投资": "%", "工业技改投资": "%",
    "高技术产业投资": "%", "民生工程投资": "%", "生态环境投资": "%", "基础设施建设投资": "%", "房地产开发投资": "%",
    "建筑安装工程投资": "%", "设备工器具购置投资": "%", "制造业投资": "%", "产业投资": "%",
    "一般公共预算收入": "万元", "一般公共预算支出": "万元", "税收收入": "万元", "非税收入": "万元",
    "个人所得税": "万元", "企业所得税": "万元", "车辆购置税": "万元", "营业税": "万元", "保险收入": "万元", "保险赔付": "万元",
    "外商直接投资": "万美元", "进出口": "亿元", "出口": "亿元", "进口": "亿元",
    "社会消费品零售总额": "亿元", "各项存款": "亿元", "各项贷款": "亿元",
    "全体居民人均可支配收入": "元", "城镇居民人均可支配收入": "元", "农村居民人均可支配收入": "元",
    "全市居民人均消费支出": "元", "城镇居民人均生活消费支出": "元", "农村居民人均生活消费支出": "元",
    "居民消费价格指数": "%", "商品零售价格指数": "%",
    "商品房销售面积": "万平方米", "商品房施工面积": "万平方米", "商品房竣工面积": "万平方米", "商品房屋销售额": "亿元",
    "施工项目个数": "个", "新开工项目个数": "个", "其中:新开工项目个数": "个", "待售面积": "万平方米",
    "分市州GDP": "亿元", "分市州固定资产投资": "亿元", "分市州消费品零售总额": "亿元", "分市州规模工业增加值": "%",
}
# 通用规则：值列有数 -> map 单位；只有增速 -> %；排名 -> 名
EXTRA_UNIT_MAP = {
    "税收收入（全口径）": "万元", "上划中央收入": "万元", "上划省级收入": "万元",
    "专项收入": "万元", "卫生健康支出": "万元", "教育": "万元", "民生支出": "万元",
    "国税系统": "万元", "地税系统": "万元", "利用省外境内资金(亿元、人民币)": "亿元",
    "住宅": "万平方米",
    "公路(万吨)": "万吨", "公路(万吨公里)": "万吨公里", "公路(万人)": "万人",
    "水运(万人)": "万人", "公路(万人公里)": "万人公里", "水运(万人公里)": "万人公里",
}

def p2_units():
    if DRY:
        # 打印需要补的分布
        dist = q("SELECT indicator_name, COUNT(*) FROM stat_monthly WHERE (unit IS NULL OR unit='') GROUP BY indicator_name ORDER BY COUNT(*) DESC")
        lines = dist.strip().splitlines()
        log(f"[计划] 单位缺失指标 {len(lines)} 个，共 {sum(int(l.split(chr(9))[1]) for l in lines)} 行")
        return
    # 1) 增速类（value IS NULL 且有 growth_rate）-> %
    run("UPDATE stat_monthly SET unit='%' WHERE (unit IS NULL OR unit='') AND value IS NULL AND growth_rate IS NOT NULL",
        "单位补全：增速类 value=NULL -> %")
    # 2) 排名 -> 名
    run("UPDATE stat_monthly SET unit='名' WHERE (unit IS NULL OR unit='') AND indicator_name LIKE '%排名%'",
        "单位补全：排名 -> 名")
    # 3) 按指标 map
    for name, unit in UNIT_MAP.items():
        run(f"UPDATE stat_monthly SET unit={esc(unit)} WHERE (unit IS NULL OR unit='') AND indicator_name={esc(name)}",
            f"单位补全：{name} -> {unit}")

    # 删除旧名重复：城乡居民收支(元) == 全体居民人均可支配收入（13 完全同值 + 4 精度略低）
    dup = q("SELECT a.id FROM stat_monthly a JOIN stat_monthly b ON a.period=b.period AND a.region=b.region "
            "AND a.indicator_name='城乡居民收支(元)' AND b.indicator_name='全体居民人均可支配收入'")
    if dup.strip():
        ids = ",".join(dup.strip().splitlines())
        run(f"DELETE FROM stat_monthly WHERE id IN ({ids})", f"删除 城乡居民收支(元) 重复 {len(ids.split(','))} 行")
    for name, unit in EXTRA_UNIT_MAP.items():
        run(f"UPDATE stat_monthly SET unit={esc(unit)} WHERE (unit IS NULL OR unit='') AND indicator_name={esc(name)}",
            f"单位补全：{name} -> {unit}")

# ---------- 校验 ----------
def verify():
    log("===== 校验 =====")
    checks = [
        ("残留旧名", "SELECT COUNT(*) FROM stat_monthly WHERE indicator_name IN ('实际利用境外资金','地区生产总值(GDP)','生产总值(GDP)','一般公共预算支出合计','全社会客运量(万人)','全社会货运量(万吨)','客运周转量(万人公里)','货运周转量(万吨公里)','分县（市、区）GDP','分县(市、区)GDP')"),
        ("残留污染名", "SELECT COUNT(*) FROM stat_monthly WHERE indicator_name LIKE '%投资' AND (indicator_name LIKE '%用电总量%' OR indicator_name LIKE '%商品房%' OR indicator_name LIKE '%(个)%' OR indicator_name LIKE '%待售面积%')"),
        ("残留贸外处", "SELECT COUNT(*) FROM stat_monthly WHERE indicator_name LIKE '%贸外处%'"),
        ("残留 unit NULL", "SELECT COUNT(*) FROM stat_monthly WHERE unit IS NULL OR unit=''"),
        ("2025-09 GDP 全市", "SELECT value, growth_rate, unit FROM stat_monthly WHERE period='2025年1-9月' AND region='全市' AND indicator_name='地区生产总值'"),
        ("2025-09 预算收入", "SELECT value, growth_rate, unit FROM stat_monthly WHERE period='2025年1-9月' AND region='全市' AND indicator_name='一般公共预算收入'"),
        ("2025-09 客运派生", "SELECT period, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='客运量(万人)' AND region='全市' AND period LIKE '2025年%' ORDER BY period"),
        ("2025-09 用电总量", "SELECT period, value, unit FROM stat_monthly WHERE indicator_name='全市用电总量(亿千瓦小时)' AND region='全市' ORDER BY period"),
        ("2025-09 外商直接投资", "SELECT value, unit FROM stat_monthly WHERE period='2025年1-9月' AND region='全市' AND indicator_name='外商直接投资'"),
        ("总行数", "SELECT COUNT(*) FROM stat_monthly"),
    ]
    for name, sql in checks:
        out = q(sql)
        log(f"[校验] {name}: {out.strip()}")

if __name__ == "__main__":
    log(f"===== 开始对齐（{'DRY-RUN' if DRY else 'APPLY'}）=====")
    backup()
    p0_foreign()
    p0_dupnames()
    p1_transport()
    p1_pollution()
    p1_region_rows()
    p2_units()
    verify()
    log("===== 完成 =====")