# AI Agent 数据分析平台

自然语言驱动的智能数据分析 Agent 平台：输入一句分析目标，AI 自动完成 **意图识别 → 分析计划 → Text-to-SQL → 受控执行 → 图表展示 → 数据解读 → 分析报告** 的全流程。内置「规则引擎 + LLM 双路」降级机制，无 LLM Key 也能完整跑通。

> 实习项目，研究对象为邵阳市政务公开工作：数据来自邵阳市政府门户依法公开的信息目录与统计月报/公报/分析栏目，产出结构化指标库后由 AI 引擎直接做趋势、对比、排名分析。

## 核心特性

- **自然语言 → 全自动分析**：无需手写 SQL，输入「2025 年 1-9 月全市 GDP」「各区县财政收入排名」即可得到图表、解读与报告
- **AI + 规则双引擎**：意图识别 / SQL 生成 / 纠错 / 解读 / 报告均 LLM 优先、规则兜底；设置 `AI_API_KEY` 即启用 DeepSeek，不设置则纯规则运行
- **SQL 安全校验**：9 条安全校验（只读、白名单表、防注入等），执行失败自动纠错重试
- **Agent 步骤追踪**：INTENT → PLAN → SQL → VALIDATE → EXECUTE → CHART → INTERPRET → REPORT 每步落库可回溯，会话覆盖式复用
- **管理后台**：数据源管理、数据元配置（数据集/数据表/字段语义/指标口径 + 分类 Tab）、AI 模型配置、意图规则与计划配置（**类型自定义 + 启用/停用**）
- **结构化数据采集管道**：政务公开爬虫（幂等、限流、噪音过滤）与统计指标管道（Excel 月报卡自动解析 + 正文指标抽取 → `stat_indicator` 期间×指标×区县长表）

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3 · MyBatis · PageHelper · JWT · Hutool（Java 21+） |
| 前端 | Vue 3 · Vite · Element Plus · ECharts · Pinia · Axios |
| 数据库 | MySQL 8 |
| AI | DeepSeek（OpenAI 兼容接口，`AI_API_KEY` 环境变量注入，Key 不入库） |

## 快速开始

### 环境要求

- JDK 21+（实测 22）、Maven 3.9+、Node 18+、MySQL 8

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE ai_agent_data DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p ai_agent_data < docs/sql/schema.sql
# 可选：统计指标管道建表与配置
mysql -uroot -p ai_agent_data < docs/sql/stat-pipeline.sql
mysql -uroot -p ai_agent_data < docs/sql/stat-analysis-config.sql
mysql -uroot -p ai_agent_data < docs/sql/data-category.sql
```

### 2. 启动后端（8080）

```bash
cd backend
cp src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml
# 编辑 application-dev.yml，填入 MYSQL_PASSWORD（与 JWT_SECRET，可选 AI_API_KEY）
# 或直接设置环境变量：
#   set MYSQL_PASSWORD=你的密码    （Windows）
#   export AI_API_KEY=sk-xxx       （Linux/macOS）
mvn package -DskipTests
java -jar target/ai-agent-data-platform-1.0.0-SNAPSHOT.jar
```

### 3. 启动前端（5173）

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 `http://localhost:5173`，默认管理员账号：`admin / admin123`（首次部署请立即修改）。

## 数据获取（可选）

仓库不含业务数据，可运行 `tools/scraper/` 下的脚本自行采集依法公开数据：

- `gov_scraper.py`：邵阳市政府门户信息公开目录全量采集（幂等、1s 限流、导航噪音过滤）
- `gov_stat_scraper.py`：统计月报 / 统计公报 / 统计分析三类栏目采集
- `stat_scraper.py`：详情页全文 + Excel 月报卡附件解析 + 指标抽取，产出结构化指标库
- `backfill_unit.py`：发文单位三级补齐

## 项目结构

```
backend/                 # Spring Boot 后端
├── src/main/java/com/aiagent/
│   ├── ai/              # AI 引擎（意图/计划/SQL/图表/解读/追问/报告）
│   ├── controller/      # REST 控制器
│   ├── service/         # 业务逻辑
│   ├── mapper/          # MyBatis 数据访问
│   └── entity/ dto/ util/ config/
frontend/                # Vue 3 前端
├── src/views/admin/     # 管理后台（数据元/AI 规则/数据源等）
├── src/views/user/      # 用户端（智能分析/数据浏览等）
└── src/api/ stores/ router/
tools/scraper/           # 数据采集与指标抽取脚本
docs/sql/                # 建表与种子 SQL（幂等可重跑）
docs/plans/              # 阶段方案文档
```

## License

[MIT](LICENSE)