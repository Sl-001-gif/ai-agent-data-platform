-- =============================================
-- 分析计划类型（analysis_plan_type）——幂等可重复执行
-- 用途：计划配置「类型」由固定 普通/政务 升级为可自定义字典（含启停状态）
-- 类型路由：GOV 保留原政务关键词路由；自定义类型按 route_keywords 命中路由；未命中回退 NORMAL
-- =============================================
USE ai_agent_data;

CREATE TABLE IF NOT EXISTS analysis_plan_type (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type_code VARCHAR(50) NOT NULL UNIQUE COMMENT '类型编码',
    type_name VARCHAR(50) NOT NULL COMMENT '类型名称',
    color VARCHAR(20) DEFAULT '#409eff' COMMENT '标签颜色',
    route_keywords VARCHAR(500) COMMENT '路由关键词，逗号分隔；空=不参与关键词路由',
    sort INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1 COMMENT '1启用 0停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析计划类型配置';

-- 种子类型（幂等：按 type_code 不存在才插入）
INSERT INTO analysis_plan_type (type_code, type_name, color, route_keywords, sort, status)
SELECT t.type_code, t.type_name, t.color, t.route_keywords, t.sort, 1 FROM (
    SELECT 'NORMAL' AS type_code, '普通' AS type_name, '#909399' AS color, '' AS route_keywords, 1 AS sort
    UNION ALL SELECT 'GOV', '政务', '#f56c6c', '政务,公开,政府,发文,邵阳,新宁', 2
    UNION ALL SELECT 'STAT', '统计', '#409eff', '统计,gdp,生产总值,财政收入,规上工业,规模以上工业,工业增加值,居民收入,社会消费品零售,固定资产投资,统计局,经济指标,增速,增幅', 3
) t
WHERE NOT EXISTS (SELECT 1 FROM analysis_plan_type pt WHERE pt.type_code = t.type_code);

-- analysis_plan_config.plan_type（幂等：列不存在才加）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_plan_config' AND COLUMN_NAME = 'plan_type');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE analysis_plan_config ADD COLUMN plan_type VARCHAR(50) DEFAULT ''NORMAL'' COMMENT ''计划类型编码（analysis_plan_type.type_code）''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量迁移 is_gov → plan_type（仅在首次加列时执行；重跑幂等跳过）
SET @col_exists2 = (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_plan_config' AND COLUMN_NAME = 'plan_type');
SET @mig = IF(@col_exists2 = 0,
    'UPDATE analysis_plan_config SET plan_type = IF(is_gov = 1, ''GOV'', ''NORMAL'')',
    'SELECT 1');
PREPARE stmt2 FROM @mig; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;