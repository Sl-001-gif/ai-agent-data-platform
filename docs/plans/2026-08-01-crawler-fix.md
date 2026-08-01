# 政务爬虫修复计划（tools/scraper）

- 日期：2026-08-01
- 状态：✅ 全量抓取进行中（706 条）；**发现并修复正文栏目/专题/友情链接噪音**（政策文件/统计信息页嵌栏目块绕过容器过滤）；噪音过滤规则已加固（test 39→47）；待爬完执行最终清理（32 条）→ 保留约 674 条纯净记录
- 治理：tools/scraper 属 🟢 低风险独立工具（后端零调用方）；实施超 100 行限额，**需用户确认超限**

## 一、背景与目标
修复 `tools/scraper/gov_scraper.py` 三个已知问题，产出可用的政务公开数据集（供 AI 引擎 GOV_INFO_RECORD 链路分析）：
1. 导航噪音约 12 条（首页/机构设置/网站地图等被当条目收录）
2. 分页 `?page=N` 失效（真实分页机制未知，可能 JS 渲染/特殊参数）
3. 新宁县源 `list_url` 是占位符，需补真实 URL

## 二、三角色工作流结论
| 角色 | 产出 | 结论 |
|------|------|------|
| Agent A 分析师 | 根因分析 + 验收标准 + 影响分析 + 用户核实清单 | 风险 🟢、后端零调用方、预算预估超限 |
| Agent B 实现者 | 噪音过滤/分页配置化/probe_site.py/README 更新 | 32/32 桩自测通过（沙箱无 bs4） |
| Agent C 审查者 | 越界检查 + 验收对照 + 问题清单 | **有条件通过**：P0 无；P1×5、P2×5 |

## 三、已落地修复
### 3.1 主脚本 gov_scraper.py（532 → 756 行）
- 噪音过滤：标题子串/精确双黑名单、URL 特征黑/白名单、容器 class/id **词元**黑名单（`_match_token_any`，避免 `page` 误伤 `page-content`）；`filter_candidate_link` 返回可解释过滤原因；`--verbose` 打印
- 分页配置化：`sources[*].pagination` → `{mode: auto|next-link|page-param|none, page_param, page_start}`（旧字符串兼容）；`normalize_pagination`/`decide_next_url` 按模式决策
- 诊断增强：未找到「下一页」且未达上限时显式提示；首页无条目时提示可能解析失败/JS 渲染；`noise_filter.print_reasons=True` 等价 `--verbose`（P1-3 修复）
- xinning：`confirmed: False` + 启动告警
### 3.2 probe_site.py（新增）
用户本机探测：HTTP 状态/编码、列表容器候选、链接样本、分页控件线索、保存 HTML 到 `probe_output/`
### 3.3 test_gov_scraper.py（新增，P2-1）
不联网回归测试：误杀（进入汛期/招标详情标题）、漏杀（导航词）、容器词元匹配、分页全分支 —— **24/24 通过**（沙箱 codex python 直驱）
### 3.4 其他
- README：pagination/noise_filter 配置说明、probe 用法、已知限制补充
- .gitignore：`__pycache__/`、`*.pyc`、`probe_output/`

## 四、P1 修复对照（Agent C 审查意见 → 处置）
| # | 审查意见 | 处置 |
|---|---------|------|
| P1-1 | 容器子串匹配 `page`/`link` 可团灭正文 | 已改词元匹配 + 移除泛词，测试通过 |
| P1-2 | `进入`/`详情`/`首页` 子串黑名单误杀真实标题 | 已移入精确名单，测试通过 |
| P1-3 | `print_reasons` 配置无效 | 已接入 crawl，README 一致 |
| P1-4 | 分页识别失败静默 | 已加显式提示（两处） |
| P1-5 | 预算超限需用户确认 | **本计划即确认请求**：净增约 224 行（gov_scraper）+174（probe）+89（README），3 个主文件 + 1 测试文件 |

## 五、验收标准（三层）
- L1（沙箱已过）：过滤规则误杀/漏杀回归、容器词元匹配、分页归一化与 decide_next_url 全分支、build_page_url —— test_gov_scraper.py 24/24
- L2（本机）：`--pages 2` 连跑两次 → 第二次新增=0；元数据 4 表无重复；`--verbose` 核对过滤原因无误杀
- L3（本机联网）：
  - 邵阳：`probe_site.py` 输出分页线索 → 按需设 `pagination.mode/page_param` → 抓 3 页 → 导航噪音=0、跨页 URL 重复=0、publish_date 填充率≥90%
  - 新宁：用户提供真实目录 URL → 替换 `xinning.list_url` + `confirmed: True` → 跑通 ≥20 条

## 六、用户本机操作步骤
```bash
# 1. 安装依赖（如未装）
pip install requests beautifulsoup4 pymysql

# 2. 探测邵阳目录页（反馈：容器 class/id、分页控件类型、第 2 页 URL 形态）
python probe_site.py https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml

# 3. 按探测结果调整 CONFIG（pagination.mode/page_param、noise_filter），试抓 1 页核对过滤
python gov_scraper.py --pages 1 --source shaoyang --verbose

# 4. 正式抓取并核对
python gov_scraper.py --pages 3 --source shaoyang

# 5. 幂等验证：再跑一次，确认「新增 0 条」
python gov_scraper.py --pages 3 --source shaoyang
```
新宁县：提供 `https://www.xinning.gov.cn` 政务公开目录页真实 URL 后，替换 `CONFIG["sources"]["xinning"]["list_url"]` 并将 `confirmed` 改为 `True`，再 `python gov_scraper.py --source xinning --pages 2`。

## 七、遗留与后续
- P2-2：probe_site.py 增加 `--probe-pages 2` 直接输出「?page=N 是否生效」结论（可选增强）
- P2-3：xinning `confirmed: False` 时默认拒绝执行（需 `--force` 放行）（可选增强）
- 噪音清理：如历史已入库噪音行需清理，单独执行 `DELETE FROM gov_info_record WHERE source_url LIKE '%/col/%' OR title IN (导航词...)`（用户确认后执行）
- 数据可用后：进入下一阶段「数据源动态切源」或先以政务数据做分析验证
## 六·补充：邵阳探测结果（2026-08-01 20:33 用户本机实测）
- 页面标题「邵阳市人民政府政府信息公开目录系统」，**目录为 zTree 动态树**：静态 HTML 无任何真实记录链接（15 个 li 全为导航/栏目链接），这就是「?page=N 失效、无下一页」的根因
- 树接口（从保存 HTML 内联脚本提取）：
  - `projectName='shaoyang'`；`channelId='979dc6eb921b418a9355bc6eb2eac8f4'`
  - `GET /u/channel/treeNew/shaoyang`（JSONP `JsonpCallBack`），参数 `level/pid/channelId/catecode/open/target/published_file_name=/zwgk/template_name=zwgk_right2,...`
  - 树节点响应含 `iframeurl`（该类目记录列表页），页面用 iframe 加载
- 结论：邵阳源需新增 **tree 模式**（拉树 → 取各叶子类目 iframeurl → 用现有 parse_list/分页逻辑抓列表）；现有 generic 列表爬法不适用于该目录入口页
- 待用户核实（下一步命令见下）：树 JSONP 响应结构 + 任一类目列表页 URL 与分页形态
- 遗留：历史入库 9 条导航噪音（首页/走进邵阳/信息公开/政务服务等），待清理脚本（用户确认后执行）
## 八、tree 模式实现（2026-08-01 20:58 完成）
- 现状：邵阳入口页为 zTree 动态树壳（静态 HTML 无真实记录），`?page=N` 失效根因即此；已实现 tree 模式：
  - `fetch_tree()`：拉取 `GET /u/channel/treeNew/shaoyang`（JSONP，参数取自入口页内联脚本），解析 49 个类目节点
  - `resolve_category_list_url()`：`/xxx/null.shtml` 占位自动探测 `xlist/xxgkList/xxgklist/list/index` 变体，返回首个 200；全部失败返回 None 并跳过
  - `crawl_tree()`：跨站/占位失败类目自动跳过；`--categories 政策文件,统计信息` 按节点 name 精确筛选
  - `crawl()` 增 `list_url`/`category_override` 参数，复用既有列表解析/分页/入库逻辑
- 真实类目 URL（13/49）：统计信息 `stjgb/xlist.shtml`、文件库 `sbjgfxwj/gfxwjlist.shtml`、财政信息 `sbjzfyjs/xlist_djlb.shtml`、
  政策解读 `gfxwjjd/nzcjd.shtml`、重大会议信息 `zfcwhyjjd/xlist.shtml`、规章库 `gzk/gzk.shtml`、机构简介 `szfjg/xzfjg.shtml`、
  重大决策预公开 `nzdjcygk/zdjcyg.shtml`、主动公开事项目录 `zfgksxml/gksxml.shtml` 等；行政许可/处罚强制为跨站链接（跳过）
- `zfsj/xsjfb.shtml`（数据发布页）：真实列表但仅 8 条统计公报、无分页 → 不作为主源
- 测试：`test_gov_scraper.py` 24 → 36（新增 fetch_tree JSONP 解包 6 例 + resolve_category_list_url 变体探测 6 例），沙箱直驱全绿；`py_compile` 通过

## 九、用户本机验证（tree 模式）
```powershell
cd 'D:\codestudy\大学项目\aiagent数据分析平台\tools\scraper'
# 试跑：只抓 2 个类目 2 页，核对真实记录 / 噪音 / 分页
python gov_scraper.py --source shaoyang --categories 政策文件,统计信息 --pages 2 --verbose
#   核对 1) 出现 [树] 共获取 49 个目录节点 → 按筛选后逐类目抓取
#   核对 2) 标题均为真实政务条目；--verbose 过滤原因无误杀
#   核对 3) 第二页有新条目？若重复/空 → 该类目页分页为 JS 渲染，把 pagination.mode 改 next-link/none
# 正式抓取（建议 500~2000 条：8 个真实类目 × 各 10 页）
python gov_scraper.py --source shaoyang --pages 10 --verbose
# 幂等：再跑一次应「新增 0 条」
```
- 若类目页分页形态与探测不符，按实际情况调整 `sources.shaoyang.pagination` 后重跑
- 历史 9 条导航噪音清理（用户确认后执行）：
  `DELETE FROM gov_info_record WHERE title IN (导航词列表) OR source_url LIKE '%/col/%'`
## 十、分页自动发现链（2026-08-01 21:30 实现，test 39/39）
- 探测结论（用户本机 21:17 实测）：
  - `stjgb/xlist.shtml`（统计公报）：`createPageHTML('page_div',10, 1,'xlist','shtml',189)` → **10 页 / 189 条**，分页为后缀式 `xlist_N.shtml`（JS 生成链接，HTML 无锚点、`?page=N` 无效）
  - `zcwjf/xlist.shtml`（政策文件）：`createPageHTML('page_div',1, 1,'xlist','shtml',5)` → **仅 1 页 / 5 条**
- 实现：`gov_scraper.py` auto 模式升级为**分页自动发现链**（纯正则、不依赖 bs4）：
  1. 页面内「下一页」链接
  2. 后缀式分页：解析 `createPageHTML` 双签名（含 `'page_div',count,cur,'xlist','shtml',total` 变体，按当前 URL 目录+prefix/ext 推导 `xlist_N.shtml`）与页面内 `xlist_2.shtml` 锚点；按 pageCount 自动封顶
  3. 参数名发现：页面内 `?pageNo=2` 等链接 → 自动改用该参数
  4. 兜底：配置的 `page_param` 递增
  - 安全网：翻页无新内容 / 请求失败立即停止，绝不空跑或死循环
- 测试：`test_gov_scraper.py` 36 → 39（新增签名 B 检测、后缀式翻页、pageCount=1 单页停止），沙箱直驱全绿；真实 HTML（211701/211707）检测验证通过
- probe_site.py 同步增强：JS 分页线索（createPageHTML/pageCount/turnPage）、后缀式分页链接样本、`--probe-pages N` 参数翻页生效性判断

## 十一、用户本机最终验证（全量抓取）
```powershell
cd 'D:\codestudy\大学项目\aiagent数据分析平台\tools\scraper'
# 先只抓两个已确认类目（统计公报 10 页 / 政策文件 1 页）
python gov_scraper.py --source shaoyang --categories 政策文件,统计信息 --pages 12 --verbose
#   预期：统计信息出现 [分页] 检测到后缀式分页 ... pageCount=10，逐页 xlist_2..10.shtml，共约 189 条
#   预期：政策文件 pageCount=1，单页 5 条后自动停止
# 全量抓取 13 个真实类目（建议 500~2000 条数据集档）
python gov_scraper.py --source shaoyang --pages 12 --verbose
# 幂等：再跑一次应「新增 0 条」
```
- 若某类目后缀页 404/无新内容 → 该模板分页不同，脚本自动停止并打印，反馈后按该类目单独调 `pagination`
## 十二、本机实测结果（2026-08-01 21:30~21:35 用户本机）
- 试跑 `--categories 政策文件,统计信息 --pages 12 --verbose`：
  - 政策文件：pageCount=1 自动封顶，单页解析 21 条（页面实际展示 21 条，createPageHTML 的 total=5 为旧计数，不影响抓取）
  - 统计信息：`[分页] 检测到后缀式分页 ... pageCount=10`，自动抓 `xlist_2..10.shtml`，共解析唯一 202 条
  - 噪音过滤 0 混入（全部导航链接带原因过滤）
- 全量抓取后库现状（截至 21:35）：**301 条唯一记录**（统计信息 202 / 事业单位年度报告公示 75 / 政策文件 11 / 其他类目 4 / NULL 9）
- 历史噪音 **12 条**待清理（用户确认后执行）：
```sql
-- 清理前可先 SELECT 核对：应返回 12 行（6 条导航 + 6 条 zwgk_right 壳页）
-- SELECT id, title, source_url FROM gov_info_record
--  WHERE title IN ('首页','走进邵阳','信息公开','政务服务','政民互动','政府数据','政务要闻','市政府','机构设置','领导信箱','网站地图','联系我们')
--    OR source_url LIKE '%/zwgk_right%' OR source_url LIKE '%/default/xhtml/zwgk/%';
DELETE FROM gov_info_record
 WHERE title IN ('首页','走进邵阳','信息公开','政务服务','政民互动','政府数据','政务要闻','市政府','机构设置','领导信箱','网站地图','联系我们')
    OR source_url LIKE '%/zwgk_right%' OR source_url LIKE '%/default/xhtml/zwgk/%';
```
- 扩量建议：`python gov_scraper.py --source shaoyang --pages 12 --verbose` 多轮增量抓取至 500~2000 条；再跑一次验证「新增 0 条」幂等
## 十三、噪音问题二：正文栏目/专题/友情链接混入（2026-08-01 21:45 发现并修复）
- 现象：`zcwjf/xlist.shtml`（政策文件）与 `stjgb/xlist.shtml`（统计信息）页面**正文内嵌栏目块**（发展六仗专题、友情链接、政务微博/智能问答、侧栏"统计公报/统计月报/统计分析"），所在容器无 nav/header 特征 → 绕过容器过滤被当记录收录
- 影响：政策文件 21 条中 12 条为栏目链接；统计信息中 3 条侧栏链接；另有历史演示种子 4 条（/xxgk/1~4）
- 修复（gov_scraper.py 默认规则）：
  - `title_exact_blacklist` 增：政务要闻/统计公报/统计月报/统计分析/政务微博/智能问答/国家部委网站/全国各省政府网站/本省市州政府网/县市区政府网/市直单位政府网站/发展六仗 6 条
  - `href_blacklist` 增：`/xlist`（栏目列表页）、`zwgk_right`（目录壳页）、`/end_link`（友情链接页）——真实记录 URL 均为 `/类目/年月/hash.shtml`，不受影响
  - 跨站真实统计公报（国家统计局/湖南省统计局 URL）**保留**（真实数据，非噪音）
- 测试：test_gov_scraper.py 39 → 47（新增栏目/外部链接过滤 7 例 + 真实记录不误杀 1 例），沙箱直驱全绿
- 最终清理（爬完当前轮后执行，预计删 32 行、保留 674 行）：
```sql
DELETE FROM gov_info_record
 WHERE title IN ('首页','走进邵阳','信息公开','政务服务','政民互动','政府数据','政务要闻','市政府','机构设置','领导信箱','网站地图','联系我们','统计公报','统计月报','统计分析','政务微博','智能问答','国家部委网站','全国各省政府网站','本省市州政府网','县市区政府网','市直单位政府网站','打好经济增长主动仗','打好科技创新攻坚仗','打好优化发展环境持久仗','打好防范化解风险阻击仗','打好安全生产翻身仗','打好重点民生保障仗')
    OR source_url LIKE '%/zwgk_right%' OR source_url LIKE '%/default/xhtml/zwgk/%'
    OR source_url LIKE '%/xlist%' OR source_url LIKE '%/end_link%'
    OR source_url IN ('https://shaoyang.gov.cn/xxgk/1','https://xinning.gov.cn/xxgk/2','https://shaoyang.gov.cn/xxgk/3','https://shaoyang.gov.cn/xxgk/4');
```
- 全量抓取后库 3752 条；**最终清理（预计删 256 条、保留 3496 条）**：在上一版条件上追加"发展六仗专题类目"（6 个专题 224 条新闻，属宣传专题非目录类目，重跑会再次收录）：
```sql
DELETE FROM gov_info_record
 WHERE title IN ('首页','走进邵阳','信息公开','政务服务','政民互动','政府数据','政务要闻','市政府','机构设置','领导信箱','网站地图','联系我们','统计公报','统计月报','统计分析','政务微博','智能问答','国家部委网站','全国各省政府网站','本省市州政府网','县市区政府网','市直单位政府网站','打好经济增长主动仗','打好科技创新攻坚仗','打好优化发展环境持久仗','打好防范化解风险阻击仗','打好安全生产翻身仗','打好重点民生保障仗')
    OR source_url LIKE '%/zwgk_right%' OR source_url LIKE '%/default/xhtml/zwgk/%'
    OR source_url LIKE '%/xlist%' OR source_url LIKE '%/end_link%'
    OR source_url IN ('https://shaoyang.gov.cn/xxgk/1','https://xinning.gov.cn/xxgk/2','https://shaoyang.gov.cn/xxgk/3','https://shaoyang.gov.cn/xxgk/4')
    OR category IN ('打好科技创新攻坚仗','打好安全生产翻身仗','打好经济增长主动仗','打好重点民生保障仗','打好防范化解风险阻击仗','打好优化发展环境持久仗');
```
- **防复发**：`sources.shaoyang.tree.skip_names` 已配置 6 个发展六仗专题名（tree 层），重跑自动跳过；噪音过滤规则（title 精确 + `/xlist`/`zwgk_right`/`end_link`）已加固
- 清理后复跑同命令应「新增 0 条」

## 十五、幂等复跑实测：复跑新增 2 条导航噪音，过滤器补漏（2026-08-01 22:51 用户复跑 / 23:0x 修复）
- 现象：用户复跑 `python gov_scraper.py --source shaoyang --pages 12` 后库 3496 → 3498，新增 2 条导航噪音：`政府数据`（zfsj/xsjfb.shtml）、`政民互动`（zmhd/tyhd_index.shtml），错误分类为「政策文件」，create_time 22:51:36/38。
- 根因：`title_exact_blacklist` 缺 `政府数据/政民互动`；`href_blacklist` 未覆盖门户栏目壳页后缀（rlist/llist/lvlist/gfxwjlist/nzcjd/xszf/nzfjg/zdjcyg/dfxfghgz/wjk_jump/xxgkjbml/tyhd_index/xsjfb/yshjzqt/rlist_c 等）。
- 修复（gov_scraper.py 内置默认规则）：
  - `title_exact_blacklist` 增：`政府数据`、`政民互动`
  - `href_blacklist` 增：`list.shtml`（覆盖部门子站栏目列表页 rlist/llist/lvlist 等；真实记录 URL 为 /类目/年月/hash.shtml 不受影响）、`nzcjd.shtml`、`zdjcyg.shtml`、`dfxfghgz.shtml`、`wjk_jump.shtml`、`xszf.shtml`、`nzfjg.shtml`、`xxgkjbml.shtml`、`tyhd_index.shtml`、`xsjfb.shtml`、`yshjzqt.shtml`、`rlist_c.shtml`
  - 误杀回归：跨站真实内容（gov.cn/微信/省统计局）与互动平台真实公告（hd_myzj_content.html?conId=）保留
- 测试：test_gov_scraper.py 47 → 55（新增导航/壳页 6 例 + 跨站保留 2 例），沙箱直驱全绿
- 数据清理：已删除 22:51 复跑新增的 2 条（id 5458/5459），库回到 3496 条、0 重复、0 导航残留
- 遗留：库中仍有 **82 条历史栏目壳页/列表页噪音**（部门法定主动公开矩阵 52 / 行政事业收费和物价调控 14 / 文件库 9 / 政策解读 2 / 重大会议信息 2 / 政府工作部门 1 / 主动公开事项目录 1 / 重大决策预公开 1），URL 均不含 /YYYYMM/ 日期段，与「真实记录 URL 均为 /类目/年月/hash.shtml」标准不符；清理 SQL 如下（**用户确认后执行**，执行后约 3414 条）：

```sql
-- 先核对：应返回 82 行
-- SELECT id, title, category, source_url FROM gov_info_record
--  WHERE source_url NOT REGEXP '/20[0-9]{2}[01][0-9]/'
--    AND (source_url LIKE '%list.shtml%' OR source_url LIKE '%nzcjd.shtml%'
--      OR source_url LIKE '%zdjcyg.shtml%' OR source_url LIKE '%dfxfghgz.shtml%'
--      OR source_url LIKE '%wjk_jump.shtml%' OR source_url LIKE '%xszf.shtml%'
--      OR source_url LIKE '%nzfjg.shtml%' OR source_url LIKE '%xxgkjbml.shtml%'
--      OR source_url LIKE '%tyhd_index.shtml%' OR source_url LIKE '%xsjfb.shtml%'
--      OR source_url LIKE '%yshjzqt.shtml%' OR source_url LIKE '%rlist_c.shtml%');
DELETE FROM gov_info_record
 WHERE source_url NOT REGEXP '/20[0-9]{2}[01][0-9]/'
   AND (source_url LIKE '%list.shtml%' OR source_url LIKE '%nzcjd.shtml%'
     OR source_url LIKE '%zdjcyg.shtml%' OR source_url LIKE '%dfxfghgz.shtml%'
     OR source_url LIKE '%wjk_jump.shtml%' OR source_url LIKE '%xszf.shtml%'
     OR source_url LIKE '%nzfjg.shtml%' OR source_url LIKE '%xxgkjbml.shtml%'
     OR source_url LIKE '%tyhd_index.shtml%' OR source_url LIKE '%xsjfb.shtml%'
     OR source_url LIKE '%yshjzqt.shtml%' OR source_url LIKE '%rlist_c.shtml%');
```

## 十六、最终收尾实测（2026-08-01 23:xx 用户复跑完成）
- 复跑结果：树模式抓取完成——**新增 195 | 更新 636 | 未变 3360 | 失败 1**
- 82 条栏目壳页清理已执行（用户确认「清理」）；过滤器补漏后**壳页 0 复活、导航 0**
- 复跑补抓第一轮漏抓的真实类目（树接口超时曾跳过）：规划计划、市直部门规范性文件等，新增 195 条全部为 `/YYYYMM/hash.shtml` 真实记录，幂等机制正常（重复抓不重复入库，只补漏）
- **最终数据：gov_info_record 3601 条唯一记录**（0 重复 / 0 空标题 / 0 壳页 / 0 导航 / 日期填充 99.94% / 约 45 类目 / 2017~2026 均衡；日期缺失仅 2 条）
- 遗留：`publish_unit` 全表为空（3601/3601）、`doc_no` 仅 13 条有值 → 下一阶段先补发文单位提取（见 `docs/plans/2026-08-02-gov-analysis.md`）
- 全部变更已提交：88eb6e6（爬虫修复）+ 95eefdb（文档）+ 本次数据收尾文档