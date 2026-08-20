-- =============================================================
-- 2026-08-19 S1 数据治理 + 指标口径补齐
-- 1) stat_indicator 去重（同源 3 倍重复，保留每组最早一条）
-- 2) metric_definition(dataset 23 stat_monthly) 补齐完整口径
-- 幂等：去重可重跑（重复已清后无影响）；口径用 UPDATE / WHERE NOT EXISTS
-- =============================================================

-- ---------- S1-1 备份 ----------
CREATE TABLE IF NOT EXISTS stat_indicator_bak_20260819_s1 AS SELECT * FROM stat_indicator;

-- ---------- S1-2 去重（业务键：period+region+indicator_name+value+unit；同值不同增速/来源属半重复，优先保留专项收入 sheet）----------
DROP TEMPORARY TABLE IF EXISTS _si_keep;
CREATE TEMPORARY TABLE _si_keep AS
SELECT id FROM (
  SELECT s.id,
         ROW_NUMBER() OVER (PARTITION BY period, region, indicator_name, value, unit
                            ORDER BY (sheet_name IN ('全体收入49','城镇收入50','农村收入51')) DESC, id ASC) AS rn
  FROM stat_indicator s
) t WHERE rn = 1;
DELETE FROM stat_indicator WHERE id NOT IN (SELECT id FROM _si_keep);

-- ---------- 口径 UPDATE（dataset 23 既有指标）----------
UPDATE metric_definition SET
  calculation_formula = 'value（该期该地区直接取值，不聚合）',
  description = '地区生产总值（亿元，全市；万元，分县区）。period 为累计期别；增速=不变价同比(%)；分县区排名按增速降序（排名越小越靠前）。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'gdp_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（亿元）',
  description = '进出口总额（亿元），增速=比上年同期累计(%)；含出口、进口分项。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'import_export_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（万元）',
  description = '地方一般公共预算收入（万元），增速=比上年同期累计(%)；分县区排名按增速降序。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'fiscal_revenue_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（亿元）',
  description = '社会消费品零售总额（亿元），增速=比上年同期累计(%)；含城镇/乡村/城区分项。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'retail_sales_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（亿元，月末余额）',
  description = '各项存款余额（亿元），月末时点余额，另有比年初增减额。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'deposit_balance_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（亿元，月末余额）',
  description = '各项贷款余额（亿元），月末时点余额，另有比年初增减额。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'loan_balance_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（元，名义值）',
  description = '城镇居民人均可支配收入（元，名义值）。period 为累计期别：1-3月=一季度累计、1-6月=上半年累计、1-9月=前三季度累计、1-12月=全年。增速=比上年同期累计名义增速(%)；区县排名按增速降序。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'urban_income_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（元，名义值）',
  description = '农村居民人均可支配收入（元，名义值）。period 为累计期别；增速=比上年同期累计名义增速(%)；区县排名按增速降序。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'rural_income_monthly';

UPDATE metric_definition SET
  calculation_formula = 'growth_rate（%，同比）',
  description = '产业投资增速=比上年同期累计(%)；分县区排名按增速降序。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'industry_invest_growth_monthly';

UPDATE metric_definition SET
  calculation_formula = 'growth_rate（%，同比）',
  description = '规模工业增加值增速=比上年同期累计(%)，绝对额存 value（亿元）。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'industry_add_value_growth_monthly';

UPDATE metric_definition SET
  calculation_formula = 'value（亿元）',
  description = '第一/二/三产业增加值（亿元），增速为实际同比增速(%)；period 为累计期别。',
  metric_type = '基础指标' WHERE dataset_id = 23 AND metric_code = 'industry_structure_monthly';

-- ---------- 口径 INSERT（缺失指标，幂等）----------
INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'all_income_monthly', '全体居民人均可支配收入', '基础指标', 'value（元，名义值）',
  '全体居民人均可支配收入（元，名义值），与 Excel「全市居民人均可支配收入」同义。period 为累计期别；增速=比上年同期累计名义增速(%)；区县排名按增速降序。',
  1, 9 WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'all_income_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'income_urban_rural_ratio', '城乡收入比', '计算指标', '城镇居民人均可支配收入 ÷ 农村居民人均可支配收入（同地区同期别）',
  '城乡收入比 = 城镇居民人均可支配收入 ÷ 农村居民人均可支配收入（同地区同期别）。严禁用全体居民作分母；2025年1-9月全市约 30402.30/14314.07 ≈ 2.12 倍。',
  1, 10 WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'income_urban_rural_ratio');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'urban_income_rank_monthly', '城镇居民人均可支配收入排名', '计算指标', 'value（名）',
  '区县排名（名，越小越靠前），按增速降序；与「城镇居民人均可支配收入」同 period 同 region 行。', 1, 11
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'urban_income_rank_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'rural_income_rank_monthly', '农村居民人均可支配收入排名', '计算指标', 'value（名）',
  '区县排名（名，越小越靠前），按增速降序；与「农村居民人均可支配收入」同 period 同 region 行。', 1, 12
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'rural_income_rank_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'all_income_rank_monthly', '全体居民人均可支配收入排名', '计算指标', 'value（名）',
  '区县排名（名，越小越靠前），按增速降序；与「全体居民人均可支配收入」同 period 同 region 行。', 1, 13
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'all_income_rank_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'consume_expense_monthly', '全市居民人均消费支出', '基础指标', 'value（元）',
  '全体居民人均消费支出（元），累计期别，增速=同比(%)。', 1, 14
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'consume_expense_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'urban_consume_monthly', '城镇居民人均生活消费支出', '基础指标', 'value（元）',
  '城镇居民人均生活消费支出（元），累计期别，增速=同比(%)。', 1, 15
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'urban_consume_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'rural_consume_monthly', '农村居民人均生活消费支出', '基础指标', 'value（元）',
  '农村居民人均生活消费支出（元），累计期别，增速=同比(%)。', 1, 16
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'rural_consume_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'industry_sector_growth_monthly', '规模工业大类行业增加值', '基础指标', 'growth_rate（%，同比）',
  '规模工业 35 个大类行业增加值增速=比上年同期累计(%)。', 1, 21
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'industry_sector_growth_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'industry_profit_monthly', '规模工业利润总额', '基础指标', 'value（万元）',
  '规模工业利润总额（万元），增速=同比(%)；注意部分期别为 1-8月 累计。', 1, 22
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'industry_profit_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'industry_sales_rate_monthly', '规模工业产销率', '基础指标', 'value（%）',
  '规模工业产销率（%），另有同比增减百分点。', 1, 23
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'industry_sales_rate_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'fixed_asset_invest_monthly', '固定资产投资', '基础指标', 'growth_rate（%，同比）',
  '固定资产投资增速=比上年同期累计(%)；分县区排名按增速降序。', 1, 31
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'fixed_asset_invest_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'infra_invest_monthly', '基础设施建设投资', '基础指标', 'value/growth_rate',
  '基础设施建设投资（绝对额或同比增速），period 为累计期别。', 1, 32
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'infra_invest_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'real_estate_invest_monthly', '房地产开发投资', '基础指标', 'value/growth_rate',
  '房地产开发投资（绝对额或同比增速），累计期别。', 1, 33
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'real_estate_invest_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'high_tech_invest_monthly', '高技术产业投资', '基础指标', 'value/growth_rate',
  '高技术产业投资（绝对额或同比增速），累计期别。', 1, 34
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'high_tech_invest_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'retail_above_limit_monthly', '限额以上零售额', '基础指标', 'value（亿元）',
  '限额以上单位零售额（亿元），增速=同比(%)，累计期别。', 1, 42
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'retail_above_limit_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'finance_deposit_monthly', '金融机构本外币存款余额', '基础指标', 'value（亿元，月末余额）',
  '金融机构本外币存款余额（亿元），月末时点，另有比年初增减额。', 1, 51
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'finance_deposit_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'finance_loan_monthly', '金融机构本外币贷款余额', '基础指标', 'value（亿元，月末余额）',
  '金融机构本外币贷款余额（亿元），月末时点，另有比年初增减额。', 1, 52
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'finance_loan_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'cpi_monthly', '居民消费价格指数', '基础指标', 'value（指数）',
  '居民消费价格指数（上年同期=100）；累计值口径（如 99.3 = 累计同比下降 0.7%）。', 1, 56
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'cpi_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'rpi_monthly', '商品零售价格指数', '基础指标', 'value（指数）',
  '商品零售价格指数（上年同期=100）；累计值口径。', 1, 57
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'rpi_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'export_monthly', '出口', '基础指标', 'value（亿元）',
  '出口总额（亿元），增速=比上年同期累计(%)。', 1, 59
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'export_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'import_monthly', '进口', '基础指标', 'value（亿元）',
  '进口总额（亿元），增速=比上年同期累计(%)。', 1, 60
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'import_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'fdi_monthly', '外商直接投资', '基础指标', 'value（万美元）',
  '外商直接投资（万美元），增速=比上年同期累计(%)。', 1, 61
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'fdi_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'actual_foreign_capital_monthly', '实际利用境外资金', '基础指标', 'value（万美元）',
  '实际利用境外资金（万美元），增速=比上年同期累计(%)。', 1, 62
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'actual_foreign_capital_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'fiscal_expense_monthly', '一般公共预算支出', '基础指标', 'value（万元）',
  '一般公共预算支出（万元），增速=比上年同期累计(%)。', 1, 46
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'fiscal_expense_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'tax_income_monthly', '税收收入', '基础指标', 'value（万元）',
  '税收收入（万元），增速=比上年同期累计(%)。', 1, 47
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'tax_income_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'non_tax_income_monthly', '非税收入', '基础指标', 'value（万元）',
  '非税收入（万元），增速=比上年同期累计(%)。', 1, 48
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'non_tax_income_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'electricity_monthly', '全市用电总量', '基础指标', 'value（亿千瓦小时）',
  '全市用电总量（亿千瓦小时），增速=同比(%)；含工业用电量分项。', 1, 66
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'electricity_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'passenger_monthly', '全社会客运量', '基础指标', 'value（万人）',
  '全社会客运量（万人），增速=同比(%)。', 1, 67
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'passenger_monthly');

INSERT INTO metric_definition (dataset_id, metric_code, name, metric_type, calculation_formula, description, status, sort)
SELECT 23, 'freight_monthly', '全社会货运量', '基础指标', 'value（万吨）',
  '全社会货运量（万吨），增速=同比(%)。', 1, 68
WHERE NOT EXISTS (SELECT 1 FROM metric_definition WHERE dataset_id = 23 AND metric_code = 'freight_monthly');
-- ---------- S1-3 精度变体清理（同业务键 0.01 内取整变体保留精度最高，窗口函数一次扫描）----------
DROP TEMPORARY TABLE IF EXISTS _si_clean;
CREATE TEMPORARY TABLE _si_clean AS
WITH base AS (
  SELECT id, period, region, indicator_name, unit, value,
         LENGTH(TRIM(TRAILING '0' FROM SUBSTRING_INDEX(CAST(value AS CHAR), '.', -1))) AS prec
  FROM stat_indicator
),
ord AS (
  SELECT b.*,
         LAG(value) OVER (PARTITION BY period, region, indicator_name, unit ORDER BY value) AS prev_value
  FROM base b
),
clustered AS (
  SELECT o.*,
         SUM(CASE WHEN prev_value IS NULL OR value - prev_value > 0.01 THEN 1 ELSE 0 END)
           OVER (PARTITION BY period, region, indicator_name, unit ORDER BY value) AS cl
  FROM ord o
)
SELECT id FROM (
  SELECT c.*, ROW_NUMBER() OVER (PARTITION BY period, region, indicator_name, unit, cl ORDER BY prec DESC, id ASC) AS rn
  FROM clustered c
) t WHERE rn = 1;
DELETE FROM stat_indicator WHERE id NOT IN (SELECT id FROM _si_clean);