-- ============================================================
-- 统计类数据结构化采集管道（stat-data-pipeline）建表 + 元数据注册
-- 2026-08-06 | 幂等可重复执行 | 只新增表与元数据，不碰既有表/既有元数据
-- 用法: mysql -uroot --default-character-set=utf8mb4 ai_agent_data -e "source docs/sql/stat-pipeline.sql"
-- ============================================================
SET NAMES utf8mb4;

-- ---------------------------------------------
-- 1. stat_doc：三类统计详情页正文全文 + 附件元数据（仅溯源，不注册进 AI 元数据）
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS stat_doc (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    gov_record_id BIGINT NOT NULL COMMENT '关联 gov_info_record.id',
    category VARCHAR(50) NOT NULL COMMENT '统计月报/统计公报/统计分析',
    title VARCHAR(500) NOT NULL,
    doc_date DATE NULL COMMENT '列表页发布日期',
    source_url VARCHAR(1000) NOT NULL COMMENT '详情页 URL',
    content LONGTEXT NULL COMMENT '详情页正文全文（已去导航/页眉/分享噪音）',
    attachment_url VARCHAR(1000) NULL COMMENT '附件绝对 URL（月报 xlsx）',
    attachment_name VARCHAR(300) NULL,
    parse_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE(有附件待解析)/TEXT_DONE(纯正文)/XLSX_DONE/XLSX_FAIL/FAILED',
    fail_reason VARCHAR(500) NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gov_record (gov_record_id),
    KEY idx_category_status (category, parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计类详情页正文与附件元数据';

-- ---------------------------------------------
-- 2. stat_indicator：期间 × 指标 × 区县 结构化指标长表（AI 引擎可分析）
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS stat_indicator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_doc_id BIGINT NOT NULL COMMENT '关联 stat_doc.id',
    period VARCHAR(50) NOT NULL COMMENT '期间，如 2025年1-9月 / 2025年1-12月 / 2025年',
    region VARCHAR(50) NOT NULL COMMENT '全市 或 区县官方全名（新宁县/邵东市/双清区…）',
    indicator_code VARCHAR(200) NOT NULL COMMENT '指标名清洗编码（去空白/前导编号）',
    indicator_name VARCHAR(300) NOT NULL COMMENT '指标原始名称',
    value DECIMAL(20,4) NULL COMMENT '指标数值（单位见 unit）',
    unit VARCHAR(50) NULL COMMENT '数值单位：亿元/万元/元/%/名/个百分点 等',
    growth_rate DECIMAL(12,2) NULL COMMENT '同比增速(%)，下降为负',
    sheet_name VARCHAR(200) NOT NULL DEFAULT '' COMMENT '来源 xlsx 工作表名；正文抽取为空',
    source_type VARCHAR(20) NOT NULL COMMENT 'XLSX/ANALYSIS/BULLETIN',
    confidence VARCHAR(10) NOT NULL DEFAULT 'medium' COMMENT 'high/medium/low',
    generator_type VARCHAR(10) NOT NULL DEFAULT 'RULE' COMMENT 'RULE/LLM',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_indicator (stat_doc_id, sheet_name, indicator_code, region, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计指标长表：期间×指标×区县 一行一指标';

-- ---------------------------------------------
-- 3. 元数据注册（仅 stat_indicator，幂等；stat_doc 不注册）
-- ---------------------------------------------
INSERT INTO dataset (name, description, business_scene, table_name, sort, db_type, db_port, db_name, status)
SELECT '邵阳统计指标数据',
       '统计月报/统计公报/统计分析 三类公开数据经结构化采集得到的「期间×指标×区县」指标库（stat_indicator 长表），
        可直接做趋势/对比/排名分析：如 2025年1-9月全市 GDP、各区县财政收入排名、规模工业增加值增速趋势',
       '统计分析', 'stat_indicator', 4, 'MYSQL', 3306, 'ai_agent_data', 1
WHERE NOT EXISTS (SELECT 1 FROM dataset WHERE table_name = 'stat_indicator');

-- 幂等清理旧配置（防重复执行累积；dataset 本身用 WHERE NOT EXISTS 保幂等）
DELETE FROM metric_definition WHERE dataset_id IN (SELECT id FROM dataset WHERE table_name = 'stat_indicator');
DELETE FROM table_field WHERE table_id IN (SELECT id FROM table_schema WHERE table_name = 'stat_indicator');
DELETE FROM table_schema WHERE table_name = 'stat_indicator';

INSERT INTO table_schema (dataset_id, table_name, table_comment, status, relation_desc, sort)
SELECT id, 'stat_indicator', '统计指标长表：期间×指标×区县，一行一指标（统计月报 xlsx 解析 + 公报/分析正文抽取）', 1,
       '关联 stat_doc（详情页正文溯源）→ 关联 gov_info_record（原始记录）', 1
FROM dataset WHERE table_name = 'stat_indicator';

-- 字段语义（stat_indicator 11 个业务字段）
INSERT INTO table_field (table_id, field_name, field_type, field_comment, business_meaning, is_metric, semantic_type, can_query, can_agg, sort)
SELECT ts.id, 'period', 'VARCHAR(50)', '期间', '统计期间，如 2025年1-9月 / 2025年1-12月 / 2025年，用于时间维度趋势分析', 0, '维度', 1, 1, 1 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'region', 'VARCHAR(50)', '区县', '全市 或 区县官方全名（新宁县/邵东市/双清区/大祥区/北塔区/新邵县/邵阳县/隆回县/洞口县/绥宁县/城步苗族自治县/武冈市），用于区域对比与排名', 0, '维度', 1, 1, 2 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'indicator_code', 'VARCHAR(200)', '指标编码', '指标名清洗编码（去空白与前导编号），用于精确匹配指标', 0, '维度', 1, 1, 3 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'indicator_name', 'VARCHAR(300)', '指标名称', '指标原始名称（如 地区生产总值（GDP）/地方一般公共预算收入/全体居民人均可支配收入/规模工业产销率）', 0, '维度', 1, 1, 4 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'value', 'DECIMAL(20,4)', '指标数值', '指标数值，单位见 unit 字段；排名类指标 value 为名次、unit=名', 1, '指标', 1, 1, 5 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'unit', 'VARCHAR(50)', '单位', '数值单位：亿元/万元/元/%/名/个百分点 等', 0, '维度', 1, 0, 6 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'growth_rate', 'DECIMAL(12,2)', '增速(%)', '同比增速（百分比数值，下降为负），与 value 同一期间口径', 1, '指标', 1, 1, 7 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'sheet_name', 'VARCHAR(200)', '来源工作表', '来源 xlsx 工作表名（统计月报）；正文抽取为空串', 0, '维度', 1, 0, 8 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'source_type', 'VARCHAR(20)', '来源类型', 'XLSX=月报卡附件 / ANALYSIS=统计分析正文 / BULLETIN=统计公报正文', 0, '维度', 1, 0, 9 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'confidence', 'VARCHAR(10)', '置信度', 'high=xlsx 结构化 / medium=规则或LLM 抽取 / low=期间推断', 0, '维度', 1, 0, 10 FROM table_schema ts WHERE ts.table_name = 'stat_indicator'
UNION ALL SELECT ts.id, 'generator_type', 'VARCHAR(10)', '生成方式', 'RULE=规则引擎 / LLM=大模型兜底抽取', 0, '维度', 1, 0, 11 FROM table_schema ts WHERE ts.table_name = 'stat_indicator';

-- 指标口径（metric_definition：供 LLM 生成 SQL 时参考，region 维度=全市/区县官方全名）
INSERT INTO metric_definition (name, description, calculation_formula, table_id, status, dataset_id, metric_code, metric_type, sql_expression, sort)
SELECT '地区生产总值（GDP）','邵阳市地区生产总值绝对额与同比增速（口径：stat_indicator 中 indicator_name 含 地区生产总值、region=全市，value 为绝对额、growth_rate 为同比增速）','SELECT period, value, growth_rate FROM stat_indicator WHERE indicator_name LIKE ''%地区生产总值%'' AND region = ''全市'' ORDER BY period', ts.id, 1, d.id, 'gdp', '基础指标', 'SELECT period, value, growth_rate FROM stat_indicator WHERE indicator_name LIKE ''%地区生产总值%'' AND region = ''全市'' ORDER BY period', 1
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'stat_indicator'
UNION ALL
SELECT '一般公共预算收入','地方一般公共预算收入（口径：indicator_name 含 一般公共预算收入，绝对额万元）','SELECT period, value, growth_rate FROM stat_indicator WHERE indicator_name LIKE ''%一般公共预算收入%'' ORDER BY period', ts.id, 1, d.id, 'fiscal_revenue', '基础指标', 'SELECT period, value, growth_rate FROM stat_indicator WHERE indicator_name LIKE ''%一般公共预算收入%'' ORDER BY period', 2
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'stat_indicator'
UNION ALL
SELECT '规模以上工业增加值','规模以上/规模工业增加值（口径：indicator_name 含 规模工业增加值 或 规模以上工业增加值）','SELECT period, value, growth_rate FROM stat_indicator WHERE (indicator_name LIKE ''%规模工业增加值%'' OR indicator_name LIKE ''%规模以上工业增加值%'') ORDER BY period', ts.id, 1, d.id, 'industry_add_value', '基础指标', 'SELECT period, value, growth_rate FROM stat_indicator WHERE (indicator_name LIKE ''%规模工业增加值%'' OR indicator_name LIKE ''%规模以上工业增加值%'') ORDER BY period', 3
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'stat_indicator'
UNION ALL
SELECT '居民人均可支配收入','居民人均可支配收入（口径：indicator_name 含 居民人均可支配收入；全体/城镇/农村 分别查询）','SELECT period, region, value, growth_rate FROM stat_indicator WHERE indicator_name LIKE ''%居民人均可支配收入%'' ORDER BY period', ts.id, 1, d.id, 'income_per_capita', '基础指标', 'SELECT period, region, value, growth_rate FROM stat_indicator WHERE indicator_name LIKE ''%居民人均可支配收入%'' ORDER BY period', 4
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'stat_indicator'
UNION ALL
SELECT '区县指标排名','区县间指标排名（口径：indicator_name 以 排名 结尾、region<>全市，value 为名次、unit=名；按 value 升序即最优）','SELECT region, value AS rank_no FROM stat_indicator WHERE indicator_name LIKE ''%排名'' AND region <> ''全市'' ORDER BY value', ts.id, 1, d.id, 'region_ranking', '计算指标', 'SELECT region, value AS rank_no FROM stat_indicator WHERE indicator_name LIKE ''%排名'' AND region <> ''全市'' ORDER BY value', 5
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'stat_indicator';