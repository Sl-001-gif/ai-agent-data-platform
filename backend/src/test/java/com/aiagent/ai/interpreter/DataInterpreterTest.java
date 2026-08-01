package com.aiagent.ai.interpreter;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单测：AI 解读的 LLM 优先与规则回退、摘要截断、Prompt 组装。 */
class DataInterpreterTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final MetadataService metadataService = mock(MetadataService.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final DataInterpreter interpreter = new DataInterpreter(llmClient, metadataService, modelRouter);

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
    void shouldFallbackToRuleWhenLlmNotConfigured() {
        when(llmClient.isConfigured()).thenReturn(false);

        DataInterpreter.Interpretation result = interpreter.interpret(plan, intent, result(List.of(row("2026-07-01", 100))));

        assertEquals("RULE", result.generatorType());
        assertFalse(result.text().isBlank());
        verify(llmClient, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void shouldUseLlmTextWhenConfigured() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("  销售额整体呈上升趋势。  ");

        DataInterpreter.Interpretation result = interpreter.interpret(plan, intent,
                result(List.of(row("2026-07-01", 100), row("2026-07-30", 150))));

        assertEquals("LLM", result.generatorType());
        assertEquals("销售额整体呈上升趋势。", result.text());
    }

    @Test
    void shouldFallbackToRuleWhenLlmThrows() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenThrow(new RuntimeException("LLM 超时"));

        DataInterpreter.Interpretation result = interpreter.interpret(plan, intent, result(List.of(row("2026-07-01", 100))));

        assertEquals("RULE", result.generatorType());
        assertFalse(result.text().isBlank());
    }

    @Test
    void shouldFallbackToRuleWhenLlmReturnsBlank() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("   ");

        DataInterpreter.Interpretation result = interpreter.interpret(plan, intent, result(List.of(row("2026-07-01", 100))));

        assertEquals("RULE", result.generatorType());
    }

    @Test
    void shouldReturnEmptyMessageWhenNoRows() {
        when(llmClient.isConfigured()).thenReturn(false);

        DataInterpreter.Interpretation result = interpreter.interpret(plan, intent, result(List.of()));

        assertTrue(result.text().contains("为空"), "空结果应提示无数据");
    }

    @Test
    void shouldBuildTrendConclusionForSalesTrend() {
        when(llmClient.isConfigured()).thenReturn(false);

        DataInterpreter.Interpretation up = interpreter.interpret(plan, intent,
                result(List.of(row("2026-07-01", 100), row("2026-07-30", 150))));
        assertTrue(up.text().contains("上升"), "末值 > 首值*1.05 应为上升: " + up.text());

        DataInterpreter.Interpretation down = interpreter.interpret(plan, intent,
                result(List.of(row("2026-07-01", 100), row("2026-07-30", 50))));
        assertTrue(down.text().contains("下降"), "末值 < 首值*0.95 应为下降: " + down.text());
    }

    @Test
    void shouldBuildGovFallbackWhenGovTable() {
        when(llmClient.isConfigured()).thenReturn(false);
        AnalysisPlan govPlan = new AnalysisPlan("GOV_INFO_RECORD", "政府信息公开记录", List.of("发文量"),
                List.of("发布日期"), "近30天", "line", List.of());
        RecognizedIntent govIntent = new RecognizedIntent("SALES_TREND", "政务发布趋势", 0.9, List.of("政务公开"));

        DataInterpreter.Interpretation result = interpreter.interpret(govPlan, govIntent,
                result(List.of(row("2026-06", 10), row("2026-07", 12))));

        assertTrue(result.text().contains("发文量"), "政务回退应含发文量: " + result.text());
    }

    @Test
    void shouldTruncateSummaryRowsAndLongValues() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(row("d" + i, i));
        }
        String summary = DataInterpreter.buildRowsSummary(List.of("order_date", "sales_amount"), rows);
        assertTrue(summary.contains("仅展示前20行"), "超过 20 行应注明截断");

        Map<String, Object> longRow = new LinkedHashMap<>();
        longRow.put("k", "x".repeat(100));
        String longSummary = DataInterpreter.buildRowsSummary(List.of("k"), List.of(longRow));
        assertTrue(longSummary.contains("…"), "超过 60 字符应截断");
        assertTrue(longSummary.length() < 120, "截断后摘要应保持精简");
    }

    @Test
    void shouldIncludeMetricsAndSummaryInPrompt() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("ok");

        interpreter.interpret(plan, intent, result(List.of(row("2026-07-01", 100))));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), captor.capture(), any());
        assertTrue(captor.getValue().contains("销售额"), "Prompt 应含指标");
        assertTrue(captor.getValue().contains("共1行"), "Prompt 应含结果摘要");
        assertTrue(captor.getValue().contains("指标口径"), "Prompt 应含指标口径");
    }

    @Test
    void shouldNotInjectMetadataForGovTable() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("ok");
        AnalysisPlan govPlan = new AnalysisPlan("GOV_INFO_RECORD", "政府信息公开记录", List.of("发文量"),
                List.of("发布日期"), "近30天", "line", List.of());
        RecognizedIntent govIntent = new RecognizedIntent("SALES_TREND", "政务发布趋势", 0.9, List.of("政务公开"));

        interpreter.interpret(govPlan, govIntent, result(List.of(row("2026-06", 10))));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), captor.capture(), any());
        assertFalse(captor.getValue().contains("指标口径"), "政务类不注入非政务元数据");
    }

    @Test
    void shouldPickTop1ByValueNotRowOrder() {
        when(llmClient.isConfigured()).thenReturn(false);
        RecognizedIntent structIntent = new RecognizedIntent("STRUCTURE", "占比", 0.9, List.of());

        Map<String, Object> small = new LinkedHashMap<>();
        small.put("category", "食品");
        small.put("sales_amount", 100);
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("category", "手机");
        big.put("sales_amount", 300);

        DataInterpreter.Interpretation result = interpreter.interpret(plan, structIntent, result(List.of(small, big)));

        assertTrue(result.text().contains("手机"), "应取数值最大行而非首行: " + result.text());
    }
}