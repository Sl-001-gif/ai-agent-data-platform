package com.aiagent.ai.metadata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final JdbcTemplate jdbcTemplate;
    private final DemoMetadataCatalog metadataCatalog;

    public MetadataService(JdbcTemplate jdbcTemplate, DemoMetadataCatalog metadataCatalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataCatalog = metadataCatalog;
    }

    /** 查询并拼装元数据文本；table_schema 无数据时回退演示目录。 */
    public String buildMetadataText() {
        List<Map<String, Object>> datasetRows = safeQuery(DATASET_SQL);
        List<Map<String, Object>> tableRows = safeQuery(TABLE_SQL);
        List<Map<String, Object>> fieldRows = safeQuery(FIELD_SQL);
        List<Map<String, Object>> metricRows = safeQuery(METRIC_SQL);
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
            String formula = cell(metric, "formula");
            String description = cell(metric, "description");
            sb.append("- ").append(metricName).append(" = ").append(formula);
            if (!description.isEmpty()) {
                sb.append("（").append(description).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
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
    private List<Map<String, Object>> safeQuery(String sql) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
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