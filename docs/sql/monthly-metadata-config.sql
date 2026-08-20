-- 月报数据元配置 + AI 分析配置（幂等可重跑，2026-08-14）
-- 数据源：stat_monthly（统计月报附件结构化指标，85 份，13 区县）

-- 1) 数据集
INSERT INTO dataset (name, description, db_type, db_name, status, business_scene, table_name, sort, category_id)
SELECT '邵阳统计月报数据',
       '统计月报附件 Excel 结构化指标：期间 x 区县 x 指标，含绝对额/增速/排名；已整理去重（stat_monthly）',
       'MYSQL', 'ai_agent_data', 1, '统计月报', 'stat_monthly', 5, 2
WHERE NOT EXISTS (SELECT 1 FROM dataset WHERE table_name = 'stat_monthly');

SET @dsid = (SELECT id FROM dataset WHERE table_name = 'stat_monthly');

-- 2) 数据表
INSERT INTO table_schema (dataset_id, table_name, table_comment, status, relation_desc, sort)
SELECT @dsid, 'stat_monthly', '统计月报结构化指标长表：期间×指标×区县，一行一指标（85 份月报卡整理去重）', 1, 'period/region/indicator_name 一行一指标', 1
WHERE NOT EXISTS (SELECT 1 FROM table_schema WHERE table_name = 'stat_monthly');

SET @tid = (SELECT id FROM table_schema WHERE table_name = 'stat_monthly');

-- 3) 字段语义
INSERT INTO table_field (table_id, field_name, field_type, field_comment, business_meaning, is_metric, semantic_type, can_query, can_agg, sort)
SELECT @tid, f.field_name, f.field_type, f.field_comment, f.business_meaning, f.is_metric, f.semantic_type, f.can_query, f.can_agg, f.sort
FROM (
  SELECT 'period' field_name, 'varchar(50)' field_type, '期间' field_comment, '统计期间：2024年1-12月/2024年1-3月/2024年 等' business_meaning, 0 is_metric, '维度' semantic_type, 1 can_query, 1 can_agg, 1 sort
  UNION ALL SELECT 'region', 'varchar(50)', '区县', '全市 + 13 区县（含市辖区/市本级）', 0, '维度', 1, 1, 2
  UNION ALL SELECT 'indicator_code', 'varchar(200)', '指标编码', '指标名去空白编码', 0, '维度', 1, 0, 3
  UNION ALL SELECT 'indicator_name', 'varchar(300)', '指标名称', '分县（市、区）GDP/产业投资/规模工业增加值 等', 0, '维度', 1, 1, 4
  UNION ALL SELECT 'value', 'decimal(20,4)', '指标数值', '绝对额（单位见 unit，GDP/财政/进出口为万元、金融/消费为亿元、收入为元）', 1, '指标', 1, 1, 5
  UNION ALL SELECT 'unit', 'varchar(50)', '单位', '万元/亿元/元/名 等，同指标跨期单位可能不同，换算前先看 unit', 0, '维度', 1, 0, 6
  UNION ALL SELECT 'growth_rate', 'decimal(12,2)', '增速(%)', '累计同比增速/累计比（部分指标仅有增速无绝对值，如产业投资）', 1, '指标', 1, 1, 7
  UNION ALL SELECT 'sheet_name', 'varchar(200)', '来源工作表', '月报卡内工作表名（核算/工业/投资/贸外/财政/收入 等）', 0, '维度', 1, 0, 8
  UNION ALL SELECT 'stat_doc_id', 'bigint', '来源文档', 'stat_doc 表文档 ID（幂等唯一键组成部分）', 0, '维度', 0, 0, 9
) f
WHERE NOT EXISTS (SELECT 1 FROM table_field WHERE table_id = @tid AND field_name = f.field_name);

-- 4) 指标口径（已由 stat-metric-definition.sql 225 条对齐口径取代，2026-08-20 停用；重跑本文件不再重建旧口径）
/*
-- 4) 指标口径（基于 stat_monthly 实测形态）
INSERT INTO metric_definition (name, description, calculation_formula, table_id, field_id, status, dataset_id, metric_code, metric_type, sql_expression, sort)
SELECT m.name, m.description, m.calculation_formula, @tid, (SELECT id FROM table_field WHERE table_id = @tid AND field_name = 'value'), 1, @dsid, m.metric_code, m.metric_type, m.sql_expression, m.sort
FROM (
  SELECT '地区生产总值' name, '单位：亿元（重建后万元口径已折算）。13 区县+全市（2018-2025），有绝对额与增速；旧名「分县（市、区）GDP」已归一' description, 'SUM(value)' calculation_formula, 'gdp_monthly' metric_code, '基础指标' metric_type, "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='地区生产总值' AND value IS NOT NULL ORDER BY period, region" sql_expression, 1 sort
  UNION ALL SELECT '进出口', '2018-2020 海关美元口径 indicator_name=进出口（万美元）（unit=万美元）；2021+ 人民币亿元口径 indicator_name=进出口。13 区县+全市', 'SUM(value)', 'import_export_monthly', '基础指标', "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name IN ('进出口','进出口（万美元）') AND value IS NOT NULL ORDER BY period, region", 2
  UNION ALL SELECT '一般公共预算收入', '单位：万元。14 区域（13 区县+全市，2018-2025），有绝对额与增速；旧名「分县（市、区）地方一般公共预算收入」已归一', 'SUM(value)', 'fiscal_revenue_monthly', '基础指标', "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='一般公共预算收入' AND value IS NOT NULL ORDER BY period, region", 3
  UNION ALL SELECT '社会消费品零售总额', '单位：亿元。13 区县+全市（2018-2025），有绝对额与增速；分县口径同指标（region 过滤）', 'SUM(value)', 'retail_sales_monthly', '基础指标', "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='社会消费品零售总额' AND value IS NOT NULL ORDER BY period, region", 4
  UNION ALL SELECT '社会消费品零售总额', '重建后分县/全市同指标，并入 retail_sales_monthly 口径（下方幂等停用旧行）', 'SUM(value)', 'retail_sales_region_monthly', '基础指标', "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='社会消费品零售总额' AND value IS NOT NULL ORDER BY period, region", 5
  UNION ALL SELECT '各项存款', '单位：亿元。11 区域（10 区县含市辖区+全市，2025 年 1-2月~1-9月）；旧名「金融机构本外币存款余额」已归一', 'SUM(value)', 'deposit_balance_monthly', '基础指标', "SELECT period, region, value, unit FROM stat_monthly WHERE indicator_name='各项存款' AND value IS NOT NULL ORDER BY period, region", 6
  UNION ALL SELECT '各项贷款', '单位：亿元。11 区域（10 区县含市辖区+全市，2025 年 1-2月~1-9月）；旧名「金融机构本外币贷款余额」已归一', 'SUM(value)', 'loan_balance_monthly', '基础指标', "SELECT period, region, value, unit FROM stat_monthly WHERE indicator_name='各项贷款' AND value IS NOT NULL ORDER BY period, region", 7
  UNION ALL SELECT '城镇居民人均可支配收入', '单位：元。13 区县+全市，有绝对额与增速', 'SUM(value)', 'urban_income_monthly', '基础指标', "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='城镇居民人均可支配收入' AND value IS NOT NULL ORDER BY period, region", 8
  UNION ALL SELECT '农村居民人均可支配收入', '单位：元。13 区县+全市，有绝对额与增速', 'SUM(value)', 'rural_income_monthly', '基础指标', "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='农村居民人均可支配收入' AND value IS NOT NULL ORDER BY period, region", 9
  UNION ALL SELECT '产业投资', '仅增速（value 为空），无绝对值。13 区县+全市（2018-2025，2025 部分期间未发布）；旧名「分县（市、区）产业投资增速」已归一', 'AVG(growth_rate)', 'industry_invest_growth_monthly', '基础指标', "SELECT period, region, growth_rate FROM stat_monthly WHERE indicator_name='产业投资' ORDER BY period, region", 10
  UNION ALL SELECT '规模工业增加值', '仅增速（value 为空），无绝对值。14 区域（13 区县+全市）；旧名「分县（市、区）规模工业增加值增速」已归一', 'AVG(growth_rate)', 'industry_add_value_growth_monthly', '基础指标', "SELECT period, region, growth_rate FROM stat_monthly WHERE indicator_name='规模工业增加值' ORDER BY period, region", 11
) m
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE metric_code = m.metric_code);

*/
-- 5) AI 分析配置：STAT_TREND / STAT -> stat_monthly
INSERT INTO analysis_plan_config (intent_code, plan_type, table_name, metrics, dimensions, chart_type, time_range, sql_template, status)
SELECT 'STAT_TREND', 'STAT', 'stat_monthly', '地区生产总值（GDP）,增速', '期间,区县', 'line', '近3年',
       "SELECT DISTINCT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='地区生产总值' AND value IS NOT NULL ORDER BY period, region",
       1
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_TREND' AND plan_type = 'STAT');

-- 6) 补齐 STAT_RANKING / GOV 变体 -> stat_monthly，停用 stat_indicator 老配置（三源分离收口）
INSERT INTO analysis_plan_config (intent_code, plan_type, table_name, metrics, dimensions, chart_type, time_range, sql_template, status)
SELECT 'STAT_RANKING', 'STAT', 'stat_monthly', '地区生产总值（GDP）,增速', '区县', 'bar', '最新期间',
       "SELECT region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name = '地区生产总值' AND region <> '全市' AND period = (SELECT period FROM stat_monthly WHERE indicator_name = '地区生产总值' AND value IS NOT NULL ORDER BY CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) DESC, CAST(SUBSTRING(period, LOCATE('-', CONCAT(period, '-')) + 1, 2) AS UNSIGNED) DESC LIMIT 1) ORDER BY value DESC",
       1
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_RANKING' AND plan_type = 'STAT');

INSERT INTO analysis_plan_config (intent_code, plan_type, table_name, metrics, dimensions, chart_type, time_range, sql_template, status)
SELECT 'STAT_RANKING', 'GOV', 'stat_monthly', '地区生产总值（GDP）,增速', '区县', 'bar', '最新期间',
       "SELECT region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name = '地区生产总值' AND region <> '全市' AND period = (SELECT period FROM stat_monthly WHERE indicator_name = '地区生产总值' AND value IS NOT NULL ORDER BY CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) DESC, CAST(SUBSTRING(period, LOCATE('-', CONCAT(period, '-')) + 1, 2) AS UNSIGNED) DESC LIMIT 1) ORDER BY value DESC",
       1
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_RANKING' AND plan_type = 'GOV' AND status = 1);

INSERT INTO analysis_plan_config (intent_code, plan_type, table_name, metrics, dimensions, chart_type, time_range, sql_template, status)
SELECT 'STAT_TREND', 'GOV', 'stat_monthly', '地区生产总值（GDP）,增速', '期间,区县', 'line', '近3年',
       "SELECT DISTINCT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='地区生产总值' AND value IS NOT NULL ORDER BY period, region",
       1
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_TREND' AND plan_type = 'GOV' AND status = 1);

-- 停用 stat_indicator 老配置（24/25/26/27），避免 AI 路由回混合老表
UPDATE analysis_plan_config SET status = 0 WHERE intent_code IN ('STAT_TREND', 'STAT_RANKING') AND table_name = 'stat_indicator';

-- 幂等修正已插入行的模板（避开 R6 黑名单函数 REPLACE）
UPDATE analysis_plan_config
SET sql_template = "SELECT region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name = '地区生产总值' AND region <> '全市' AND period = (SELECT period FROM stat_monthly WHERE indicator_name = '地区生产总值' AND value IS NOT NULL ORDER BY CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) DESC, CAST(SUBSTRING(period, LOCATE('-', CONCAT(period, '-')) + 1, 2) AS UNSIGNED) DESC LIMIT 1) ORDER BY value DESC"
WHERE intent_code = 'STAT_RANKING' AND table_name = 'stat_monthly';


-- 幂等修正 STAT_TREND 行模板带 unit（供 LLM 解读识别单位）
UPDATE analysis_plan_config
SET sql_template = "SELECT DISTINCT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='地区生产总值' AND value IS NOT NULL ORDER BY period, region"
WHERE intent_code = 'STAT_TREND' AND table_name = 'stat_monthly';
-- 幂等修正 STAT_TREND 行模板带 unit（供 LLM 解读识别单位）
UPDATE analysis_plan_config
SET sql_template = "SELECT DISTINCT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='地区生产总值' AND value IS NOT NULL ORDER BY period, region"
WHERE intent_code = 'STAT_TREND' AND table_name = 'stat_monthly';

-- 7) 补齐 STAT STRUCTURE（产业占比）-> stat_monthly（2026-08-18：数据集路由修复后，STRUCTURE 意图命中该表模板，不再回退政务/订单）
INSERT INTO analysis_plan_config (intent_code, plan_type, table_name, metrics, dimensions, chart_type, time_range, sql_template, status)
SELECT 'STRUCTURE', 'STAT', 'stat_monthly', '第一产业增加值,第二产业增加值,第三产业增加值', '产业', 'pie', '最新期间',
       "SELECT indicator_name AS industry, value, unit FROM stat_monthly WHERE indicator_name IN ('第一产业增加值','第二产业增加值','第三产业增加值') AND region = '全市' AND value IS NOT NULL AND period = (SELECT period FROM stat_monthly WHERE indicator_name IN ('第一产业增加值','第二产业增加值','第三产业增加值') AND region = '全市' AND value IS NOT NULL ORDER BY CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) DESC, CAST(SUBSTRING(period, LOCATE('-', CONCAT(period, '-')) + 1, 2) AS UNSIGNED) DESC LIMIT 1) ORDER BY value DESC",
       1
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STRUCTURE' AND plan_type = 'STAT' AND table_name = 'stat_monthly');


-- 8) stat_monthly 指标口径：三次产业增加值（含去重提示，供 LLM SQL 生成参考；源表同期间同产业存在核算/投资分表多行）
INSERT INTO metric_definition (name, description, calculation_formula, status, dataset_id, metric_code, metric_type, sql_expression, sort)
SELECT '三次产业增加值', '重建后拆为三项：indicator_name IN (第一产业增加值/第二产业增加值/第三产业增加值)，region=全市，value IS NOT NULL。示例见 sql_expression',
       'SUM(value)', 1,
       (SELECT id FROM dataset WHERE table_name = 'stat_monthly'), 'industry_structure_monthly', '基础指标',
       "SELECT indicator_name, value, unit FROM stat_monthly WHERE indicator_name IN ('第一产业增加值','第二产业增加值','第三产业增加值') AND region='全市' AND value IS NOT NULL AND period=(SELECT MAX(period) FROM stat_monthly WHERE indicator_name IN ('第一产业增加值','第二产业增加值','第三产业增加值') AND region='全市' AND value IS NOT NULL) ORDER BY indicator_name",
       12
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE metric_code = 'industry_structure_monthly');

-- 9) stat_monthly 指标口径：地区生产总值别名（重建后已归一到 indicator_name=地区生产总值；本行种子后停用，避免与 gdp_monthly 重复）
INSERT INTO metric_definition (name, description, calculation_formula, status, dataset_id, metric_code, metric_type, sql_expression, sort)
SELECT '地区生产总值', '重建后「地区生产总值（GDP）」「分县（市、区）GDP」已归一为 indicator_name=地区生产总值，并入 gdp_monthly 口径（下方幂等停用本行避免重复）',
       'SUM(value)', 1, (SELECT id FROM dataset WHERE table_name = ''stat_monthly''), 'gdp_alias_monthly', '基础指标',
       "SELECT period, region, value, growth_rate, unit FROM stat_monthly WHERE indicator_name='地区生产总值' AND value IS NOT NULL ORDER BY period, region",
       13
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE metric_code = ''gdp_alias_monthly'');

-- 10) 重建后停用合并口径：分县社零并入「社会消费品零售总额」、GDP 别名并入「地区生产总值」（与 2026-08-19 stat_monthly 重建后 DB 同步）
UPDATE metric_definition SET status = 0,
       description = '重建后分县/全市同指标（indicator_name=社会消费品零售总额），已并入 retail_sales_monthly 口径，停用避免重复。'
WHERE metric_code = 'retail_sales_region_monthly' AND dataset_id = @dsid;
UPDATE metric_definition SET status = 0,
       description = '重建后「地区生产总值（GDP）」「分县（市、区）GDP」已归一为 indicator_name=地区生产总值，并入 gdp_monthly 口径，停用避免重复。'
WHERE metric_code = 'gdp_alias_monthly' AND dataset_id = @dsid;

-- 3b) 字段语义刷新（2026-08-20 数据重处理后对齐规范口径；幂等，仅更新已存在行）
SET @tid2 = (SELECT id FROM table_schema WHERE table_name = 'stat_monthly');
UPDATE table_field f SET f.business_meaning = CASE f.field_name
  WHEN 'region' THEN '全市 + 各区县（37 个区域口径，含经开区/市辖区等历史残留），region 精确匹配'
  WHEN 'indicator_name' THEN '规范指标名（2026-08-20 重处理归一，与 stat_indicator_category 树叶子一致，如 地区生产总值/各项存款/公路(万吨)），精确匹配取值过滤，禁止 LIKE'
  WHEN 'value' THEN '绝对额（单位见 unit；重处理后同指标跨期单位已统一：GDP/财政/进出口/金融/消费=亿元、收入=元、其他见 unit）'
  WHEN 'unit' THEN '标准化单位（亿元/万元/元/个/名/% 等），2026-08-20 重处理后同指标跨期单位一致'
  WHEN 'growth_rate' THEN '累计同比增速；部分指标仅有增速无绝对值（value 为空，如部分投资/排名类）'
  ELSE f.business_meaning END
WHERE f.table_id = @tid2 AND f.field_name IN ('region','indicator_name','value','unit','growth_rate');
