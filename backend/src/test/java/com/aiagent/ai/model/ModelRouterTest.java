package com.aiagent.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRouterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ModelRouter router =
            new ModelRouter(jdbcTemplate, "gpt-4o-default", "https://api.openai.com/v1", "");

    @Test
    void shouldPickSqlConfigForSqlStep() {
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL))
                .thenReturn(List.of(configRow("sql-generator", "gpt-4o-sql"), configRow("text-analyzer", "gpt-4o-text")));

        ModelRouter.ModelConfig config = router.resolve("SQL");

        assertEquals("sql-generator", config.name(), "SQL 步骤应选 sql 配置");
        assertEquals("gpt-4o-sql", config.modelName());
    }

    @Test
    void shouldPickTextConfigForIntentStep() {
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL))
                .thenReturn(List.of(configRow("sql-generator", "gpt-4o-sql"), configRow("text-analyzer", "gpt-4o-text")));

        ModelRouter.ModelConfig config = router.resolve("INTENT");

        assertEquals("text-analyzer", config.name(), "INTENT 步骤应选 text 配置");
        assertEquals("gpt-4o-text", config.modelName());
    }

    @Test
    void shouldFallbackToDefaultModelWhenNoConfigs() {
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL)).thenReturn(List.of());

        ModelRouter.ModelConfig config = router.resolve("SQL");

        assertEquals("gpt-4o-default", config.modelName(), "无配置时应回退默认 modelName");
        assertEquals("https://api.openai.com/v1", config.endpoint());
        assertEquals("default", config.name());
    }

    @Test
    void shouldUseOverrideModelById() {
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL))
                .thenReturn(List.of(configRowWithId(1L, "text-analyzer", "gpt-4o-text"),
                        configRowWithId(2L, "report-generator", "gpt-4o-report")));

        ModelRouter.ModelConfig config = router.resolve("REPORT", 1L);

        assertEquals("text-analyzer", config.name(), "指定 id 时应优先使用该配置");
        assertEquals("gpt-4o-text", config.modelName());
    }

    @Test
    void shouldFallbackWhenOverrideIdMissing() {
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL))
                .thenReturn(List.of(configRowWithId(1L, "text-analyzer", "gpt-4o-text"),
                        configRowWithId(2L, "report-generator", "gpt-4o-report")));

        ModelRouter.ModelConfig config = router.resolve("REPORT", 999L);

        assertEquals("report-generator", config.name(), "指定 id 不存在时回退按用途路由");
    }

    @Test
    void shouldDetectUsableConfigWithApiKey() {
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL))
                .thenReturn(List.of(configRowWithKey("sql-deepseek", "deepseek-chat", "sk-test")));

        assertTrue(router.hasUsableConfig(), "启用且带 Key 的配置应视为 LLM 可用");
    }

    @Test
    void shouldNotDetectConfigWithoutApiKey() {
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL))
                .thenReturn(List.of(configRowWithKey("sql-deepseek", "deepseek-chat", "")));

        assertFalse(router.hasUsableConfig(), "启用但无 Key 的配置不应视为 LLM 可用");
    }

    @Test
    void shouldNotDetectDisabledConfigWithApiKey() {
        Map<String, Object> row = configRowWithKey("sql-deepseek", "deepseek-chat", "sk-test");
        row.put("status", 0);
        when(jdbcTemplate.queryForList(ModelRouter.CONFIG_SQL)).thenReturn(List.of(row));

        assertFalse(router.hasUsableConfig(), "停用配置即使带 Key 也不应视为 LLM 可用");
    }

    private static Map<String, Object> configRow(String name, String modelName) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("model_name", modelName);
        return map;
    }

    private static Map<String, Object> configRowWithKey(String name, String modelName, String apiKey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("model_name", modelName);
        map.put("status", 1);
        map.put("api_key", apiKey);
        return map;
    }

    private static Map<String, Object> configRowWithId(Long id, String name, String modelName) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("model_name", modelName);
        return map;
    }
}