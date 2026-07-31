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
CREATE TABLE IF NOT EXISTS dataset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    db_type VARCHAR(20) DEFAULT 'MYSQL',
    db_host VARCHAR(255),
    db_port INT DEFAULT 3306,
    db_name VARCHAR(100),
    db_username VARCHAR(100),
    db_password VARCHAR(255),
    status TINYINT DEFAULT 1,
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
    status TINYINT DEFAULT 1,
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
    description TEXT,
    calculation_formula TEXT COMMENT '计算口径/SQL',
    table_id BIGINT,
    field_id BIGINT,
    status TINYINT DEFAULT 1,
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
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt 模板表';

-- ---------------------------------------------
-- 初始化管理员账号 (密码: admin123)
-- ---------------------------------------------
INSERT INTO sys_user (username, password, nickname, role, status)
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '系统管理员', 'ADMIN', 1);
