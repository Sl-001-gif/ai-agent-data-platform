---
name: ai-coding-governance
description: AI 编码治理协议 — 解决项目记忆缺失、变更失控、同源盲区等 6 大问题（公开模板，克隆后复制为根目录 AGENTS.md）
---

# AI 编码治理协议

> **本文件为公开模板**：从本仓库克隆后复制为根目录 `AGENTS.md` 使用。
> 不含本机路径/口令/个人配置；机器特定环境速查与个人协议（学习复习、错峰测试）由使用者补充到本地 `AGENTS.md`。
> 交接总览见 `docs/handover/交接文档-2026-08-20.md`。

## 核心原则

1. **项目记忆缺失** → AGENTS.md + 架构文档体系
2. **局部正确全局失控** → 强制影响分析 + rg 调用方扫描
3. **承重墙误碰** → 风险区域标注 + 分级响应
4. **顺手重构范围失控** → 变更预算 + 禁止越界
5. **编译通过≠功能正常** → 三层测试体系
6. **自我审查同源盲区** → 三角色分离工作流

## 第一章：强制启动流程

### 1.1 每次对话开始时
1. 读取项目根目录 `AGENTS.md`（本模板的本地版），关注 🔴🟡 高风险区域标注
2. 向用户输出：理解的关键约束、哪些是红线
3. 用户确认后才开始处理任务

### 1.2 记忆文件体系
- `AGENTS.md`：治理协议 + 环境速查 + 进度表 + 风险区域 + 已知陷阱
- `docs/项目结构文档.md`：工程结构地图（包结构/数据表/接口/链路/测试）
- `docs/plans/`：阶段实施计划与待办
- `docs/sql/`：平台表 DDL 真源

## 第二章：变更约束
- **2.1 禁止顺手重构**：非直接相关代码一行都不动
- **2.2 变更预算**：单任务 ≤ 3 文件 / 100 行新增代码；超出请示用户；测试文件单独计数
- **2.3 变更范围声明**：动手前输出文件/改动/理由表
- **2.4 Review 检查点**：改完先出 diff 概览，用户确认后再落地
- **2.5 文档同步义务**：新增接口/数据表/目录/路由/AI 子模块必须同步 `docs/项目结构文档.md`；以代码与 AGENTS.md 为准

## 第三章：强制影响分析
- 每次变更前输出：范围、连锁（rg 扫描调用方）、风险级别、预算、契约变化、回退方案
- 修改函数签名/核心数据结构/🔴 代码 → 必须 `rg` 扫描所有调用方
- 风险分级：🔴 改前完整影响链+确认；🟡 列全部调用方+确认；🟢 正常改+变更清单

## 第四章：三层测试体系
- L1 单元（函数输入输出）→ L2 集成（跨模块数据流）→ L3 端到端（用户场景链路）
- 测试先行；测试文件不占预算；🔴 三层全覆盖、🟡 L1+L2、🟢 L1

## 第五章：三角色分离工作流
- 触发：🔴 区域变更 / 跨 3+ 模块 / 核心数据结构变更 / 深度审查
- 分析师（澄清+验收标准+影响分析）→ 实现者（编码+测试+变更清单）→ 审查者（覆盖/越界/遗漏/测试）

## 第六章：纠偏与负反馈
- 踩线 → 承认违规 → 回退越界变更 → 重新走流程；负反馈记入 AGENTS.md 已知陷阱

## 第七章：执行优先级
安全（不碰承重墙、不越界）> 正确（三层测试、影响分析）> 效率（预算内）> 美观

---

# 项目概览
自然语言驱动的智能数据分析 Agent 平台：用户输入分析目标，AI 自动完成 意图识别 → SQL 生成与执行 → 图表展示 → 数据解读 → 报告生成。研究对象为邵阳市政务公开数据（`gov_info_record` 3890 条，政务目录 + 统计三栏目）。

## 技术栈
SpringBoot 3 + MyBatis + PageHelper + Hutool + JWT（Java 21 / Maven）· Vue 3 + Vite + Element-Plus + ECharts · MySQL 5.7+/8.0 · DeepSeek LLM（OpenAI 兼容）

## 目录结构速览
```
backend/src/main/java/com/aiagent/
  controller/ service/ mapper/ entity/ dto/ config/ util/
  ai/           # AI 核心引擎
    intent/ planner/ sql/ chart/ interpreter/ recommender/ report/ metadata/ model/ context/ prompt/ validate/
frontend/src/
  views/admin/ views/user/ views/analysis/ views/system/ components/ router/ stores/ api/ assets/
docs/
  sql/（DDL） plans/（阶段计划） reports/（分析产物） handover/（交接文档） 项目结构文档.md
tools/
  scraper/（政务/统计爬虫与清洗） _test_stat/（错峰深度测试工具） _pwtest/（浏览器烟测）
```

# 🔴 高风险区域（承重墙）
| 路径 | 说明 |
|------|------|
| `backend/src/main/java/com/aiagent/ai/` | AI 核心引擎，被全流程依赖 |
| `backend/src/main/java/com/aiagent/config/` | 全局配置，影响所有模块 |

# 🟡 中等风险区域
| 路径 | 说明 |
|------|------|
| `backend/src/main/java/com/aiagent/service/` | 业务逻辑层，被 Controller 调用 |
| `backend/src/main/java/com/aiagent/mapper/` | 数据访问层，被 Service 调用 |

# 🟢 低风险区域
| 路径 | 说明 |
|------|------|
| `backend/src/main/java/com/aiagent/util/` | 工具类 |
| `frontend/src/assets/` | 静态资源 |

# 模块依赖地图
🔴 `ai/` → 被 `controller/AnalysisController`、`AgentController` 依赖
🔴 `config/SecurityConfig` → JWT 配置影响全系统鉴权
🟡 `service/` → 被 `controller/` 下所有 Controller 调用
反向依赖速查：`rg "X\(" --type java -l`

# 环境速查（占位符版，本机值见本地 AGENTS.md）
| 项 | 值 |
|----|----|
| JDK | 本机 openjdk-22.0.1（完整路径见本地 AGENTS.md） |
| Maven | 本机 IDEA 内置 maven3，跑前设 `JAVA_HOME` |
| MySQL | 本机服务，库 `ai_agent_data`（dev profile），口令走 `MYSQL_PASSWORD` |
| 后端启动 | `java -jar backend/target/ai-agent-data-platform-1.0.0-SNAPSHOT.jar` → 8080 |
| 前端启动 | `cd frontend && npm run dev` → 5173 |
| LLM | `AI_API_KEY`（deepseek-chat，OpenAI 兼容端点），无 key 自动回退规则引擎 |
| 配置模板 | `backend/src/main/resources/application-dev.yml.example` |

# 已知陷阱（精选；完整版见本地 AGENTS.md）
| 陷阱 | 解决方案 |
|------|----------|
| Java 源文件带 BOM → 编译"非法字符" | 写 Java/脚本必须 UTF-8 无 BOM |
| 未登录访问接口返回 403 空 body | SecurityConfig 配 authenticationEntryPoint 返回统一 401 JSON |
| `@Valid` 校验失败经 /error 被安全链拦截 | GlobalExceptionHandler 统一返回 400 JSON |
| MockMvc 中文断言恒失败 | `getContentAsString(StandardCharsets.UTF_8)` |
| `.cmd` 硬编码中文 → GBK 解析报错 | .cmd 内禁止写死中文 |
| PATH 上 java 是坏链接 → 启动即退出 | 用 JDK 完整路径启动 |
| 沙箱离线 `mvn test` 不可行 | L1 用运行器直驱真实类；全量留给本机联网 |
| 脚本内写死数据库口令 | 一律走 `MYSQL_PWD` / `MYSQL_PASSWORD` 环境变量 |

> 待办与进度：见 `docs/plans/` 与本地 `AGENTS.md`「开发进度」表。
> 交接细节：见 `docs/handover/交接文档-2026-08-20.md`。