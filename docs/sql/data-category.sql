-- =============================================
-- 数据分类（data_category）——幂等可重复执行
-- 用途：管理后台「数据元配置」Tab 页签分类，以数据集为单位打分类
-- =============================================
USE ai_agent_data;

CREATE TABLE IF NOT EXISTS data_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE COMMENT '分类名称',
    color VARCHAR(20) DEFAULT '#409eff' COMMENT '标签颜色',
    sort INT DEFAULT 0 COMMENT '排序',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据分类表';

-- dataset.category_id（幂等：列不存在才加）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'category_id');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE dataset ADD COLUMN category_id BIGINT NULL COMMENT ''所属分类 ID（data_category.id）''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 种子分类（幂等：按 name 不存在才插入）
INSERT INTO data_category (name, color, sort)
SELECT t.name, t.color, t.sort FROM (
    SELECT '政务数据' AS name, '#409eff' AS color, 1 AS sort
    UNION ALL SELECT '统计数据', '#67c23a', 2
    UNION ALL SELECT '演示数据', '#e6a23c', 3
) t
WHERE NOT EXISTS (SELECT 1 FROM data_category dc WHERE dc.name = t.name);

-- 现有数据集自动归集（幂等：只填空位）
UPDATE dataset d
SET d.category_id = (SELECT c.id FROM data_category c WHERE c.name = '政务数据')
WHERE d.name = '邵阳政务信息公开数据' AND d.category_id IS NULL;

UPDATE dataset d
SET d.category_id = (SELECT c.id FROM data_category c WHERE c.name = '统计数据')
WHERE d.name = '邵阳统计指标数据' AND d.category_id IS NULL;

UPDATE dataset d
SET d.category_id = (SELECT c.id FROM data_category c WHERE c.name = '演示数据')
WHERE d.name IN ('订单销售数据集','用户增长与留存数据集','产品销售数据集') AND d.category_id IS NULL;