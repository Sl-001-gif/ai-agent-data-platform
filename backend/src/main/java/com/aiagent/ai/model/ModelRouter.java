package com.aiagent.ai.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按任务用途路由 AI 模型：SQL 生成/纠错优先 sql 配置，报告生成优先 report 配置，
 * 意图识别/图表/解读/计划等其余步骤用 text 配置；查不到配置时回退 yml 默认值。
 */
@Service
public class ModelRouter {

    /** 路由到的模型配置。 */
    public record ModelConfig(String name, String modelName, String endpoint, String apiKey,
                              int maxTokens, double temperature) {
    }

    static final String CONFIG_SQL = "SELECT * FROM ai_model_config WHERE status = 1";
    static final int DEFAULT_MAX_TOKENS = 2048;
    static final double DEFAULT_TEMPERATURE = 0.2;

    private final JdbcTemplate jdbcTemplate;
    private final String defaultModelName;
    private final String defaultEndpoint;
    private final String defaultApiKey;

    public ModelRouter(JdbcTemplate jdbcTemplate,
                       @Value("${ai.model.model-name:gpt-4o}") String defaultModelName,
                       @Value("${ai.model.endpoint:https://api.openai.com/v1}") String defaultEndpoint,
                       @Value("${ai.model.api-key:}") String defaultApiKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaultModelName = defaultModelName;
        this.defaultEndpoint = defaultEndpoint;
        this.defaultApiKey = defaultApiKey;
    }

    /** 依据步骤类型解析模型配置；DB 无匹配配置时回退默认。 */
    public ModelConfig resolve(String stepType) {
        String key = resolveConfigKey(stepType);
        ModelConfig picked = pickConfig(queryConfigs(), key);
        return picked != null ? picked : fallbackConfig();
    }

    /** 依据步骤类型解析模型配置；modelConfigId 非空时优先使用该启用配置，找不到则回退默认路由。 */
    public ModelConfig resolve(String stepType, Long modelConfigId) {
        if (modelConfigId != null) {
            ModelConfig override = pickById(queryConfigs(), modelConfigId);
            if (override != null) {
                return override;
            }
        }
        return resolve(stepType);
    }

    /** 从配置行中按 id 选取启用配置；无匹配返回 null（便于单测）。 */
    static ModelConfig pickById(List<Map<String, Object>> rows, Long id) {
        if (rows == null || id == null) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            Object cellId = row.get("id");
            if (cellId != null && id.equals(Long.valueOf(String.valueOf(cellId)))) {
                return toConfig(row);
            }
        }
        return null;
    }

    /** 步骤类型 → 配置名关键字：SQL / REPORT / 其余(text)。 */
    static String resolveConfigKey(String stepType) {
        String type = stepType == null ? "" : stepType.toUpperCase(Locale.ROOT);
        if (type.contains("SQL")) {
            return "sql";
        }
        if (type.contains("REPORT")) {
            return "report";
        }
        return "text";
    }

    /** 从配置行中选取 name 含关键字的配置；无匹配返回 null（便于单测）。 */
    static ModelConfig pickConfig(List<Map<String, Object>> rows, String key) {
        if (rows == null) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            String name = cell(row, "name");
            if (name.toLowerCase(Locale.ROOT).contains(key)) {
                return toConfig(row);
            }
        }
        return null;
    }

    /** 行数据 → ModelConfig（便于单测）。 */
    static ModelConfig toConfig(Map<String, Object> row) {
        return new ModelConfig(
                cell(row, "name"),
                cell(row, "model_name"),
                cell(row, "endpoint"),
                cell(row, "api_key"),
                intCell(row, "max_tokens", DEFAULT_MAX_TOKENS),
                doubleCell(row, "temperature", DEFAULT_TEMPERATURE));
    }

    /** 配置表不存在/查询失败时容错为空。 */
    private List<Map<String, Object>> queryConfigs() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(CONFIG_SQL);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** DB 中是否存在启用且带 API Key 的模型配置（供 LlmClient 判断 LLM 是否可用）。 */
    public boolean hasUsableConfig() {
        for (Map<String, Object> row : queryConfigs()) {
            Object status = row.get("status");
            boolean enabled = status == null || "1".equals(String.valueOf(status));
            if (enabled && notBlank(cell(row, "api_key"))) {
                return true;
            }
        }
        return false;
    }

    private ModelConfig fallbackConfig() {
        String env = System.getenv("AI_API_KEY");
        String key = env != null && !env.isBlank() ? env : defaultApiKey;
        return new ModelConfig("default", defaultModelName, defaultEndpoint, key, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }

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

    private static int intCell(Map<String, Object> row, String key, int defaultValue) {
        try {
            return Integer.parseInt(cell(row, key));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double doubleCell(Map<String, Object> row, String key, double defaultValue) {
        try {
            return Double.parseDouble(cell(row, key));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
