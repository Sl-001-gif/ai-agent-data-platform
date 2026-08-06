-- AI 引擎接线：邵阳统计指标库（stat_indicator）问数配置（幂等，可重复执行）
-- 说明：仅新增意图规则与计划配置，不改现有配置；回退=删除本文件新增行即可

-- 1) 意图规则：区县指标排名（优先级 0，先于现有 RANKING/SALES_TREND）
INSERT INTO analysis_intent_rule (intent_code, intent_name, keywords, priority, status)
SELECT 'STAT_RANKING', '区县指标排名',
'区县财政收入,县市区财政收入,财政收入排名,区县排名,县市区排名,区县gdp,县市区gdp,gdp排名,经济排名,区县经济,县市区经济,指标排名',
0, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM analysis_intent_rule WHERE intent_code = 'STAT_RANKING');

-- 2) 意图规则：统计指标趋势（优先级 1，先于 SALES_TREND=7）
INSERT INTO analysis_intent_rule (intent_code, intent_name, keywords, priority, status)
SELECT 'STAT_TREND', '统计指标趋势',
'gdp,生产总值,财政收入,规上工业,规模以上工业,工业增加值,居民收入,社会消费品零售,固定资产投资,统计月报,统计公报,统计分析,统计局,经济指标,经济趋势,经济数据,经济总量,经济运行,经济形势,经济,增速,增幅',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM analysis_intent_rule WHERE intent_code = 'STAT_TREND');

-- 3) 计划配置：STAT_TREND（非政务关键词提问，如"2025年GDP趋势"）
INSERT INTO analysis_plan_config (intent_code, is_gov, table_name, metrics, dimensions, chart_type, time_range, sql_template, status, sort)
SELECT 'STAT_TREND', 0, 'stat_indicator', '地区生产总值（GDP）,增速', '期间,区县', 'line', '近3年',
'SELECT period, value, growth_rate FROM stat_indicator WHERE indicator_name = ''地区生产总值（GDP）'' AND region = ''全市'' AND unit = ''亿元'' ORDER BY period',
1, 10
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_TREND' AND is_gov = 0);

-- 4) 计划配置：STAT_TREND（含"邵阳"等政务关键词提问）
INSERT INTO analysis_plan_config (intent_code, is_gov, table_name, metrics, dimensions, chart_type, time_range, sql_template, status, sort)
SELECT 'STAT_TREND', 1, 'stat_indicator', '地区生产总值（GDP）,增速', '期间,区县', 'line', '近3年',
'SELECT period, value, growth_rate FROM stat_indicator WHERE indicator_name = ''地区生产总值（GDP）'' AND region = ''全市'' AND unit = ''亿元'' ORDER BY period',
1, 10
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_TREND' AND is_gov = 1);

-- 5) 计划配置：STAT_RANKING（非政务关键词提问）
INSERT INTO analysis_plan_config (intent_code, is_gov, table_name, metrics, dimensions, chart_type, time_range, sql_template, status, sort)
SELECT 'STAT_RANKING', 0, 'stat_indicator', '地方一般公共预算收入,排名', '区县', 'bar', '最新期间',
'SELECT region, value, growth_rate FROM stat_indicator WHERE indicator_name = ''地方一般公共预算收入'' AND region <> ''全市'' AND unit = ''万元'' ORDER BY value DESC LIMIT 13',
1, 10
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_RANKING' AND is_gov = 0);

-- 6) 计划配置：STAT_RANKING（含"邵阳"等政务关键词提问）
INSERT INTO analysis_plan_config (intent_code, is_gov, table_name, metrics, dimensions, chart_type, time_range, sql_template, status, sort)
SELECT 'STAT_RANKING', 1, 'stat_indicator', '地方一般公共预算收入,排名', '区县', 'bar', '最新期间',
'SELECT region, value, growth_rate FROM stat_indicator WHERE indicator_name = ''地方一般公共预算收入'' AND region <> ''全市'' AND unit = ''万元'' ORDER BY value DESC LIMIT 13',
1, 10
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_config WHERE intent_code = 'STAT_RANKING' AND is_gov = 1);