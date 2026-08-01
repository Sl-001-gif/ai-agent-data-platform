package com.aiagent.ai.report;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单测：报告生成的 LLM 优先与三级降级（业务模板 / 极简数据报告）。 */
class ReportGeneratorTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final MetadataService metadataService = mock(MetadataService.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final ReportGenerator generator = new ReportGenerator(llmClient, metadataService, modelRouter);

    private AnalysisPlan plan;
    private RecognizedIntent intent;

    @BeforeEach
    void setUp() {
        when(metadataService.buildMetadataText()).thenReturn("【指标口径】销售额 = SUM(sales_amount)");
        plan = new AnalysisPlan("order_info", "订单表", List.of("销售额"), List.of("日期"), "近30天", "line",
                List.of("INTENT", "PLAN", "SQL", "VALIDATE", "EXECUTE", "CHART", "INTERPRET", "REPORT"));
        intent = new RecognizedIntent("SALES_TREND", "销售趋势", 0.9, List.of("销售", "趋势"));
    }

    private SqlExecutor.ExecutionResult result(List<Map<String, Object>> rows) {
        return new SqlExecutor.ExecutionResult(List.of("order_date", "sales_amount"), rows, rows.size());
    }

    private Map<String, Object> row(String date, double amount) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("order_date", date);
        r.put("sales_amount", amount);
        return r;
    }

    @Test
    void shouldFallbackToRuleTemplateWhenLlmNotConfigured() {
        when(llmClient.isConfigured()).thenReturn(false);

        ReportGenerator.ReportResult report = generator.generate(
                plan, intent, result(List.of(row("2026-07-01", 100), row("2026-07-02", 150))), "解读正文", "分析销售趋势");

        assertEquals("RULE", report.generatorType());
        assertEquals("SALES_TREND", report.templateName());
        assertTrue(report.content().contains("订单表"));
        assertTrue(report.content().contains("共查询 2 行"));
        assertTrue(report.content().contains("解读正文"));
        assertTrue(report.content().contains("销售额"));
        verify(llmClient, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void shouldUseLlmTextWhenConfigured() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("# 报告\n\n## 概述\nLLM 内容。");

        ReportGenerator.ReportResult report = generator.generate(
                plan, intent, result(List.of(row("2026-07-01", 100))), "", "分析销售趋势");

        assertEquals("LLM", report.generatorType());
        assertNull(report.templateName());
        assertEquals("# 报告\n\n## 概述\nLLM 内容。", report.content());
        verify(modelRouter).resolve("REPORT");
    }

    @Test
    void shouldFallbackToRuleWhenChatThrows() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenThrow(new RuntimeException("LLM 不可用"));

        ReportGenerator.ReportResult report = generator.generate(
                plan, intent, result(List.of(row("2026-07-01", 100))), "解读", "分析销售趋势");

        assertEquals("RULE", report.generatorType());
        assertFalse(report.content().isBlank());
    }

    @Test
    void shouldFallbackToRuleWhenChatBlank() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("   ");

        ReportGenerator.ReportResult report = generator.generate(
                plan, intent, result(List.of(row("2026-07-01", 100))), "解读", "分析销售趋势");

        assertEquals("RULE", report.generatorType());
    }

    @Test
    void shouldUseMinimalReportForUnknownIntent() {
        when(llmClient.isConfigured()).thenReturn(false);
        RecognizedIntent unknown = new RecognizedIntent("WEIRD_TYPE", "未知分析", 0.3, List.of());

        ReportGenerator.ReportResult report = generator.generate(
                plan, unknown, result(List.of(row("2026-07-01", 100))), "", "测试");

        assertEquals("RULE", report.generatorType());
        assertEquals("MINIMAL", report.templateName());
        assertTrue(report.content().contains("未匹配业务模板"));
        assertTrue(report.content().contains("[order_date, sales_amount]"));
        assertTrue(report.content().contains("共查询 1 行"));
    }

    @Test
    void shouldUseGovTemplateWithoutNonGovMetadata() {
        when(llmClient.isConfigured()).thenReturn(false);
        AnalysisPlan govPlan = new AnalysisPlan("GOV_INFO_RECORD", "政务公开记录表", List.of("发文量"),
                List.of("类目"), "近30天", "bar",
                List.of("INTENT", "PLAN", "SQL", "VALIDATE", "EXECUTE", "CHART", "INTERPRET", "REPORT"));
        RecognizedIntent govIntent = new RecognizedIntent("SALES_TREND", "政务信息发布趋势", 0.9, List.of("政务公开", "趋势"));

        ReportGenerator.ReportResult report = generator.generate(
                govPlan, govIntent, result(List.of(row("2026-07-01", 5))), "发文量上升", "政务信息发布趋势");

        assertEquals("RULE", report.generatorType());
        assertEquals("SALES_TREND", report.templateName());
        assertTrue(report.content().contains("政务公开简报") || report.content().contains("发文"));
        verify(metadataService, never()).buildMetadataText();
    }

    @Test
    void shouldInjectMetadataAndRowsSummaryIntoPrompt() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("# 报告");

        generator.generate(plan, intent, result(List.of(row("2026-07-01", 100))), "解读正文", "分析销售趋势");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), captor.capture(), any());
        assertTrue(captor.getValue().contains("【指标口径】"));
        assertTrue(captor.getValue().contains("查询结果摘要"));
        assertTrue(captor.getValue().contains("共1行"));
    }

    @Test
    void shouldHandleEmptyRowsInTemplate() {
        when(llmClient.isConfigured()).thenReturn(false);

        ReportGenerator.ReportResult report = generator.generate(
                plan, intent, result(List.of()), "", "分析销售趋势");

        assertEquals("RULE", report.generatorType());
        assertTrue(report.content().contains("共查询 0 行"));
    }

    @Test
    void shouldBuildTitleFromIntentName() {
        assertEquals("「销售趋势」分析报告", ReportGenerator.buildTitle(plan, intent));
    }
}