# 3A 政务数据爬虫（tools/scraper）

自然语言数据分析平台的配套数据采集工具：抓取邵阳市/新宁县两级政府门户网站**信息公开目录**中依法主动公开的政务信息，结构化入库并同步写入平台元数据（数据集/表结构/字段语义/指标口径），供后续 Text-to-SQL 与统计分析使用。

## 功能

- 遍历信息公开列表页：分页行为由数据源 `pagination` 配置驱动（`auto` / `next-link` / `page-param` / `none`），
  `auto` 优先找页面内"下一页"链接，缺失时按 `page_param` 参数递增，最多 `max_pages` 页
- **tree 模式（目录树）**：数据源 `type: "tree"` 时先拉取目录树接口（JSONP），按 `--categories` 筛选后逐类目抓列表；
  占位 URL（`null.shtml`）自动探测 `xlist/xxgkList/list/index` 变体，真实 URL 直接抓，跨站/解析失败类目自动跳过
- **噪音链接过滤**：可组合规则（标题子串/精确黑名单、URL 特征黑/白名单、容器 class/id 黑/白名单），
  命中即过滤并可用 `--verbose` 打印每条被过滤链接及原因，便于核对与调参
- 解析条目：标题、原文链接（绝对化）、发布日期、公开单位、公开类目、文号、摘要（200 字截断）
- 去重：单次运行内存 `seen` 集合按 URL 去重；入库按 `source_url` 唯一键幂等
- 发布日期缺失时自动访问详情页补充一次（顺带补文号/单位/类目）
- 自动建表 `gov_info_record` 并幂等写入元数据：`dataset` / `table_schema` / `table_field` / `metric_definition`
- 单条记录入库异常容错继续，不影响整批抓取

## 依赖安装

```bash
pip install requests beautifulsoup4 pymysql
```

Python 3.8+，无其他第三方依赖。

## 站点结构探测（probe_site.py，本机联网运行）

**配置/修改数据源前先探测**，避免按猜测写配置：

```bash
python probe_site.py https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml
python probe_site.py URL --out probe_output --limit 20 --timeout 15
python probe_site.py URL --out probe_output --limit 20 --timeout 15
# 参数翻页探测：额外抓取 ?page=2..N 与第1页对比，输出「参数翻页是否生效」结论
python probe_site.py URL --probe-pages 3
```

输出内容：

| 输出项 | 用途 |
|--------|------|
| HTTP 状态 / 最终 URL / 编码 / 页面大小 | 确认入口可访问、编码正确 |
| 分页控件线索（下一页链接 / 表单 action / script 分页参数 / JS 分页函数 createPageHTML 等 / 后缀式分页链接 _2.shtml） | 确定 `pagination.mode` 与 `page_param`，或发现真实分页形态 |
| 列表容器候选（li/tr 含链接容器数量 + top 容器 class/id） | 校准 `container_*` 过滤规则 |
| 前 N 条链接样本（标题 + 绝对化 href） | 核对真实条目与导航链接的区别 |
| 分页控件线索（下一页链接 / 表单 action / script 分页参数） | 确定 `pagination.mode` 与 `page_param` |
| 原始 HTML 保存到本地 `probe_output/*.html` | 离线分析、把结果反馈给开发 |

## 用法

```bash
# 默认：邵阳数据源，最多 5 页
python gov_scraper.py

# 指定页数、数据源，并打印被过滤的噪音链接及原因
python gov_scraper.py --pages 5 --source shaoyang --verbose

# 新宁县数据源（需先配置实际 URL，见下文）
python gov_scraper.py --pages 5 --source xinning

# tree 模式：只抓指定类目（名称与树节点 name 精确匹配，逗号分隔）
python gov_scraper.py --source shaoyang --categories 政策文件,统计信息 --pages 2 --verbose
```

参数：

| 参数 | 说明 | 默认 |
|------|------|------|
| `--pages N` | 分页抓取上限 | 5（CONFIG 中 `max_pages`） |
| `--source NAME` | 数据源，可选 `shaoyang` / `xinning` | `shaoyang` |
| `--categories A,B` | tree 模式：只抓指定类目（精确匹配树节点 name，逗号分隔） | 全部类目 |
| `--verbose` | 打印每条被过滤的噪音链接及原因 | 关闭 |

## CONFIG 说明

`gov_scraper.py` 顶部 `CONFIG` 区可统一修改，同时支持环境变量覆盖（无需改代码）：

| 配置项 | 说明 | 默认值 | 环境变量 |
|--------|------|--------|----------|
| `mysql.host` | MySQL 地址 | `localhost` | `MYSQL_HOST` |
| `mysql.port` | MySQL 端口 | `3306` | `MYSQL_PORT` |
| `mysql.user` | MySQL 用户名 | `root` | `MYSQL_USER` |
| `mysql.password` | MySQL 密码 | `Admin@123456` | `MYSQL_PASSWORD` |
| `mysql.db` | 数据库名 | `ai_agent_data` | `MYSQL_DB` |
| `max_pages` | 分页上限 | `5` | —（用 `--pages` 覆盖） |
| `request_interval` | 请求间隔（秒） | `1.0` | `SCRAPER_INTERVAL` |
| `sources` | 数据源配置（URL / 分页 / confirmed） | 见下 | — |
| `noise_filter` | 噪音过滤规则（追加项） | 见下 | — |

示例：

```bash
set MYSQL_PASSWORD=你的密码
python gov_scraper.py --pages 3
```

### sources[*].pagination（分页策略，结构化配置）

旧写法字符串 `"auto"` 仍兼容，等价于 `{"mode": "auto", "page_param": "page", "page_start": 1}`。

| 字段 | 说明 |
|------|------|
| `mode` | `auto`=优先"下一页"链接，缺失时自动发现分页（见下）；`next-link`=只认"下一页"链接；`page-param`=直接用参数翻页；`none`=只抓首页 |
| `page_param` | 参数翻页使用的查询参数名（探测 script 后可确认，如 `pageNo`/`pageIndex`） |
| `page_start` | 参数翻页起始页码（默认 1） |
| `max_pages` | 可选，覆盖全局 `max_pages` |

示例（邵阳发现真实分页参数是 `pageNo` 且首页从 0 开始）：

```python
"pagination": {"mode": "page-param", "page_param": "pageNo", "page_start": 0, "max_pages": 10},
```

**auto 模式自动发现链（无需人工探测即可适配多数政府站）：**

1. 页面内「下一页」链接
2. 后缀式分页：`createPageHTML(...)`（Sohu CMS 模板，如 `createPageHTML('page_div',10,1,'xlist','shtml',189)`）或页面内 `xlist_2.shtml` 锚点 → 生成 `xlist_2.shtml` 翻页，并按 pageCount 自动封顶
3. 参数名发现：页面内出现 `?pageNo=2` 等链接 → 自动改用该参数
4. 兜底：配置的 `page_param` 递增

运行时安全网：翻页后无新内容 / 请求失败立即停止，绝不空跑或死循环。

### noise_filter（噪音链接过滤规则）

配置项均为**追加**到内置默认集合（标题子串黑名单 / 标题精确黑名单 / URL 黑名单 / 容器 class-id 黑名单），
去重后生效；白名单为空列表 = 不启用。

| 字段 | 说明 |
|------|------|
| `min_title_len` | 标题最短长度，默认 4 |
| `title_blacklist` | 标题子串黑名单（追加） |
| `title_exact_blacklist` | 标题精确黑名单（追加） |
| `href_blacklist` | href 子串黑名单（追加，匹配前统一小写） |
| `href_whitelist` | href 子串白名单（非空时启用：href 必须命中其一） |
| `container_blacklist` | 容器 class/id **词元**黑名单（追加，含祖先节点；按非字母数字切词整词比对，避免 `page` 误伤 `page-content`） |
| `container_whitelist` | 容器 class/id 子串白名单（非空时启用） |
| `print_reasons` | True 时打印每条被过滤链接及原因（等价 `--verbose`） |

示例（某站点正文链接都带 `/content/`，容器都在 `id=zwgk-list` 内，可收紧）：

```python
"noise_filter": {
    "href_whitelist": ["/content/"],
    "container_whitelist": ["zwgk-list"],
    "print_reasons": True,
},
```


## 噪音过滤内置默认规则补充

- 内置默认规则（代码 `_TITLE_NAV_EXACT_BLACKLIST` / `_HREF_NAV_BLACKLIST`）除导航标题/URL/容器黑名单外，另含**门户栏目壳页后缀**：`list.shtml`（覆盖部门子站栏目列表页 rlist/llist/lvlist 等）、`nzcjd.shtml`、`zdjcyg.shtml`、`dfxfghgz.shtml`、`wjk_jump.shtml`、`xszf.shtml`、`nzfjg.shtml`、`xxgkjbml.shtml`、`tyhd_index.shtml`、`xsjfb.shtml`、`yshjzqt.shtml`、`rlist_c.shtml`
- 真实记录 URL 为 `/类目/年月/hash.shtml`，不受上述后缀影响；跨站真实内容（gov.cn/微信/省统计局）与互动平台真实公告（`hd_myzj_content.html?conId=`）保留
## 数据源与新宁县 URL 占位

- `shaoyang`（默认，已验证入口）：
  `https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml`
  - **入口页是 zTree 动态树壳**（静态 HTML 无真实记录），脚本走 `type: "tree"` 模式：
    拉取 `GET /u/channel/treeNew/shaoyang`（JSONP，参数见 CONFIG `sources.shaoyang.tree`）→ 49 个类目节点 →
    13 个带真实 URL（统计信息 `stjgb/xlist.shtml`、文件库 `sbjgfxwj/gfxwjlist.shtml`、财政信息 `sbjzfyjs/xlist_djlb.shtml`、
    政策解读 `gfxwjjd/nzcjd.shtml`、重大会议信息 `zfcwhyjjd/xlist.shtml`、规章库 `gzk/gzk.shtml`、机构简介 `szfjg/xzfjg.shtml` 等），
    其余 36 个 `null.shtml` 占位自动探测变体；`zfsj/xsjfb.shtml`（数据发布）仅 8 条公报无分页，不作为主源
- `xinning`：**占位符** `https://www.xinning.gov.cn/xxgk/xxzwgkList.shtml` 为示例地址，**尚未经用户确认**，
  配置中 `confirmed: False`。运行 `--source xinning` 会打印启动告警。
  **待用户提供**新宁县人民政府官网"信息公开"栏目目录页的实际 URL（可用 `probe_site.py` 找到）后：
  1. 替换 `CONFIG["sources"]["xinning"]["list_url"]`
  2. 按探测结果设置 `pagination` 与 `noise_filter`
  3. 删除或改为 `"confirmed": True`

## 验收口径（修复后如何核对）

1. **噪音过滤**：`python gov_scraper.py --pages 1 --source shaoyang --verbose`，
   核对 stderr 中每条 `[过滤]` 原因是否合理、正文条目未被误杀（真实标题不应出现在过滤列表）
2. **分页**：跑 `probe_site.py` 拿到真实分页线索后，按线索设置 `pagination`；
   `auto` 模式日志出现"下一页链接"说明页面内翻页可用；出现"分页参数未生效"说明该站忽略参数，改用 `next-link`/`none`
3. **数据质量**：入库后抽查 `gov_info_record`，无导航类标题（网站地图/联系我们/下一页 等），URL 均为正文原文

## 重跑幂等说明

- 单次运行：内存 `seen` 集合按 URL 去重，同一页/跨页重复条目只处理一次
- 跨次运行：`gov_info_record.source_url` 为 UNIQUE 键，写入使用 `INSERT ... ON DUPLICATE KEY UPDATE`，
  重复抓取只会更新已有记录内容，不会产生重复行
- 元数据表（`dataset` / `table_schema` / `table_field` / `metric_definition`）均采用"查重后插入"策略，
  同名/同键记录自动跳过，重复运行安全

## 入库内容

业务表 `gov_info_record`（自动 `CREATE TABLE IF NOT EXISTS`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT AUTO_INCREMENT PK | 主键 |
| `title` | VARCHAR(500) | 标题 |
| `doc_no` | VARCHAR(100) | 文号 |
| `publish_unit` | VARCHAR(200) | 公开单位 |
| `category` | VARCHAR(100) | 公开类目 |
| `publish_date` | DATE | 发布日期 |
| `source_url` | VARCHAR(500) UNIQUE | 原文链接（幂等键） |
| `summary` | TEXT | 摘要（200 字） |
| `create_time` | DATETIME | 入库时间 |

元数据幂等写入：

- `dataset`：`name='邵阳政务信息公开数据'`，description 记录抓取来源与合规声明，连接信息填实际值，`status=1`
- `table_schema`：`gov_info_record` / `政府信息公开记录`，指向 dataset
- `table_field`：title/doc_no/publish_unit/category/publish_date/source_url/summary 各一行，含业务语义，`is_metric=0`
- `metric_definition`：4 条指标（发文量、类目占比、平均每日发文量、单位发文量），含计算口径 SQL，`status=1`

## 合规声明

- 数据来源均为邵阳市、新宁县人民政府门户网站**依法主动公开**的政务信息，符合数据使用规范
- 本项目抓取仅用于学术研究与政务公开优化分析，不涉及非公开/涉密内容
- 脚本内置请求间隔与分页上限，建议保持默认值，遵守目标网站服务条款，避免高频抓取

## 已知限制

- 各政府网站列表页 HTML 结构不同，`parse_list()` 使用通用 `li`/`tr` 容器 + 最长文本链接启发式；
- 容器黑名单按词元匹配（如 `nav`/`pagination`），但仍可能命中正文容器；若 `--verbose` 显示全部条目被过滤，先检查页面主容器的 class/id 是否含黑名单词，用 `container_blacklist` 无法解决时把内置词挪走（见 `_CONTAINER_NAV_BLACKLIST`）或改用 `container_whitelist` 收紧；
  若目标站点结构特殊导致解析不全，需按实际页面微调选择器或过滤规则
- 噪音过滤默认规则为保守集合（避免误伤含"信息公开/政务公开"字样的真实标题），
  若仍混入少量导航链接，用 `--verbose` 观察后按需追加 `noise_filter` 配置
- 站点无"下一页"链接且不支持 `page_param` 参数时，脚本检测到无新内容会自动提前停止（只抓第 1 页）
- 发布日期、文号、单位、类目依赖页面文本中的规范写法，提取不到时留空，不影响入库
