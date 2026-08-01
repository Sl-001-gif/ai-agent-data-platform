# 3A 政务数据爬虫（tools/scraper）

自然语言数据分析平台的配套数据采集工具：抓取邵阳市/新宁县两级政府门户网站**信息公开目录**中依法主动公开的政务信息，结构化入库并同步写入平台元数据（数据集/表结构/字段语义/指标口径），供后续 Text-to-SQL 与统计分析使用。

## 功能

- 遍历信息公开列表页：优先找页面内"下一页"链接，找不到则按 `?page=N` 递增，最多 `max_pages` 页
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

## 用法

```bash
# 默认：邵阳数据源，最多 5 页
python gov_scraper.py

# 指定页数与数据源
python gov_scraper.py --pages 5 --source shaoyang

# 新宁县数据源（需先配置实际 URL，见下文）
python gov_scraper.py --pages 5 --source xinning
```

参数：

| 参数 | 说明 | 默认 |
|------|------|------|
| `--pages N` | 分页抓取上限 | 5（CONFIG 中 `max_pages`） |
| `--source NAME` | 数据源，可选 `shaoyang` / `xinning` | `shaoyang` |

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
| `sources` | 数据源 URL 列表 | 见下 | — |

示例：

```bash
set MYSQL_PASSWORD=你的密码
python gov_scraper.py --pages 3
```

## 数据源与新宁县 URL 占位

- `shaoyang`（默认，已验证入口）：
  `https://www.shaoyang.gov.cn/shaoyang/xxgk/xxzwgkList.shtml`
- `xinning`：**占位符** `https://www.xinning.gov.cn/xxgk/xxzwgkList.shtml` 为示例地址，**尚未经用户确认**。
  **待用户提供**新宁县人民政府官网"信息公开"栏目目录页的实际 URL 后，替换
  `CONFIG["sources"]["xinning"]["list_url"]` 再运行 `--source xinning`。

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
  若目标站点结构特殊导致解析不全，需按实际页面微调选择器
- 站点无"下一页"链接且不支持 `?page=N` 时，脚本检测到无新内容会自动提前停止（只抓第 1 页）
- 发布日期、文号、单位、类目依赖页面文本中的规范写法，提取不到时留空，不影响入库