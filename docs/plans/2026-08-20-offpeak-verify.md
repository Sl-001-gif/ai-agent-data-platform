<!-- status: PENDING -->

# 错峰测试待办：aiagent数据分析平台 stat_monthly 深度测试收尾验证（含阶段4 分类树验证）

> 依据技能 `offpeak-test-defer`：2026-08-20 周四 10:08 属 DeepSeek 高峰全价时段（北京时间 09:00-12:00、14:00-18:00），全量/回归测试与构建禁止内联；执行窗口按 **DeepSeek 空闲时段（token 半价）** 安排：00:00-09:00、12:00-14:00、18:00-24:00。
> 本环境无 automation_update 工具 → 按技能约束写入本待办（PENDING）。**DeepSeek 空闲时段（北京时间 00:00-09:00、12:00-14:00、18:00-24:00，token 半价）打开 Codex 时，本会话开场将自动检测到该标记并自动执行**（见 `offpeak-test-defer` 技能「非高峰自动启动」节）。

## 背景（已完成的交互会话内工作，勿重复）
- 深度测试第 3 轮 145 题：140 PASS / 5 FAIL（外贸外资-Q07 + 跨期-Q02/Q06/Q07/Q09）。
- 5 个 FAIL 根因已定位为**计划/生成层代码缺陷**（非数据）：
  1. RANK_METRICS 顺序缺陷：「投资排名」泛化词先于「外商直接投资排名」命中 → 指标错配成固定资产投资。
  2. 无年份的「最新/9月末/是多少」快照问法被判成 STAT_TREND 近3年趋势 → LLM 臆造年度期别（1-12月）或写错年份（2024 vs 2025）。
- 已实施修复（4 个主文件，改动均在 stat_monthly 分支，不碰承重墙/数据）：
  - `backend/src/main/java/com/aiagent/ai/planner/AnalysisPlanner.java`：rankMetricFor 最长命中优先；refineStatTrendSpec 新增单期快照分支（最新期间/指标维度/bar）；中长期消费贷款别名改精确名。
  - `backend/src/main/java/com/aiagent/ai/sql/RuleSqlGenerator.java`：非区县快照 SELECT 增加 period 列。
  - `backend/src/main/java/com/aiagent/ai/sql/LlmSqlGenerator.java`：最新期间快照计划直走规则引擎；SYSTEM_PROMPT 新增规则 11（最新期别以实际数据为准）。
  - `backend/src/main/java/com/aiagent/util/TimeRangeParser.java`：「N月末」不再解析为「近N月」。
- 定向 L1 测试已内联跑过（63 项全绿）：AnalysisPlannerStatSnapshotTest(7) + 既有受影响类。

## 高峰期间已确认的遗留问题（回归范围，勿与本次修复混淆）
- `AnalysisReportIntegrationTest` 4 例失败（execute 400 + 3 级联 NPE）已在阶段 3 定位修复（全量 354/354 全绿），非高峰不再作为遗留问题处理。

## 环境现状（2026-08-20 上午核查）
- 后端 8080 与前端 5173 **当前均未运行**（仅 MySQL 3306 在线），非高峰第 1 步前需先启动前端 dev（`cd frontend && npm run dev`）与后端 jar。

## 非高峰执行步骤（DeepSeek 空闲时段内，一条命令串）
1. 全量回归：`mvn test`（期望 **366 全绿** = 阶段3 354 + 新增 StatCategoryServiceTest 12；AnalysisReportIntegrationTest 4 例已在阶段 3 修复纳入回归范围，若复现则定位根因）。
2. 重建后端 jar：`mvn -f backend\pom.xml package -DskipTests`（或 `install`），注意旧 jar 被进程占用时先停 8080 进程再 Move-Item 改名。
3. 启动后端（带 AI_API_KEY，后台）：
   `$env:AI_API_KEY=...; Start-Process -WindowStyle Hidden 'C:\Users\13975\.jdks\openjdk-22.0.1\bin\java.exe' -ArgumentList '-jar','backend\target\ai-agent-data-platform-1.0.0-SNAPSHOT.jar'`，等 `Started AgentApplication`。
4. 深度测试第 4 轮（全量 145 题，全量 LLM）：
   - `cd tools\_test_stat`；先归档第 3 轮：`Move-Item results results_round3`（不存在则跳过）；
   - `node deep_test.mjs --concurrency=3`（PREFIX 政务部员深度测试1，dataset 23）。
5. 汇总：`python tools\_test_stat\gen_report.py`（生成 汇总报告.md + 汇总表.csv 到 _test_stat）。
6. q7 三层验证：
   ① 接口层：结果文件断言 columns/rows/unit/chartType（汇总表核对）；
   ② chartOption 脚本级：`node -e "import('file:///D:/codestudy/大学项目/aiagent数据分析平台/frontend/src/utils/chartOption.js').then(m=>{...})"` 用第 4 轮真实 rows 跑 buildChartOption 断言无异常；
   ③ 浏览器：`node tools\_pwtest\smoke_chart.cjs`（需前端 `npm run dev` 在 5173 运行，用 Edge）。
7. 预期验收：145 题全 PASS（重点复核原 5 FAIL：外贸外资-Q07、跨期-Q02/06/07/09）；无回归（其余 140 不降级）。
8. 汇报格式：Tests run 汇总 / 失败用例清单 / 根因与修复建议；并把 汇总表.csv 路径给用户便于人工检查。
9. 全部完成后按用户原要求**自动关机**：`shutdown /s /t 60`（先输出结果再关）。

## 追加：阶段 4（统计指标分类树管理）验证（2026-08-20 上午会话新增）
新增内容（已写完，未编译/构建/运行验证）：
- 后端 5 文件：`entity/StatCategory.java`、`mapper/StatCategoryMapper.java(+xml)`、`service/StatCategoryService.java`、`controller/StatCategoryController.java`（`/api/admin/stat-category` GET tree / POST / PUT{id} / DELETE{id}）、测试 `test/.../service/StatCategoryServiceTest.java`（12 例）。
- 前端 4 文件：`api/statCategory.js`、`views/admin/StatCategory.vue`（el-tree 三级树 + 增/改/删对话框）、`router/index.js` 路由 `stat-category`、`Layout.vue` 菜单「统计指标分类」。
- 已内联 javac 语法编译通过（4 主类 + 1 测试类，exit 0）；未跑单测/未构建。

非高峰执行（在既有第 1 步全量回归中并入）：
1. 定向先跑：`mvn test -Dtest=StatCategoryServiceTest`（期望 12 项全绿）。
2. 全量回归期望 354 + 12 = **366 全绿**。
3. `npm run build` 前端构建通过（含新页面/路由/菜单）。
4. 后端重启后接口烟测（admin token）：
   - `GET /api/admin/stat-category/tree` → 10 根 / 34 中类 / 225 叶子；
   - POST 新增一级分类 → PUT 改名 → DELETE 删除（含「有子节点拒绝删除」1 例）。
5. 浏览器烟测 `http://localhost:5173/stat-category`：树形展示、展开全部、新增下级、编辑、删除。
6. 遗留待用户确认：阶段 1 树与库差异（树 225 vs 库 227：`利润总额` 已改名、`客运量(万人)`/`旅客周转量(万人公里)` 未入树）——需修 `build_stat_category.py` 过期 `ALIAS_MATCH_EXTRA` 后重跑 `--tree` 并同步库表，用户确认后再做。

---

## 已创建定时任务（2026-08-20 10:15 补充，10:45 按用户建议改为 13:00/19:00 双触发）
- 任务名：`offpeak-aiagent-verify`（Windows 任务计划程序，每日重复）
- 触发：**每日 13:00 与 19:00**（两触发器，均属 DeepSeek 空闲半价窗口：12:00–14:00、18:00–24:00；MultipleInstancesPolicy=IgnoreNew，13:00 未跑完时 19:00 不重复启动）。Interactive only，需用户保持登录。
- 执行：`tools\_test_stat\offpeak_run.cmd`（内部调用 `offpeak_run.ps1` 并将输出落盘 `_offpeak_cmd.log`）。
- 执行模式（10:46 脚本已改，防深夜无人值守跑不完）：**默认快验证**——只跑 `mvn test` 全量 + 重建 jar + 重启后端 + `npm run build` + pwtest 烟测，约 1 小时内完成；**145 题深度测试需 `-IncludeDeep` 参数**，默认不随定时任务执行，改由「打开 Codex 非高峰开场自动执行」（人在场可随时中断），符合「大晚上睡觉电脑出情况无法解决」的顾虑。
- 自动关机：仅 `-IncludeDeep` 且当日 21:00 后触发且深度测试成功才自动关机；快验证模式 / 13:00 / 19:00 触发一律不关机（脚本已在 10:45 加时间守卫）。
- 取消：`schtasks /Delete /TN offpeak-aiagent-verify /F`；关中断言：`shutdown /a`；任务 XML 备份：`tools\_test_stat\offpeak-aiagent-verify.xml`

### 14:55 故障修复与快验证结果（2026-08-20 下午记录）
- **故障根因**：13:00 定时任务 Last Result=1 且无日志——`offpeak_run.cmd` 为 UTF-8 硬编码中文路径，cmd（GBK）解析报 `'cy' is not recognized`，powershell 未启动。
- **修复**：`offpeak_run.cmd` 改为 `%~dp0` 自引用（纯 ASCII）+ `exit /b %ERRORLEVEL%`；已手动复跑验证 cmd exit=0。
- **快验证结果（14:52-14:54 内联）**：mvn test 全量 **380/380 绿**（含 StatCategoryServiceTest 12 例）；npm run build **exit=0**；后端 8080 已带新 jar 重启、前端 5173 已启动；**pwtest scenario2 PASS，scenario1 FAIL**。
- **pwtest scenario1 遗留**：「各区县进出口总额增速排名」返回 X 轴仅 5 项市州区域（大湘西/洞庭湖/环长株潭/湘南/长株潭），断言要求 ≥12 区县 → **stat_monthly 进出口无区县级口径，规则/LLM 回退到分市州数据**；属数据口径缺陷，待确认后修（提示无区县口径或按市州展示）。
- 19:00 定时任务将用修复后的 cmd 自动重跑快验证（预计成功；若 user 在场可不跑深度测试）。
- 脚本：`tools\_test_stat\offpeak_run.ps1`（含 mvn test → 重建 jar → 重启后端 → 第4轮145题 → gen_report → chartOption 检查 → npm build → pwtest → 汇总落盘 → 自动关机）
- 汇总输出：`tools\_test_stat\错峰汇总-2026-08-20.md` + 汇总表.csv/汇总报告.md
- 取消：`schtasks /Delete /TN offpeak-aiagent-verify /F`；关中断言：`shutdown /a`
- LLM key：从 DB `ai_model_config`(id 1/2/3) 读取，无需环境变量；id=18 为测试残留假 key（sk-不应入），建议 status=0 清理
