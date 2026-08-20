package com.aiagent.ai.metadata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分析元数据服务：读取数据集/表结构/字段语义/指标口径，拼装成注入 LLM Prompt 的结构化文本。
 * 业务表不存在或为空时回退内置演示元数据，保证无数据环境也能生成 SQL。
 */
@Service
public class MetadataService {

    static final String DATASET_SQL = "SELECT * FROM dataset WHERE status = 1 LIMIT 1";
    static final String TABLE_SQL = "SELECT * FROM table_schema WHERE status = 1";
    static final String FIELD_SQL =
            "SELECT f.*, s.table_name FROM table_field f JOIN table_schema s ON f.table_id = s.id WHERE s.status = 1";
    static final String METRIC_SQL = "SELECT * FROM metric_definition WHERE status = 1";
    static final String DATASET_BY_ID_SQL = "SELECT * FROM dataset WHERE status = 1 AND id = ?";
    static final String TABLE_BY_DATASET_SQL = "SELECT * FROM table_schema WHERE status = 1 AND dataset_id = ?";
    static final String FIELD_BY_DATASET_SQL =
            "SELECT f.*, s.table_name FROM table_field f JOIN table_schema s ON f.table_id = s.id WHERE s.status = 1 AND s.dataset_id = ?";
    static final String METRIC_BY_DATASET_SQL = "SELECT * FROM metric_definition WHERE status = 1 AND (dataset_id = ? OR dataset_id IS NULL)";

    /** 统计长表（stat_monthly/stat_indicator）的期别与口径说明，注入 LLM Prompt 防累计值误读。 */
    static final String PERIOD_SEMANTICS = "\n【统计口径与期别说明】\n"
            + "- period 为「累计期别」：YYYY年1-3月=一季度累计、1-6月=上半年累计、1-9月=前三季度累计、1-12月=全年累计；"
            + "同一年各期别为累计到期末的关系，不可相加，跨期别不可直接比较。\n"
            + "- 趋势与增幅必须对比「上年同期累计期别」（如 2025年1-9月 对比 2024年1-9月），不得用一季度累计与三季度累计算增幅。\n"
            + "- growth_rate 字段已是「比上年同期累计增长(%)」，直接引用，不要重复计算。\n"
            + "- 城乡收入比 = 城镇居民人均可支配收入 ÷ 农村居民人均可支配收入（同地区同期别），严禁用全体居民作分母。\n"
            + "- 数值格式：金额统一千分位并保留 2 位小数（如 38,575.59 元），增速统一保留 1 位小数并加 %（如 4.9%）。";

    private final JdbcTemplate jdbcTemplate;
    private final DemoMetadataCatalog metadataCatalog;

    public MetadataService(JdbcTemplate jdbcTemplate, DemoMetadataCatalog metadataCatalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataCatalog = metadataCatalog;
    }

    /** 查询并拼装元数据文本；table_schema 无数据时回退演示目录。 */
    public String buildMetadataText() {
        return buildMetadataText(null);
    }

    /** 按数据集构建元数据文本；datasetId 为空时全库。 */
    public String buildMetadataText(Long datasetId) {
        List<Map<String, Object>> datasetRows = datasetId == null
                ? safeQuery(DATASET_SQL) : safeQuery(DATASET_BY_ID_SQL, datasetId);
        List<Map<String, Object>> tableRows = datasetId == null
                ? safeQuery(TABLE_SQL) : safeQuery(TABLE_BY_DATASET_SQL, datasetId);
        List<Map<String, Object>> fieldRows = datasetId == null
                ? safeQuery(FIELD_SQL) : safeQuery(FIELD_BY_DATASET_SQL, datasetId);
        List<Map<String, Object>> metricRows = datasetId == null
                ? safeQuery(METRIC_SQL) : safeQuery(METRIC_BY_DATASET_SQL, datasetId);
        if (tableRows.isEmpty()) {
            return buildDemoText(metadataCatalog.listTables());
        }
        return buildTextFromRows(datasetRows, tableRows, fieldRows, metricRows);
    }

    /** 按行列数据拼装结构化元数据文本（便于单测）。 */
    static String buildTextFromRows(List<Map<String, Object>> datasetRows,
                                    List<Map<String, Object>> tableRows,
                                    List<Map<String, Object>> fieldRows,
                                    List<Map<String, Object>> metricRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("【数据集】\n");
        for (Map<String, Object> row : safeList(datasetRows)) {
            String name = cell(row, "name");
            String description = cell(row, "description");
            if (!name.isEmpty()) {
                sb.append("- ").append(name);
                if (!description.isEmpty()) {
                    sb.append("（").append(description).append("）");
                }
                sb.append("\n");
            }
        }
        sb.append("【表结构】\n");
        for (Map<String, Object> table : safeList(tableRows)) {
            String tableName = cell(table, "table_name");
            String comment = cell(table, "comment");
            sb.append("- 表: ").append(tableName);
            if (!comment.isEmpty()) {
                sb.append("（").append(comment).append("）");
            }
            sb.append("\n");
            for (Map<String, Object> field : safeList(fieldRows)) {
                if (!tableName.isEmpty() && !tableName.equalsIgnoreCase(cell(field, "table_name"))) {
                    continue;
                }
                sb.append("  - 字段: ").append(cell(field, "field_name"))
                        .append(" | 类型: ").append(typeOf(field))
                        .append(" | 注释: ").append(cell(field, "comment"))
                        .append(" | 业务含义: ").append(cell(field, "business_meaning"))
                        .append(" | 是否指标: ").append(isMetric(field) ? "是" : "否")
                        .append("\n");
            }
        }
        sb.append("【指标口径】\n");
        for (Map<String, Object> metric : safeList(metricRows)) {
            String metricName = cell(metric, "metric_name");
            if (metricName.isEmpty()) {
                metricName = cell(metric, "name");
            }
            String formula = cell(metric, "calculation_formula");
            if (formula.isEmpty()) {
                formula = cell(metric, "formula");
            }
            String description = cell(metric, "description");
            sb.append("- ").append(metricName).append(" = ").append(formula);
            if (!description.isEmpty()) {
                sb.append("（").append(description).append("）");
            }
            sb.append("\n");
        }
        if (containsStatTable(tableRows)) {
            sb.append(PERIOD_SEMANTICS);
        }
        return sb.toString();
    }

    /** 是否包含统计长表（period 累计期别语义仅对其适用）。 */
    private static boolean containsStatTable(List<Map<String, Object>> tableRows) {
        for (Map<String, Object> table : safeList(tableRows)) {
            String name = cell(table, "table_name");
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith("stat_")) {
                return true;
            }
        }
        return false;
    }

    /** 回退：按内置演示目录拼装。 */
    static String buildDemoText(List<DemoMetadataCatalog.DemoTable> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("【数据集】\n").append("- 演示数据集（内置演示元数据）\n");
        sb.append("【表结构】\n");
        for (DemoMetadataCatalog.DemoTable table : tables) {
            sb.append("- 表: ").append(table.name()).append("（").append(table.comment()).append("）\n")
                    .append("  - 维度: ").append(String.join(", ", table.dimensions())).append("\n")
                    .append("  - 指标: ").append(String.join(", ", table.metrics())).append("\n");
        }
        return sb.toString();
    }

    /** 表不存在/查询失败时容错为空。 */
    private List<Map<String, Object>> safeQuery(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = args == null || args.length == 0
                    ? jdbcTemplate.queryForList(sql) : jdbcTemplate.queryForList(sql, args);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
    private static List<Map<String, Object>> safeList(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    /** 大小写不敏感取单元格值，兼容不同 JDBC 返回的列名大小写。 */
    private static String cell(Map<String, Object> row, String key) {
        if (row == null) {
            return "";
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return "";
    }

    private static String typeOf(Map<String, Object> row) {
        String type = cell(row, "field_type");
        return type.isEmpty() ? cell(row, "data_type") : type;
    }

    private static boolean isMetric(Map<String, Object> row) {
        String value = cell(row, "is_metric").toLowerCase();
        return "1".equals(value) || "true".equals(value);
    }
}
