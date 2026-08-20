package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.prompt.PromptLoader;
import com.aiagent.mapper.AiConfigMapper;
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
    private final PromptLoader promptLoader = new PromptLoader(mock(AiConfigMapper.class));
    private final LlmSqlGenerator generator =
            new LlmSqlGenerator(llmClient, validator, ruleGenerator, metadataService, modelRouter, new ObjectMapper(), promptLoader);

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
    @Test
    void shouldUseLlmSqlForStatMonthlyWhenConfigured() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("SELECT period, region, value, unit FROM stat_monthly WHERE indicator_name = '地区生产总值' AND region = '全市'");
        AnalysisPlan plan = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值"),
                List.of("期间", "指标"), "近3年", "line", List.of());

        SqlGenerator.GeneratedSql result = generator.generate(plan,
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.8, List.of("生产总值")));

        assertEquals("LLM", result.generatorType(), "配置 key 后 stat_monthly 全量走 LLM");
        assertTrue(result.sql().contains("FROM stat_monthly"), "LLM SQL 应指向 stat_monthly");
        verify(llmClient, times(1)).chat(anyString(), anyString());
    }

    @Test
    void shouldFallbackToRuleForStatMonthlyWhenLlmInvalid() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("SELECT 1; DROP TABLE stat_monthly")
                .thenReturn("SELECT 1; DROP TABLE stat_monthly");
        AnalysisPlan plan = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值"),
                List.of("期间", "指标"), "近3年", "line", List.of());

        SqlGenerator.GeneratedSql result = generator.generate(plan,
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.8, List.of("生产总值")));

        assertEquals("RULE", result.generatorType(), "LLM 两次校验失败应回退规则");
        assertTrue(result.sql().contains("FROM stat_monthly"), "规则 SQL 应指向 stat_monthly");
        verify(llmClient, times(2)).chat(anyString(), anyString());
    }
    @Test
    void shouldScopeMetadataByPlanDatasetId() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(metadataService.buildMetadataText(23L)).thenReturn("【表结构】stat_monthly");
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("SELECT period, region, value, unit FROM stat_monthly WHERE indicator_name = '地区生产总值' AND region = '全市'");
        AnalysisPlan plan = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值"),
                List.of("期间", "指标"), "近3年", "line", List.of());
        plan.setDatasetId(23L);

        SqlGenerator.GeneratedSql result = generator.generate(plan,
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.8, List.of("生产总值")));

        assertEquals("LLM", result.generatorType());
        verify(metadataService).buildMetadataText(23L);
        verify(metadataService, never()).buildMetadataText();
    }

    @Test
    void shouldUseRuleForStatRegionSnapshotPlan() {
        when(llmClient.isConfigured()).thenReturn(true);
        AnalysisPlan plan = new AnalysisPlan("stat_monthly", "统计月报", List.of("一般公共预算收入"),
                List.of("区县"), "近3年", "bar", List.of());

        SqlGenerator.GeneratedSql result = generator.generate(plan,
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.8, List.of("排名")));

        assertEquals("RULE", result.generatorType(), "排名快照应走规则引擎避免 LLM 忽略维度");
        assertTrue(result.sql().contains("region <> '全市'"), "规则 SQL 应按区县取数: " + result.sql());
        verify(llmClient, never()).chat(anyString(), anyString());
    }

    @Test
    void shouldUseRuleForStatSnapshotPlan() {
        when(llmClient.isConfigured()).thenReturn(true);
        AnalysisPlan plan = new AnalysisPlan("stat_monthly", "统计月报", List.of("产业投资"),
                List.of("指标"), "最新期间", "bar", List.of());

        SqlGenerator.GeneratedSql result = generator.generate(plan,
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.8, List.of("最新")));

        assertEquals("RULE", result.generatorType(), "最新期间快照应走规则引擎避免 LLM 臆造期别");
        assertTrue(result.sql().contains("period = (SELECT period FROM stat_monthly"), "规则 SQL 应动态取最新期别: " + result.sql());
        verify(llmClient, never()).chat(anyString(), anyString());
    }

}
