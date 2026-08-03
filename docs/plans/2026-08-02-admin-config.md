# 下一任务书：管理端「数据元配置 + AI 规则配置」落地（对齐项目结构文档）

> 承接：2026-08-02 文档入库（`docs/项目结构文档.md` / `docs/项目介绍.md`）+ 政务数据接入完成（commit 1a13fdc）。
> 依据：`docs/项目结构文档.md`（基于 36 张截图还原的 14 页管理端规划）。
> 目标：让管理员在**前端自行增删改**数据集 / 数据表 / 字段语义 / 指标口径（及后续意图规则、AI 模型、Prompt 模板），AI 引擎**按库中数据与配置运行**（LLM 元数据已读库、规则引擎尚未配置化）。

## 一、现状基线（2026-08-02 已核对）

- 前端仅 6 条路由：`/login /register / /analysis /profile /admin`；管理端只有 `Dashboard.vue`（数据源管理）
- 后端仅 4 个 Controller：`AnalysisController` / `AuthController` / `DataSourceAdminController` / `GlobalExceptionHandler`
- 库表 `dataset(1) / table_schema(1) / table_field(7) / metric_definition(4)` 已有政务种子数据，且被 `MetadataService` 读入 LLM Prompt —— **缺管理界面、缺文档定义字段**
- `ai_model_config / prompt_template` 表存在但为空
- 规则引擎 `RuleIntentRecognizer / AnalysisPlanner / RuleSqlGenerator` 仍硬编码在 Java static map
- 角色矩阵已对齐：`SecurityConfig` 中 `/api/admin/**` 需 ADMIN，USER 仅智能分析

## 二、实施步骤（按文档「使用说明」开发顺序：Layout → 元数据 4 页 → AI 配置 2 页 → 智能分析）

### 阶段 A：数据元配置管理端（优先，直接满足「前端管理数据 + AI 按库分析」）
- **A1 表结构对齐**：`dataset / table_schema / table_field / metric_definition` 补文档字段（排序、业务场景、主表名、关系说明、语义类型、可查询/可聚合、指标编码、SQL 表达式等），存量政务元数据迁移补种
- **A2 后端 CRUD**：4 组接口，沿用 `/api/admin/**` + ADMIN 模式（照 `DataSourceAdminController`），路由语义按文档 `/dataset` `/data-table` `/field-semantic` `/metric`
- **A3 AI 联动**：`MetadataService` 读取补全列，**修复 `calculation_formula` 列名 bug**（现读 `formula` 恒空）；前端改元数据 → LLM Prompt 立即生效
- **A4 前端页面**：管理端侧边栏加 4 菜单 + 4 页面，弹窗增删改（对齐文档字段/placeholder/状态/排序）
- **A5 三层测试**：L1 服务单测 / L2 真实库集成断言 / L3 e2e 脚本扩展

### 阶段 B：AI 规则配置化
- 新增 `analysis_intent_rule` + `analysis_plan_config` 两表，现有 8 意图 + 16 计划配置 + SQL 模板落种子
- `RuleIntentRecognizer / AnalysisPlanner / RuleSqlGenerator` 改读库，表空自动回退内置（行为不变）
- 管理端「分析配置」页（意图规则 + 计划配置增删改）
- 顺带解决 GOV SALES_TREND「近 30 天」缺口（time_range 配置化）

### 阶段 C：AI 能力配置
- `ai_model_config / prompt_template` CRUD + 现有 DeepSeek 配置种子落库（key 不入库，仅模型名/地址/用途）
- 管理端「AI 模型配置」「Prompt 模板管理」两页（弹窗形式）

### 阶段 D：智能分析页面对齐
- `/home` 系统首页、`/user` 用户管理
- `/ai-analysis` 映射现有分析链路；`/chat-session` 多轮会话；`/agent-plan` `/agent-track` 计划与执行追踪（复用 `analysis_session/step` 表）；`/report` 报告历史列表（复用 `analysis_report` 表）

## 三、预算与红线

- 阶段 A 预估 14 主文件 / 800+ 行（后端 CRUD ~400 + 前端 4 页 ~350 + DDL/迁移 ~80），**超「3 文件/100 行」需用户批准**；测试文件另计
- 阶段 B 触碰 🔴 `ai/` 引擎：**改前输出完整影响链经用户确认**
- 阶段 C/D 涉及 `service/ mapper/`：改前 `rg` 列调用方
- 实施完不自动 commit，由用户确认后提交

## 四、待用户确认

1. 阶段 A 是否开工（表结构采用「扩展现有表」方式）
2. 阶段 A 预算（14 文件 / 800+ 行）是否批准
3. 本次做到阶段几（A / A+B / 全部）
---

## 实施完成记录（2026-08-02）

### 阶段 A：数据元配置管理端（已完成，2026-08-02 上午）
- 库表：`dataset / table_schema / table_field / metric_definition` 补文档字段并线上 ALTER；政务种子（gov_info_record 表 + 7 字段 + 4 指标带 metric_code）已迁移
- 后端：4 实体 + `MetadataAdminMapper` + `MetadataAdminService` + `MetadataAdminController`（`/api/admin/dataset|data-table|field-semantic|metric`，仅 ADMIN）；`MetadataService` 修复 `calculation_formula` 优先
- 前端：`DataSource|Dataset|DataTable|FieldSemantic|Metric.vue` + `api/metadata.js` + 管理端侧边菜单
- 测试：L1 `MetadataAdminServiceTest` 17 例 / L2 `MetadataAdminIntegrationTest` 10 例全绿（修复 createField canQuery 默认值）

### 阶段 B：AI 规则配置化（已完成）
- 库表：`analysis_intent_rule`（7 条意图规则）+ `analysis_plan_config`（16 条计划配置：普通 8 + 政务 8）已建表落库，种子与内置回退逐字一致
- 后端：`AnalysisConfigService`（库优先、空表回退内置）+ `AnalysisConfigAdminController`（`/api/admin/analysis-config/intent-rules|plan-configs`）
- 🔴 引擎改造（行为不变）：`RuleIntentRecognizer / AnalysisPlanner / RuleSqlGenerator` 改为注入配置服务，保留无参构造兼容测试
- 缺口修复：`AnalysisController` 传原文 → `TimeRangeParser` 提取时间范围（近3年/近30天/近3月/近2周…）→ `RuleSqlGenerator` 按单位生成 `INTERVAL n YEAR/MONTH/WEEK/DAY`；「近3年按月发文趋势」现已正确
- 测试：L1 `AnalysisConfigServiceTest` 9 例 + `TimeRangeParserTest` 4 例 + `AnalysisPlannerTimeRangeTest` 2 例；L2 `AnalysisConfigAdminIntegrationTest` 8 例

### 阶段 C：AI 能力配置（已完成）
- 库表：`ai_model_config` 种子 3 条（text/sql/report → deepseek-chat，key 不入库，环境变量 `AI_API_KEY` 优先）；`prompt_template` 种子 2 条（SQL/INTERPRET 基线）
- 后端：`AiConfigService` + `AiConfigAdminController`（`/api/admin/ai-config/models|prompts`），接口层强制 apiKey 置空不落库
- 前端：`AnalysisConfig.vue`（意图规则/计划配置双 Tab）+ `AiModel.vue` + `PromptTemplate.vue` + 路由/菜单接线
- 测试：L2 `AiConfigAdminIntegrationTest` 8 例（含 key 不入库断言）

### 验证结果
- 后端全量 `mvn test`：**226/226 全绿**（含既有 164 例回归）
- 前端 `npm run build`：通过
- 待本机执行：重启后端 + 管理端页面人工验收 + `llm-verify-e2e.ps1`（真实 LLM）
- 未 commit，待用户确认后提交
### 阶段 D：智能分析页面对齐（已完成，2026-08-02 下午）
- 后端新增历史查询接口（仅本人数据）：
  - `GET /api/analysis/sessions`（会话列表，支持关键字）、`DELETE /api/analysis/session/{id}`（级联删步骤+报告）
  - `GET /api/analysis/session/{id}/steps`（计划/执行追踪数据源）、`GET /api/analysis/reports`、`GET /api/analysis/report/{id}`
  - `AnalysisSessionMapper` 补 `selectByUserId/deleteById`；`AnalysisReportMapper` 补 `selectByUserId/selectById`；新增 `AnalysisHistoryService` + `AnalysisHistoryController`
- 后端新增用户管理（仅 ADMIN）：`GET/POST/PUT/DELETE /api/admin/user`（列表脱敏、创建加密、编辑可选改密、禁止删除当前登录账号）
- 前端统一布局 `views/layout/Layout.vue`（左侧菜单按角色显隐 + 顶部用户下拉），新增页面：
  - `/home` 系统首页（欢迎横幅 + 功能卡片）、`/user` 用户管理、`/chat-session` 多轮会话、`/agent-plan` 计划步骤、`/agent-track` 执行追踪、`/report` 报告历史
  - `/ai-analysis` 与 `/analysis` 均映射现有分析链路（`AnalysisInput.vue`），登录后默认跳 `/home`；`/admin` 旧管理后台保留兼容
- 测试：L2 `AnalysisHistoryIntegrationTest` 6 例 + `UserAdminIntegrationTest` 7 例全绿；全量 `mvn test` 239/239 全绿；前端 `npm run build` 通过
- 待本机验证：重启后端 + 前端，浏览器走查 7 个新页面；`llm-verify-e2e.ps1` 需在后端启动终端设置 `AI_API_KEY` 后重跑
- 未 commit，待用户确认后提交