# 统计类数据结构化采集管道（stat-data-pipeline）实施记录

> 2026-08-06 | 状态：✅ 已实施（M2~M5 完成，LLM 模式 L3 待本机带 key 复核）
> 目标：把 `gov_info_record` 中「统计月报 85 / 统计公报 189 / 统计分析 204」共 478 条列表级记录，
> 加工成「期间 × 指标 × 区县」结构化指标库（`stat_indicator`），使 AI 引擎可直接做趋势/对比/排名分析并出图。

## 一、M1 站点探测结论（2026-08-06 实测）

- 月报详情页（`/shaoyang/tjyb/YYYYMM/<hash>.shtml`）正文在 `div.wenz > UCAPCONTENT`，内含 1 个相对路径 `.xlsx` 附件链接（`<hash>/files/<file>.xlsx`），单份约 110KB、38 个 sheet
- 公报/分析详情页正文同样在 `UCAPCONTENT`（公报全文约 1.2 万字符，数字被拆进多个 span，需无分隔符拼接文本节点）；尾部分享/二维码噪音均在容器外，天然排除
- 月报卡 xlsx 表形：A 区县表（首列=区县短名，标题行=指标名，含 绝对额/增速/排名 列）、B 指标表 4 列（指标|期间|绝对额(单位)|增速(%)）、C 指标表 3 列（指标/行业|期间|增速（%））；另有「年份×月份」湖南省对比表（81图5）需跳过
- 区县短名→官方全名归一化：新宁→新宁县、邵东→邵东市、城步→城步苗族自治县 等 13 个（全市+12 区县）

## 二、实施结果

### M2 建表与元数据（`docs/sql/stat-pipeline.sql`，幂等可重复执行）
- `stat_doc`：正文全文（LONGTEXT 不截断）+ 附件元数据 + parse_status，唯一键 `gov_record_id`
- `stat_indicator`：period/region/indicator_code/indicator_name/value/unit/growth_rate/sheet_name/source_type/confidence/generator_type，唯一键 `stat_doc_id+sheet_name+indicator_code+region+period`
- 元数据注册仅 `stat_indicator`（dataset id=11「邵阳统计指标数据」/ table_schema / table_field 11 字段 / metric_definition 5 指标：GDP、一般公共预算收入、规模以上工业增加值、居民人均可支配收入、区县指标排名）
- 回退方案：`stat_indicator.status=0` 即从 Prompt 排除，无需改代码

### M3 管道 `tools/scraper/stat_scraper.py`（复用 gov_scraper 工具函数，不改其行为）
- 阶段 1 详情页采集：478 条 → **成功 472 / 失败 6**（6 条为 2012-2017 跨站 stats.gov.cn 死链 404，原页面已下线，属预期）
- 阶段 2 月报卡解析：**85/85 全部成功**，产出 XLSX 指标 25,236 条（含区县排名独立指标「指标名+排名」）
- 阶段 3 正文抽取（规则优先 + LLM 兜底，无 key 自动纯规则）：387 篇全部处理，产出 BULLETIN 4,858 + ANALYSIS 1,088 条
- 幂等与断点续爬：parse_status 状态机（PENDING/DONE/TEXT_DONE/XLSX_DONE/XLSX_FAIL/FAILED），重跑只补失败项；`--force/--dry-run/--limit/--category` 支持调试
- 最终指标库：**31,182 条 / 13 区域 / 117 期间 / 2,227 指标名**

### M4 测试 `tools/scraper/test_stat_scraper.py`
- L1：真实样本（testdata/yuebao_202509.xlsx，2025 年 9 月月报卡）解析断言（GDP 1974.6101 亿元/增速 4.958、新宁县 GDP 1041963.13 万元/排名第 10、一般公共预算收入 861488 万元/增速 -6.25）+ 合成三种表形 + 期间/区县/指标规范化 + 正文规则抽取（GDP/三产/社零，下降转负）+ 详情页正文抽取 + 回归（指标名含「地区」不作表头）
- L2：stat_doc upsert 幂等、stat_indicator INSERT IGNORE 幂等（MySQL 不可用自动跳过）
- 结果：**51 项全绿**

### M5 元数据生效与 L3 验收（2026-08-06，后端 8080）
- 管理端元数据 API 验证：dataset/table_schema/table_field(11)/metric_definition(5) 均含 stat_indicator ✅
- `/api/analysis/execute` 3 个演示问题（各区县财政收入排名/地区生产总值趋势/规模工业增加值增速分析）链路全通：200 + rows + chart + session + interpretation ✅
- `/api/analysis/report` 覆盖式落库 + REPORT 步骤：content 556 字、generatorType=RULE ✅
- ⚠️ RULE 模板仍以 GOV_INFO_RECORD/演示表为目标；stat_indicator 的 SQL 生成依赖 LLM 元数据注入——**LLM 模式验证需本机设置 `AI_API_KEY` 后重启后端跑 `llm-verify-e2e.ps1`**（元数据注入机制已通过管理端 API 确认生效）

## 三、变更范围（已批准超预算）
- 新增：`docs/sql/stat-pipeline.sql`、`tools/scraper/stat_scraper.py`、`tools/scraper/test_stat_scraper.py`、`tools/scraper/testdata/yuebao_202509.xlsx`（测试夹具 112KB）、本文档
- 追加：`tools/scraper/README.md` 一节
- 未改动：`ai/`、`config/`、`service/`、`mapper/`、`gov_scraper.py`、`gov_stat_scraper.py`、`schema.sql`

## 四、合规与口径
- 仅采集邵阳市人民政府门户依法公开的统计栏目详情页与附件，限流 1s/条；成果汇报注明数据来源与口径
- region=全市/区县官方全名（月报卡列头短名已归一化）；rank 类指标 value=名次、unit=名；正文抽取 confidence=medium 便于人工核对

## 五、待办（本机）
1. 设置 `AI_API_KEY` 重启后端 → 跑 `backend/src/test/e2e/llm-verify-e2e.ps1`，用「2025年1-9月全市GDP / 各区县财政收入排名 / 规模工业增加值增速趋势」验证 LLM 直出 stat_indicator SQL 并出图
2. 可选：22 篇跨站旧公报详情页无正文（原页面已下线），如需要可剔除或标注

## 六、AI 引擎接线完成（2026-08-06）
- **问题定位**：`stat_indicator` 数据与元数据均已就绪，但 AI 问数链路未识别统计意图 →「邵阳经济趋势」误路由 GOV_INFO_RECORD 发文量；且 `SqlValidator` 白名单为 Java 硬编码，不含 `stat_indicator`。
- **改动**（最小增量，纯新增/放宽，无行为回退）：
  - 数据库配置 `docs/sql/stat-analysis-config.sql`（幂等）：意图规则 `STAT_RANKING`(prio 0)/`STAT_TREND`(prio 1) + 计划配置 4 条（is_gov 0/1 × 2 意图），table=stat_indicator，规则兜底 SQL（GDP 趋势/财政排名），LLM 模式按元数据自适应。
  - Java（🔴 ai/，共 2 处 1 处小改）：
    - `ai/sql/SqlValidator.java`：TABLE_WHITELIST + `stat_indicator`（仅放宽）。
    - `ai/metadata/DemoMetadataCatalog.java`：+ `stat_indicator` →「统计指标库」注释。
    - `ai/planner/AnalysisPlanner.java`：表注释优先取目录注释，gov 标签仅兜底（对既有表行为不变，rg 已扫调用方）。
- **验证（L3 全链路，真实后端+MySQL）**：
  - 「邵阳经济趋势」→ STAT_TREND / stat_indicator / GDP 期间序列 / 101 行
  - 「2025年1-9月全市GDP」→ STAT_TREND / stat_indicator / 101 行
  - 「各区县财政收入排名」→ STAT_RANKING / bar / 13 行（区县财政收入排序）
  - L1：`SqlValidatorTest` 新增 stat_indicator 用例，相关单测全绿（MVN_EXIT=0）。
- **回退方案**：删除 `docs/sql/stat-analysis-config.sql` 新增配置行 + 撤销 2 处 Java 白名单/注释增量即可。
- **剩余待办**：
  1. 设 `AI_API_KEY` 重启后端 → LLM 模式按问题自适应生成 stat_indicator SQL（规则模式已验证兜底可用）。
  2. 2023/2024 全市 GDP 年度缺口、三产增加值绝对值（占比）待从官方公报补数。
  3. 可选：22 篇跨站旧公报详情页无正文（原页面下线），可剔除或标注。