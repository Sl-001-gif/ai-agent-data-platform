# 🤖 AI Agent 数据分析平台

> 自然语言驱动的智能数据分析平台 —— 输入分析目标，AI 自动完成从意图识别、SQL 生成与执行到图表展示、数据解读、报告生成的全流程数据分析。

---

## 📖 项目简介

AI Agent 数据分析平台是一个基于 **SpringBoot 3 + Vue 3** 前后端分离的智能化数据分析系统。用户只需用**自然语言**描述分析需求，系统即可通过 AI Agent 自动完成**意图识别 → 分析计划生成 → Text-to-SQL → SQL 安全校验 → SQL 执行 → 图表推荐 → 数据解读 → 报告生成**的完整闭环，大幅降低数据分析门槛。

---

## ✨ 核心功能

### 👑 管理端

| 功能模块 | 描述 |
|---------|------|
| **系统管理** | 登录、个人信息管理、修改密码 |
| **用户管理** | 管理系统所有用户账号与权限 |
| **数据集管理** | 维护可分析的数据集及其配置 |
| **表结构与字段语义** | 管理数据表结构、字段说明与业务含义 |
| **指标口径管理** | 统一管理业务指标的计算口径与定义 |
| **业务数据初始化** | 初始化业务分析所需的基准数据 |
| **AI 模型配置** | 管理 AI 模型参数与 Prompt 模板 |
| **SQL 安全校验** | 配置与管理 SQL 安全校验规则 |
| **Agent 执行记录** | 查看 Agent 执行的全链路日志与状态 |
| **分析报告管理** | 查看与管理已生成的分析报告 |

### 👤 普通用户

| 功能模块 | 描述 |
|---------|------|
| **账号管理** | 登录、注册、个人信息、修改密码 |
| **数据集选择** | 选择要分析的数据集 |
| **自然语言分析** | 用自然语言输入分析目标与需求 |
| **意图识别查看** | 查看 AI 识别出的分析意图 |
| **Agent 分析计划** | 查看 AI 自动生成的详细分析计划与步骤 |
| **SQL 生成与执行** | 查看每步生成的 SQL、安全校验结果与执行状态 |
| **结果表格与图表** | 查看查询结果数据与 ECharts 自动推荐的图表 |
| **AI 数据解读** | 查看 AI 对数据的深度解读与结论 |
| **推荐追问** | 基于当前结果推荐下一步追问问题 |
| **多轮会话** | 同一主题下的多次分析归入一个会话统一管理 |
| **分析报告** | 一键生成分析报告并查看历史记录 |

---

## 🛠️ 技术栈

### 整体架构

```
┌─────────────────────────────────────────────────┐
│             前端 (Vue 3 + Vite)                   │
│  Element-Plus  │  ECharts  │  Vue-Router  │ Axios │
└──────────────────────┬──────────────────────────┘
                       │ REST API (JWT)
┌──────────────────────▼──────────────────────────┐
│              后端 (SpringBoot 3)                  │
│  Controller  →  Service  →  MyBatis  →  MySQL   │
│         ↑                          ↑             │
│         └── AI Agent 核心引擎 ──────┘             │
└─────────────────────────────────────────────────┘
```

### 详细技术栈

| 层级 | 技术 | 说明 |
|-----|------|------|
| **后端框架** | SpringBoot 3 | 主框架，Java 21 |
| **ORM** | MyBatis + PageHelper | 数据访问层与分页 |
| **工具库** | Hutool | Java 工具集 |
| **鉴权** | JWT | 前后端分离 Token 鉴权 |
| **构建** | Maven 3.8+ | 项目构建管理 |
| **前端框架** | Vue 3 + Vite | 现代化前端开发 |
| **UI 组件** | Element-Plus | 企业级 UI 组件库 |
| **路由** | Vue-Router | 前端路由管理 |
| **HTTP** | Axios | 前端网络请求 |
| **样式** | Sass | CSS 预处理 |
| **图表** | ECharts | 数据可视化图表库 |
| **数据库** | MySQL 5.7+ / 8.0 | 关系型数据库 |
| **AI 能力** | OpenAI API / 大语言模型 | 自然语言处理核心 |

### 版本要求

| 组件 | 最低版本 |
|------|---------|
| JDK | 17+ |
| MySQL | 5.7 或 8.0 |
| Node.js | 18+ |
| Maven | 3.8+ |
| Navicat（可选）| 16+ |

---

## 📁 项目结构

### 整体目录

```
ai-agent-data-platform/
├── backend/                          # 后端项目（SpringBoot 3 + Maven）
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/aiagent/
│   │   │   │   ├── controller/       # REST 控制器层
│   │   │   │   ├── service/          # 业务逻辑层
│   │   │   │   ├── mapper/           # MyBatis Mapper 接口
│   │   │   │   ├── entity/           # 数据库实体类
│   │   │   │   ├── dto/              # 数据传输对象
│   │   │   │   ├── config/           # 全局配置
│   │   │   │   │   ├── SecurityConfig.java    # JWT 安全配置
│   │   │   │   │   ├── CorsConfig.java        # 跨域配置
│   │   │   │   │   ├── MyBatisConfig.java     # MyBatis 配置
│   │   │   │   │   └── WebMvcConfig.java      # Web MVC 配置
│   │   │   │   ├── ai/               # 🧠 AI 核心引擎
│   │   │   │   │   ├── intent/       # 分析意图识别
│   │   │   │   │   ├── planner/      # Agent 分析计划生成
│   │   │   │   │   ├── sql/          # Text-to-SQL + 校验 + 纠错
│   │   │   │   │   ├── chart/        # 图表类型推荐
│   │   │   │   │   ├── interpreter/  # AI 数据解读
│   │   │   │   │   └── recommender/  # 推荐追问生成
│   │   │   │   └── util/             # 工具类
│   │   │   └── resources/
│   │   │       ├── application.yml   # 主配置文件
│   │   │       ├── application-dev.yml   # 开发环境配置
│   │   │       ├── application-prod.yml  # 生产环境配置
│   │   │       └── mapper/           # MyBatis XML Mapper
│   │   └── test/
│   │       └── java/com/aiagent/     # 单元测试
│   └── pom.xml
│
├── frontend/                         # 前端项目（Vue 3 + Vite）
│   ├── src/
│   │   ├── views/
│   │   │   ├── admin/                # 管理端页面
│   │   │   │   ├── Dashboard.vue     # 管理首页
│   │   │   │   ├── UserManagement.vue    # 用户管理
│   │   │   │   ├── DatasetManagement.vue # 数据集管理
│   │   │   │   ├── TableSchema.vue       # 表结构管理
│   │   │   │   ├── MetricManagement.vue  # 指标口径管理
│   │   │   │   ├── AIConfig.vue          # AI 模型配置
│   │   │   │   ├── SQLSecurity.vue       # SQL 安全校验
│   │   │   │   ├── AgentLogs.vue         # Agent 执行记录
│   │   │   │   └── ReportManagement.vue  # 分析报告管理
│   │   │   └── user/                 # 用户端页面
│   │   │       ├── Login.vue             # 登录
│   │   │       ├── Register.vue          # 注册
│   │   │       ├── Profile.vue           # 个人信息
│   │   │       ├── DatasetSelect.vue     # 选择数据集
│   │   │       ├── AnalysisInput.vue     # 分析目标输入
│   │   │       ├── AnalysisResult.vue    # 分析结果展示
│   │   │       ├── SessionHistory.vue    # 多轮会话历史
│   │   │       └── ReportGenerate.vue    # 报告生成
│   │   ├── components/               # 公共组件
│   │   │   ├── ChartRenderer.vue         # ECharts 图表渲染
│   │   │   ├── AgentStepTimeline.vue     # Agent 执行步骤追踪
│   │   │   ├── DataTable.vue             # 数据表格展示
│   │   │   ├── SQLDisplay.vue            # SQL 展示与校验状态
│   │   │   └── DataInterpretation.vue    # AI 数据解读卡片
│   │   ├── router/
│   │   │   └── index.js              # 路由配置
│   │   ├── stores/                   # Pinia 状态管理
│   │   │   ├── user.js               # 用户状态
│   │   │   └── analysis.js           # 分析会话状态
│   │   ├── api/                      # Axios API 接口
│   │   │   ├── auth.js               # 认证相关
│   │   │   ├── dataset.js            # 数据集相关
│   │   │   ├── analysis.js           # 分析相关
│   │   │   └── report.js             # 报告相关
│   │   ├── utils/                    # 工具函数
│   │   │   ├── request.js            # Axios 封装
│   │   │   └── auth.js               # Token 管理
│   │   └── assets/                   # 静态资源
│   │       ├── styles/               # 全局样式
│   │       └── images/               # 图片资源
│   ├── index.html
│   ├── vite.config.js
│   └── package.json
│
├── docs/                             # 项目文档
│   └── sql/                          # SQL 初始化脚本
│       ├── schema.sql                # 建表语句
│       └── init_data.sql             # 初始化数据
│
└── README.md                         # 本文件
```

---

## 🚀 快速开始

### 前置环境

确保已安装以下环境：

```bash
# 检查 Java 版本（需要 17+）
java -version

# 检查 Maven 版本（需要 3.8+）
mvn -version

# 检查 Node.js 版本（需要 18+）
node -v

# 检查 MySQL 版本（5.7 或 8.0）
mysql --version
```

### 1️⃣ 数据库初始化

```bash
# 创建数据库
mysql -u root -p -e "CREATE DATABASE ai_agent_data DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 导入建表脚本
mysql -u root -p ai_agent_data < docs/sql/schema.sql

# 导入初始化数据（可选）
mysql -u root -p ai_agent_data < docs/sql/init_data.sql
```

### 2️⃣ 启动后端

```bash
# 进入后端目录
cd backend

# 配置数据库连接
# 编辑 src/main/resources/application-dev.yml，修改 MySQL 连接信息

# 编译并启动
mvn clean package -DskipTests
java -jar target/ai-agent-data-platform.jar

# 后端默认运行在 http://localhost:8080
```

### 3️⃣ 启动前端

```bash
# 进入前端目录
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 前端默认运行在 http://localhost:5173
```

### 4️⃣ 访问系统

打开浏览器访问 `http://localhost:5173` 即可进入系统。

---

## 🔄 核心流程：AI Agent 数据分析

```
用户输入自然语言分析目标
        │
        ▼
┌─────────────────────────────────────────────────────┐
│  ① 分析意图识别                                      │
│  识别用户的分析意图（趋势分析、对比分析、占比分析等）    │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ② Agent 分析计划生成                                │
│  自动拆解分析步骤，生成详细的执行计划                   │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ③ Text-to-SQL 生成                                 │
│  结合表结构、字段语义、指标口径，生成对应 SQL          │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ④ SQL 安全校验                                     │
│  校验 SQL 合法性，防止注入与危险操作                   │
├─────────────────┬───────────────────────────────────┤
│      ❌ 校验失败 ──→  ⑤ 自动纠错重试                   │
│      ✅ 校验通过 ──→  ↓                              │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ⑥ SQL 执行                                        │
│  在数据库中执行 SQL 并获取查询结果                     │
├─────────────────┬───────────────────────────────────┤
│      ❌ 执行失败 ──→  ⑤ 自动纠错重试                   │
│      ✅ 执行成功 ──→  ↓                              │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ⑦ 查询结果摘要压缩                                  │
│  对大量结果数据进行摘要压缩，适配 LLM 上下文窗口       │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ⑧ 图表类型推荐                                     │
│  根据查询结果自动推荐合适的 ECharts 图表类型           │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ⑨ AI 数据解读 + 推荐追问                            │
│  AI 深度解读数据，给出结论与洞察，推荐下一步追问       │
└─────────────────┬───────────────────────────────────┘
                  ▼
┌─────────────────────────────────────────────────────┐
│  ⑩ 分析报告生成                                     │
│  将整轮分析过程、SQL、图表、解读汇总为结构化报告       │
└─────────────────────────────────────────────────────┘
```

---

## 💡 项目创新点

### 1️⃣ 自然语言驱动的数据分析 Agent
用户无需手写 SQL，用自然语言描述分析目标即可完成从意图识别到报告生成的全流程，大幅降低数据分析门槛。

### 2️⃣ Text-to-SQL + 字段语义与指标口径注入
将表结构、字段语义、指标口径注入 Prompt，让生成的 SQL 贴合真实业务口径，而非机械翻译自然语言。

### 3️⃣ SQL 安全校验 + 执行失败自动纠错
生成的 SQL 先进行安全校验，执行报错后由 AI 自动分析错误原因并纠错重试，兼顾可用性与安全性。

### 4️⃣ Agent 分步执行与过程追踪
将分析拆解为意图识别、计划生成、SQL 生成、校验、执行、图表推荐、数据解读、报告生成等多个步骤，每步执行过程可追踪、可回溯。

### 5️⃣ 图表自动推荐 + AI 数据解读
结合查询结果自动推荐最合适的图表类型（ECharts 渲染），并由 AI 深度解读数据、给出业务结论与洞察。

### 6️⃣ 多轮会话归档 + 推荐追问
系统基于查询结果自动推荐追问问题，并使用会话机制将同一主题下的多次分析记录关联起来，形成完整分析链路。

---

## ⚙️ 环境配置说明

### 后端配置 (`application-dev.yml`)

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_agent_data?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver

# JWT 配置
jwt:
  secret: your-jwt-secret-key
  expiration: 86400000  # 24小时

# AI 模型配置
ai:
  model:
    api-key: your-api-key
    model-name: gpt-4o
    endpoint: https://api.openai.com/v1
```

### 前端配置 (`frontend/.env.development`)

```env
VITE_API_BASE_URL=http://localhost:8080/api
VITE_APP_TITLE=AI Agent 数据分析平台
```

---

## 📚 项目依赖

### Maven 核心依赖（`pom.xml`）

| 依赖 | 用途 |
|------|------|
| spring-boot-starter-web | Web 框架 |
| spring-boot-starter-security | 安全框架 |
| mybatis-spring-boot-starter | MyBatis ORM |
| pagehelper-spring-boot-starter | 分页插件 |
| hutool-all | Java 工具集 |
| jjwt-api / jjwt-impl | JWT 鉴权 |
| mysql-connector-j | MySQL 驱动 |
| lombok | 代码简化 |

### NPM 核心依赖（`package.json`）

| 依赖 | 用途 |
|------|------|
| vue@3 | 前端框架 |
| vite | 构建工具 |
| element-plus | UI 组件库 |
| vue-router | 路由管理 |
| axios | HTTP 请求 |
| sass | 样式预处理器 |
| echarts | 数据可视化图表 |
| pinia | 状态管理 |

---

## 📄 数据库表设计（主要表）

| 表名 | 说明 |
|------|------|
| `sys_user` | 系统用户表 |
| `sys_role` | 角色表 |
| `dataset` | 数据集配置表 |
| `table_schema` | 数据表结构定义表 |
| `table_field` | 字段语义定义表 |
| `metric_definition` | 指标口径定义表 |
| `analysis_session` | 分析会话表 |
| `analysis_step` | Agent 执行步骤记录表 |
| `analysis_report` | 分析报告表 |
| `ai_model_config` | AI 模型配置表 |
| `prompt_template` | Prompt 模板表 |

---

## 🤝 贡献指南

欢迎贡献代码或提出改进建议！请遵循以下流程：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

---

## 📄 开源协议

本项目仅供学习交流使用。

---

> **项目状态**：开发中 🚧
> **技术交流**：如有问题或建议，欢迎提交 Issue
