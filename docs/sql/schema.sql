-- =============================================
-- AI Agent 数据分析平台 - 数据库初始化脚本
-- =============================================

CREATE DATABASE IF NOT EXISTS ai_agent_data
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE ai_agent_data;

-- ---------------------------------------------
-- 系统用户表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    email VARCHAR(100),
    phone VARCHAR(20),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status TINYINT DEFAULT 1 COMMENT '1:启用 0:禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ---------------------------------------------
-- 数据集配置表
-- ---------------------------------------------
-- ---------------------------------------------
-- 数据分类表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS data_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE COMMENT '分类名称',
    color VARCHAR(20) DEFAULT '#409eff' COMMENT '标签颜色',
    sort INT DEFAULT 0 COMMENT '排序',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据分类表';

CREATE TABLE IF NOT EXISTS dataset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    business_scene VARCHAR(200) COMMENT '业务场景',
    table_name VARCHAR(200) COMMENT '关联主表名',
    sort INT DEFAULT 0 COMMENT '排序',
    db_type VARCHAR(20) DEFAULT 'MYSQL',
    db_host VARCHAR(255),
    db_port INT DEFAULT 3306,
    db_name VARCHAR(100),
    db_username VARCHAR(100),
    db_password VARCHAR(255),
    status TINYINT DEFAULT 1,
    category_id BIGINT NULL COMMENT '所属分类 ID（data_category.id）',
    create_by BIGINT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据集配置表';

-- ---------------------------------------------
-- 数据表结构定义表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS table_schema (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    table_name VARCHAR(200) NOT NULL,
    table_comment VARCHAR(500),
    relation_desc VARCHAR(500) COMMENT '表关系说明',
    sort INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1,
    category_id BIGINT NULL COMMENT '所属分类 ID（data_category.id）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (dataset_id) REFERENCES dataset(id) ON DELETE CASCADE,
    INDEX idx_dataset (dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表结构定义表';

-- ---------------------------------------------
-- 字段语义定义表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS table_field (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_id BIGINT NOT NULL,
    field_name VARCHAR(200) NOT NULL,
    field_type VARCHAR(50),
    field_comment VARCHAR(500),
    business_meaning TEXT COMMENT '业务含义/字段语义',
    is_metric TINYINT DEFAULT 0 COMMENT '是否为指标字段',
    semantic_type VARCHAR(20) COMMENT '语义类型：维度/指标/标识',
    can_query TINYINT DEFAULT 1 COMMENT '是否可查询',
    can_agg TINYINT DEFAULT 1 COMMENT '是否可聚合',
    sort INT DEFAULT 0 COMMENT '排序',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (table_id) REFERENCES table_schema(id) ON DELETE CASCADE,
    INDEX idx_table (table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段语义定义表';

-- ---------------------------------------------
-- 指标口径定义表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS metric_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    dataset_id BIGINT COMMENT '所属数据集ID',
    metric_code VARCHAR(100) COMMENT '指标编码',
    metric_type VARCHAR(20) DEFAULT '基础指标' COMMENT '指标类型：基础指标/计算指标',
    description TEXT,
    calculation_formula TEXT COMMENT '计算口径/SQL',
    sql_expression TEXT COMMENT 'SQL表达式',
    sort INT DEFAULT 0 COMMENT '排序',
    table_id BIGINT,
    field_id BIGINT,
    status TINYINT DEFAULT 1,
    category_id BIGINT NULL COMMENT '所属分类 ID（data_category.id）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标口径定义表';

-- ---------------------------------------------
-- 分析会话表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS analysis_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    dataset_id BIGINT,
    title VARCHAR(500),
    analysis_goal VARCHAR(500) COMMENT '分析目标',
    status VARCHAR(20) DEFAULT 'ACTIVE',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析会话表';

-- ---------------------------------------------
-- Agent 执行步骤记录表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS analysis_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    round_no INT DEFAULT 1 COMMENT '轮次',
    step_order INT DEFAULT 1,
    step_type VARCHAR(50) COMMENT 'INTENT/PLAN/SQL/VALIDATE/EXECUTE/CHART/INTERPRET/REPORT',
    input_data TEXT,
    output_data TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',
    error_message TEXT,
    duration_ms BIGINT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES analysis_session(id) ON DELETE CASCADE,
    INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行步骤记录表';

-- ---------------------------------------------
-- 分析报告表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS analysis_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    round_no INT DEFAULT 1 COMMENT '轮次',
    title VARCHAR(500),
    content LONGTEXT,
    status VARCHAR(20) DEFAULT 'DRAFT',
    create_by BIGINT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES analysis_session(id) ON DELETE CASCADE,
    INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析报告表';

-- ---------------------------------------------
-- AI 模型配置表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS ai_model_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    api_key VARCHAR(500),
    endpoint VARCHAR(500),
    max_tokens INT DEFAULT 4096,
    temperature DECIMAL(3,2) DEFAULT 0.7,
    status TINYINT DEFAULT 1,
    category_id BIGINT NULL COMMENT '所属分类 ID（data_category.id）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型配置表';

-- ---------------------------------------------
-- Prompt 模板表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS prompt_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(50) COMMENT 'INTENT/SQL/CHART/INTERPRET/RECOMMEND',
    content TEXT NOT NULL,
    version INT DEFAULT 1,
    status TINYINT DEFAULT 1,
    category_id BIGINT NULL COMMENT '所属分类 ID（data_category.id）',
    variables VARCHAR(500) NULL COMMENT '变量名逗号分隔（datasetSchema/userQuestion/originSQL）',
    sort INT DEFAULT 0 COMMENT '排序权重',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt 模板表';


-- ---------------------------------------------
-- AI 数据源配置表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS ai_data_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    db_type VARCHAR(20) DEFAULT 'MYSQL',
    host VARCHAR(100) NOT NULL,
    port INT NOT NULL DEFAULT 3306,
    database_name VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(200) NOT NULL COMMENT 'v1 演示明文存储',
    remark VARCHAR(255),
    create_by BIGINT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 数据源配置表';
-- ---------------------------------------------
-- 初始化管理员账号 (密码: admin123)
-- ---------------------------------------------
INSERT INTO sys_user (username, password, nickname, role, status)
VALUES ('admin', '$2a$10$y7OYaEl6AAAIsJy9wxzrROo7b41zJHxWlgY19fb9N20t4lnBNShPG', '系统管理员', 'ADMIN', 1);

-- ---------------------------------------------
-- 分析意图规则配置表
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS analysis_intent_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    intent_code VARCHAR(50) NOT NULL COMMENT '意图编码',
    intent_name VARCHAR(50) NOT NULL COMMENT '意图名称',
    keywords VARCHAR(500) NOT NULL COMMENT '关键词，逗号分隔',
    priority INT DEFAULT 0 COMMENT '优先级，越小越先匹配',
    status TINYINT DEFAULT 1 COMMENT '启用状态',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_intent_code (intent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析意图规则配置';

-- ---------------------------------------------
-- 分析计划配置表
-- ---------------------------------------------
-- ---------------------------------------------
-- 分析计划类型配置表
-- ---------------------------------------------
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

CREATE TABLE IF NOT EXISTS analysis_plan_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    intent_code VARCHAR(50) NOT NULL COMMENT '意图编码',
    is_gov TINYINT DEFAULT 0 COMMENT '是否政务类计划',
    plan_type VARCHAR(50) DEFAULT 'NORMAL' COMMENT '计划类型编码（analysis_plan_type.type_code）',
    table_name VARCHAR(100) NOT NULL COMMENT '目标表名',
    metrics VARCHAR(500) NOT NULL COMMENT '指标，逗号分隔',
    dimensions VARCHAR(500) NOT NULL COMMENT '维度，逗号分隔',
    chart_type VARCHAR(20) DEFAULT 'table' COMMENT '图表类型',
    time_range VARCHAR(50) DEFAULT '近30天' COMMENT '默认时间范围',
    sql_template TEXT COMMENT '规则 SQL 模板，{timeRange} 占位',
    status TINYINT DEFAULT 1,
    category_id BIGINT NULL COMMENT '所属分类 ID（data_category.id）',
    sort INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_intent (intent_code, is_gov)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析计划配置';

-- ---------------------------------------------
-- 分析意图规则种子（与内置回退逐字一致）
-- ---------------------------------------------
INSERT INTO analysis_intent_rule (intent_code, intent_name, keywords, priority, status) VALUES
('USER_PROFILE', '用户画像', '用户画像,人群画像,客户画像,画像,人群,偏好,特征', 1, 1),
('ANOMALY', '异常归因', '下降,下跌,异常,原因,归因,波动,为什么', 2, 1),
('RETENTION', '留存转化', '留存,转化,复购,流失', 3, 1),
('COMPARISON', '对比分析', '对比,比较,差异', 4, 1),
('STRUCTURE', '占比结构', '占比,结构,构成,比例,份额', 5, 1),
('RANKING', '排名分析', '排名,排行,最好,最差,top10,top 10,前10', 6, 1),
('SALES_TREND', '销售趋势', '销售,销售额,销量,营收,收入,趋势,走势,增长', 7, 1),
('STAT_TREND', '统计指标趋势', 'gdp,生产总值,地区生产总值,GDP,财政收入,一般公共预算收入,一般公共预算支出,预算收入,财政支出,税收,非税收入,规上工业,规模以上工业,工业增加值,居民收入,全体居民人均可支配收入,社会消费品零售,固定资产投资,统计月报,统计公报,统计分析,统计局,经济指标,经济趋势,经济数据,经济总量,经济运行,经济形势,经济,增速,增幅,第一产业,第二产业,第三产业,一产,二产,三产,产业发展,产业趋势,外商,外资,进出口,出口,进口,零售,存款,贷款,可支配收入,用电量,全社会用电量,客运量,货运量,商品房销售,工业投资,产业投资,高技术产业投资', 1, 1),
('STAT_RANKING', '区县指标排名', '区县财政收入,县市区财政收入,财政收入排名,区县排名,县市区排名,区县gdp,县市区gdp,gdp排名,经济排名,区县经济,县市区经济,指标排名', 0, 1);

-- ---------------------------------------------
-- 分析计划配置种子（普通 8 条 + 政务 8 条）
-- ---------------------------------------------
INSERT INTO analysis_plan_config (intent_code, is_gov, table_name, metrics, dimensions, chart_type, time_range, sql_template, status, sort) VALUES
('SALES_TREND', 0, 'order_info', '订单量,销售额', '日期', 'line', '近30天',
 'SELECT order_date, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount FROM order_info WHERE order_date >= {timeRange} GROUP BY order_date ORDER BY order_date', 1, 1),
('USER_PROFILE', 0, 'user_info', '新增用户数,活跃用户数', '年龄段,城市', 'bar', '近30天',
 'SELECT age_group, city, SUM(new_user_count) AS new_user_count, SUM(active_user_count) AS active_user_count FROM user_info GROUP BY age_group, city', 1, 2),
('COMPARISON', 0, 'order_info', '销售额,订单量', '区域,渠道', 'bar', '近30天',
 'SELECT region, channel, SUM(sales_amount) AS sales_amount, SUM(order_count) AS order_count FROM order_info GROUP BY region, channel', 1, 3),
('RANKING', 0, 'product_info', '销量,销售额', '品类', 'bar', '近30天',
 'SELECT category, SUM(sales_volume) AS sales_volume, SUM(sales_amount) AS sales_amount FROM product_info GROUP BY category ORDER BY SUM(sales_volume) DESC LIMIT 10', 1, 4),
('STRUCTURE', 0, 'order_info', '销售额', '品类', 'pie', '近30天',
 'SELECT category, SUM(sales_amount) AS sales_amount FROM order_info GROUP BY category', 1, 5),
('RETENTION', 0, 'user_info', '留存率,新增用户数', '日期', 'line', '近30天',
 'SELECT register_date, AVG(retention_rate) AS retention_rate FROM user_info GROUP BY register_date ORDER BY register_date', 1, 6),
('ANOMALY', 0, 'order_info', '订单量,销售额', '日期,区域', 'table', '近30天',
 'SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount FROM order_info GROUP BY order_date, region ORDER BY order_date DESC LIMIT 30', 1, 7),
('GENERAL', 0, 'order_info', '订单量,销售额,客单价', '日期,区域', 'table', '近30天',
 'SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount, ROUND(SUM(sales_amount) / NULLIF(SUM(order_count), 0), 2) AS avg_order_amount FROM order_info GROUP BY order_date, region ORDER BY order_date', 1, 8),
('SALES_TREND', 1, 'GOV_INFO_RECORD', '发文量,日均发文量', '发布日期', 'line', '近30天',
 'SELECT DATE_FORMAT(publish_date,''%Y-%m'') AS month, COUNT(*) AS doc_count FROM gov_info_record WHERE publish_date >= {timeRange} GROUP BY month ORDER BY month', 1, 1),
('RANKING', 1, 'GOV_INFO_RECORD', '发文量', '公开单位', 'bar', '近30天',
 'SELECT COALESCE(NULLIF(publish_unit,''''), category) AS unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY unit ORDER BY doc_count DESC LIMIT 10', 1, 2),
('STRUCTURE', 1, 'GOV_INFO_RECORD', '发文量', '公开类目', 'pie', '近30天',
 'SELECT category, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category', 1, 3),
('USER_PROFILE', 1, 'GOV_INFO_RECORD', '发文量,类目占比', '公开类目,公开单位', 'table', '近30天',
 'SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit', 1, 4),
('COMPARISON', 1, 'GOV_INFO_RECORD', '发文量,类目占比', '公开类目,公开单位', 'table', '近30天',
 'SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit', 1, 5),
('RETENTION', 1, 'GOV_INFO_RECORD', '发文量,类目占比', '公开类目,公开单位', 'table', '近30天',
 'SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit', 1, 6),
('ANOMALY', 1, 'GOV_INFO_RECORD', '发文量,类目占比', '公开类目,公开单位', 'table', '近30天',
 'SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit', 1, 7),
('GENERAL', 1, 'GOV_INFO_RECORD', '发文量,类目占比', '公开类目,公开单位', 'table', '近30天',
 'SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit', 1, 8);

-- ---------------------------------------------
-- AI 模型配置种子（key 不入库，环境变量 AI_API_KEY 优先）
-- ---------------------------------------------
INSERT INTO ai_model_config (name, model_name, endpoint, max_tokens, temperature, status) VALUES
('text-deepseek', 'deepseek-chat', 'https://api.deepseek.com/v1', 2048, 0.2, 1),
('sql-deepseek', 'deepseek-chat', 'https://api.deepseek.com/v1', 2048, 0.2, 1),
('report-deepseek', 'deepseek-chat', 'https://api.deepseek.com/v1', 4096, 0.7, 1);

-- ---------------------------------------------
-- Prompt 模板种子（基线，供管理端查看与维护）
-- ---------------------------------------------
INSERT INTO prompt_template (name, type, content, version, status, variables, sort) VALUES
('SQL 生成基线', 'SQL', '你是资深数据分析师，根据给定的数据表元数据生成一条只读 SELECT SQL。只输出 SQL 本身，不要任何解释、不要 markdown 代码块、不要分号结尾。查询数值指标时，若数据表含 unit 字段，请在 SELECT 中一并返回 unit，便于图表标注单位。', 1, 1, 'datasetSchema,userQuestion,originSQL', 1),
('解读生成基线', 'INTERPRET', '你是资深数据分析师。根据给定的查询结果与指标口径，用中文输出不超过150字的分析结论，包含关键数字与趋势、占比或对比要点；只输出结论正文，不要标题、不要 markdown、不要多余解释。', 1, 1, 'datasetSchema,resultRows', 2),
('意图识别基线', 'INTENT', '你是数据分析意图识别器。根据用户问题判断分析意图，只输出一个 JSON 对象，不要 markdown、不要任何解释。JSON 字段：intentType（必须从给定可选项中选一个）、intentName（中文名称）、confidence（0~1 的置信度）、matchedKeywords（命中的关键词数组）。', 1, 1, 'userQuestion,availableCodes', 3),
('推荐追问基线', 'RECOMMEND', '你是数据分析助手。根据分析意图、指标维度与查询结果摘要，给出 2~3 条与当前分析上下文相关的推荐追问，只输出 JSON 数组（每项为一句中文问题），不要任何解释。', 1, 1, 'userQuestion,intent,resultSummary', 4);


-- ---------------------------------------------
-- 多轮会话迁移（对已存在的旧表幂等补列，MySQL 8.0 兼容写法）
-- 用法：新库直接建表含上述列；旧库执行下面三段（每段先查 information_schema 再 ALTER）。
-- ---------------------------------------------
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_session' AND COLUMN_NAME = 'analysis_goal');
SET @ddl = IF(@s = 0, 'ALTER TABLE analysis_session ADD COLUMN analysis_goal VARCHAR(500) NULL COMMENT ''分析目标'' AFTER title', 'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_step' AND COLUMN_NAME = 'round_no');
SET @ddl = IF(@s = 0, 'ALTER TABLE analysis_step ADD COLUMN round_no INT DEFAULT 1 COMMENT ''轮次'' AFTER session_id', 'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
-- prompt_template 补列（variables/sort）
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prompt_template' AND COLUMN_NAME = 'variables');
SET @ddl = IF(@s = 0, 'ALTER TABLE prompt_template ADD COLUMN variables VARCHAR(500) NULL COMMENT ''变量名逗号分隔'' AFTER content', 'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prompt_template' AND COLUMN_NAME = 'sort');
SET @ddl = IF(@s = 0, 'ALTER TABLE prompt_template ADD COLUMN sort INT DEFAULT 0 COMMENT ''排序权重'' AFTER version', 'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_report' AND COLUMN_NAME = 'round_no');
SET @ddl = IF(@s = 0, 'ALTER TABLE analysis_report ADD COLUMN round_no INT DEFAULT 1 COMMENT ''轮次'' AFTER session_id', 'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;


-- ============ Agent 多步分析计划（2026-08-18）============
CREATE TABLE IF NOT EXISTS agent_plan (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL COMMENT '所属用户',
  title VARCHAR(200) NOT NULL COMMENT '计划标题',
  goal TEXT NOT NULL COMMENT '宏观分析目标',
  dataset_id BIGINT DEFAULT NULL COMMENT '关联数据集',
  model_config_id BIGINT DEFAULT NULL COMMENT '步骤执行模型配置',
  status VARCHAR(20) NOT NULL DEFAULT 'GENERATED' COMMENT 'GENERATED/EXECUTING/DONE/FAILED',
  steps_json LONGTEXT DEFAULT NULL COMMENT '步骤 JSON',
  report_title VARCHAR(300) DEFAULT NULL COMMENT '报告标题',
  report_content LONGTEXT DEFAULT NULL COMMENT '报告正文(Markdown)',
  report_generator_type VARCHAR(20) DEFAULT NULL COMMENT '报告生成方式 LLM/RULE',
  report_charts_json LONGTEXT DEFAULT NULL COMMENT '报告图表数据 JSON 数组',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_agent_plan_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 多步分析计划（宏观目标拆解→逐步执行→报告）';