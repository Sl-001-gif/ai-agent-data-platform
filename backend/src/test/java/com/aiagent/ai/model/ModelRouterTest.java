package com.aiagent.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static Map<String, Object> configRow(String name, String modelName) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("model_name", modelName);
        return map;
    }
}