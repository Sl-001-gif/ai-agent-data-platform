-- 测试表元数据配置：order_info / user_info / product_info（2026-08-03，幂等可重复执行）
SET NAMES utf8mb4;

-- 1. 修正订单数据集 table_name 的 tab 瑕疵
UPDATE dataset SET table_name = 'order_info' WHERE id = 6 AND table_name LIKE '%order_info%';

-- 2. 确保三个数据集存在（幂等）
INSERT INTO dataset (name, description, db_type, db_host, db_port, db_name, status, business_scene, table_name, sort)
SELECT '订单销售数据集','订单明细（日期/区域/渠道/品类/数量/金额），用于销售分析演示','MYSQL',NULL,3306,'ai_agent_data',1,'销售分析','order_info',1
WHERE NOT EXISTS (SELECT 1 FROM dataset WHERE table_name = 'order_info');

INSERT INTO dataset (name, description, db_type, db_host, db_port, db_name, status, business_scene, table_name, sort)
SELECT '用户增长与留存数据集','用户注册与活跃明细（注册日期/年龄段/城市/新增/活跃/留存率），用于用户增长与留存分析演示','MYSQL',NULL,3306,'ai_agent_data',1,'用户分析','user_info',2
WHERE NOT EXISTS (SELECT 1 FROM dataset WHERE table_name = 'user_info');

INSERT INTO dataset (name, description, db_type, db_host, db_port, db_name, status, business_scene, table_name, sort)
SELECT '产品销售数据集','商品销售明细（品类/品牌/销量/销售额），用于产品销售结构分析演示','MYSQL',NULL,3306,'ai_agent_data',1,'商品分析','product_info',3
WHERE NOT EXISTS (SELECT 1 FROM dataset WHERE table_name = 'product_info');

-- 3. 幂等清理旧配置（防重复）
DELETE FROM metric_definition WHERE dataset_id IN (SELECT id FROM dataset WHERE table_name IN ('order_info','user_info','product_info'));
DELETE FROM table_field WHERE table_id IN (SELECT id FROM table_schema WHERE table_name IN ('order_info','user_info','product_info'));
DELETE FROM table_schema WHERE table_name IN ('order_info','user_info','product_info');

-- 4. 表结构
INSERT INTO table_schema (dataset_id, table_name, table_comment, status, relation_desc, sort)
SELECT id, table_name,
       CASE table_name WHEN 'order_info' THEN '订单销售明细（按日期/区域/渠道/品类聚合）'
                       WHEN 'user_info' THEN '用户注册与活跃留存明细（按日期/年龄段/城市聚合）'
                       ELSE '商品销售明细（按品类/品牌聚合）' END,
       1, '演示数据集，用于 AI 分析链路验证', 1
FROM dataset WHERE table_name IN ('order_info','user_info','product_info');

-- 5. 字段语义（order_info 7 字段）
INSERT INTO table_field (table_id, field_name, field_type, field_comment, business_meaning, is_metric, semantic_type, can_query, can_agg, sort)
SELECT id, 'order_date', 'DATE', '订单日期', '订单发生的日期，用于时间维度聚合（日/周/月/年）', 0, '维度', 1, 1, 1 FROM table_schema WHERE table_name = 'order_info'
UNION ALL SELECT id, 'region', 'VARCHAR(50)', '区域', '订单所属销售区域（如 华东/华南），用于区域对比', 0, '维度', 1, 1, 2 FROM table_schema WHERE table_name = 'order_info'
UNION ALL SELECT id, 'channel', 'VARCHAR(50)', '渠道', '订单销售渠道（如 线上/线下），用于渠道对比', 0, '维度', 1, 1, 3 FROM table_schema WHERE table_name = 'order_info'
UNION ALL SELECT id, 'category', 'VARCHAR(50)', '品类', '商品品类（如 食品/数码），用于品类结构分析', 0, '维度', 1, 1, 4 FROM table_schema WHERE table_name = 'order_info'
UNION ALL SELECT id, 'order_count', 'INT', '订单量', '该维度组合下的订单笔数，口径=SUM(order_count)', 1, '指标', 1, 1, 5 FROM table_schema WHERE table_name = 'order_info'
UNION ALL SELECT id, 'sales_amount', 'DECIMAL(12,2)', '销售额', '该维度组合下的销售金额，口径=SUM(sales_amount)', 1, '指标', 1, 1, 6 FROM table_schema WHERE table_name = 'order_info'
UNION ALL SELECT id, 'sales_volume', 'INT', '销售件数', '该维度组合下的售出商品件数，口径=SUM(sales_volume)', 1, '指标', 1, 1, 7 FROM table_schema WHERE table_name = 'order_info';

-- 6. 字段语义（user_info 6 字段）
INSERT INTO table_field (table_id, field_name, field_type, field_comment, business_meaning, is_metric, semantic_type, can_query, can_agg, sort)
SELECT id, 'register_date', 'DATE', '注册日期', '用户注册日期，用于时间维度聚合与留存批次划分', 0, '维度', 1, 1, 1 FROM table_schema WHERE table_name = 'user_info'
UNION ALL SELECT id, 'age_group', 'VARCHAR(50)', '年龄段', '用户年龄段分组（如 18-24/25-34），用于年龄结构分析', 0, '维度', 1, 1, 2 FROM table_schema WHERE table_name = 'user_info'
UNION ALL SELECT id, 'city', 'VARCHAR(50)', '城市', '用户所在城市，用于地域分布分析', 0, '维度', 1, 1, 3 FROM table_schema WHERE table_name = 'user_info'
UNION ALL SELECT id, 'new_user_count', 'INT', '新增用户数', '该维度组合下的新增注册用户数，口径=SUM(new_user_count)', 1, '指标', 1, 1, 4 FROM table_schema WHERE table_name = 'user_info'
UNION ALL SELECT id, 'active_user_count', 'INT', '活跃用户数', '该维度组合下的活跃用户数，口径=SUM(active_user_count)', 1, '指标', 1, 1, 5 FROM table_schema WHERE table_name = 'user_info'
UNION ALL SELECT id, 'retention_rate', 'DECIMAL(5,2)', '留存率(%)', '注册用户次月留存率（百分比数值），按批次统计，直接 AVG 为近似口径', 1, '指标', 1, 1, 6 FROM table_schema WHERE table_name = 'user_info';

-- 7. 字段语义（product_info 4 字段）
INSERT INTO table_field (table_id, field_name, field_type, field_comment, business_meaning, is_metric, semantic_type, can_query, can_agg, sort)
SELECT id, 'category', 'VARCHAR(50)', '品类', '商品品类（如 手机/家电），用于品类结构分析', 0, '维度', 1, 1, 1 FROM table_schema WHERE table_name = 'product_info'
UNION ALL SELECT id, 'brand', 'VARCHAR(50)', '品牌', '商品品牌，用于品牌对比与市场份额分析', 0, '维度', 1, 1, 2 FROM table_schema WHERE table_name = 'product_info'
UNION ALL SELECT id, 'sales_volume', 'INT', '销量', '该维度组合下的销售件数，口径=SUM(sales_volume)', 1, '指标', 1, 1, 3 FROM table_schema WHERE table_name = 'product_info'
UNION ALL SELECT id, 'sales_amount', 'DECIMAL(12,2)', '销售额', '该维度组合下的销售金额，口径=SUM(sales_amount)', 1, '指标', 1, 1, 4 FROM table_schema WHERE table_name = 'product_info';

-- 8. 指标口径（order_info）
INSERT INTO metric_definition (name, description, calculation_formula, table_id, status, dataset_id, metric_code, metric_type, sql_expression, sort)
SELECT '订单量','订单销售总笔数（口径：全表/分组 SUM(order_count)）','SELECT SUM(order_count) AS cnt FROM order_info', ts.id, 1, d.id, 'order_count_total', '基础指标', 'SELECT SUM(order_count) AS cnt FROM order_info', 1
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'order_info'
UNION ALL
SELECT '销售额','订单销售总金额（口径：全表/分组 SUM(sales_amount)）','SELECT SUM(sales_amount) AS amt FROM order_info', ts.id, 1, d.id, 'sales_amount_total', '基础指标', 'SELECT SUM(sales_amount) AS amt FROM order_info', 2
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'order_info'
UNION ALL
SELECT '客单价','平均每笔订单的销售金额（口径：SUM(sales_amount)/SUM(order_count)，防除零）','SELECT ROUND(SUM(sales_amount)/NULLIF(SUM(order_count),0),2) AS avg_order_amount FROM order_info', ts.id, 1, d.id, 'avg_order_amount', '计算指标', 'SELECT ROUND(SUM(sales_amount)/NULLIF(SUM(order_count),0),2) AS avg_order_amount FROM order_info', 3
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'order_info';

-- 9. 指标口径（user_info）
INSERT INTO metric_definition (name, description, calculation_formula, table_id, status, dataset_id, metric_code, metric_type, sql_expression, sort)
SELECT '新增用户数','新增注册用户总数（口径：SUM(new_user_count)）','SELECT SUM(new_user_count) AS cnt FROM user_info', ts.id, 1, d.id, 'new_user_total', '基础指标', 'SELECT SUM(new_user_count) AS cnt FROM user_info', 1
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'user_info'
UNION ALL
SELECT '活跃用户数','活跃用户总数（口径：SUM(active_user_count)）','SELECT SUM(active_user_count) AS cnt FROM user_info', ts.id, 1, d.id, 'active_user_total', '基础指标', 'SELECT SUM(active_user_count) AS cnt FROM user_info', 2
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'user_info'
UNION ALL
SELECT '留存率','注册用户次月留存率均值（口径：数据按批次统计，AVG(retention_rate) 为近似值）','SELECT ROUND(AVG(retention_rate),2) AS avg_retention_rate FROM user_info', ts.id, 1, d.id, 'retention_rate_avg', '计算指标', 'SELECT ROUND(AVG(retention_rate),2) AS avg_retention_rate FROM user_info', 3
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'user_info';

-- 10. 指标口径（product_info）
INSERT INTO metric_definition (name, description, calculation_formula, table_id, status, dataset_id, metric_code, metric_type, sql_expression, sort)
SELECT '销量','商品销售总件数（口径：SUM(sales_volume)）','SELECT SUM(sales_volume) AS cnt FROM product_info', ts.id, 1, d.id, 'sales_volume_total', '基础指标', 'SELECT SUM(sales_volume) AS cnt FROM product_info', 1
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'product_info'
UNION ALL
SELECT '销售额','商品销售总金额（口径：SUM(sales_amount)）','SELECT SUM(sales_amount) AS amt FROM product_info', ts.id, 1, d.id, 'sales_amount_total', '基础指标', 'SELECT SUM(sales_amount) AS amt FROM product_info', 2
FROM table_schema ts JOIN dataset d ON d.table_name = ts.table_name WHERE ts.table_name = 'product_info';