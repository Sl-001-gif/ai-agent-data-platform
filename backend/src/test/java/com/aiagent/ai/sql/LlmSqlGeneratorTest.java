package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmSqlGeneratorTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final SqlValidator validator = new SqlValidator();
    private final RuleSqlGenerator ruleGenerator = new RuleSqlGenerator();
    private final MetadataService metadataService = mock(MetadataService.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final LlmSqlGenerator generator =
            new LlmSqlGenerator(llmClient, validator, ruleGenerator, metadataService, modelRouter, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(metadataService.buildMetadataText()).thenReturn("【表结构】gov_info_record");
    }

    @Test
    void shouldFallbackToRuleWhenLlmNotConfigured() {
        when(llmClient.isConfigured()).thenReturn(false);

        SqlGenerator.GeneratedSql result = generator.generate(govPlan(), govIntent());

        assertEquals("RULE", result.generatorType(), "未配置 Key 应回退 RULE");
        assertTrue(result.sql().contains("FROM gov_info_record"), "回退 SQL 应指向 gov 表");
        verify(llmClient, never()).chat(anyString(), anyString());
    }

    @Test
    void shouldUseLlmSqlWhenValid() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("```sql\nSELECT category, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category\n```");

        SqlGenerator.GeneratedSql result = generator.generate(govPlan(), govIntent());

        assertEquals("LLM", result.generatorType(), "校验通过应标记 LLM");
        assertEquals("SELECT category, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category", result.sql(),
                "SQL 应去除 markdown 围栏");
        assertFalse(result.sql().contains("```"), "SQL 不应含围栏");
    }

    @Test
    void shouldFallbackToRuleWhenBothAttemptsInvalid() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("SELECT 1; DROP TABLE x")
                .thenReturn("SELECT 1; DROP TABLE y");

        SqlGenerator.GeneratedSql result = generator.generate(govPlan(), govIntent());

        assertEquals("RULE", result.generatorType(), "两次校验失败应回退 RULE");
        verify(llmClient, times(2)).chat(anyString(), anyString());
    }

    @Test
    void shouldRetryOnceAndSucceedOnSecondAttempt() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("SELECT 1; DROP TABLE x")
                .thenReturn("SELECT category, COUNT(*) FROM gov_info_record GROUP BY category");

        SqlGenerator.GeneratedSql result = generator.generate(govPlan(), govIntent());

        assertEquals("LLM", result.generatorType(), "重试成功后应标记 LLM");
        assertFalse(result.sql().contains(";"), "SQL 不应含分号");
        verify(llmClient, times(2)).chat(anyString(), anyString());
    }

    private static AnalysisPlan govPlan() {
        return new AnalysisPlan("GOV_INFO_RECORD", "政府公开信息记录", List.of("文档数"), List.of("分类"), "近30天", "bar", List.of());
    }

    private static RecognizedIntent govIntent() {
        return new RecognizedIntent("STRUCTURE", "信息结构分析", 0.9, List.of("分类"));
    }
}