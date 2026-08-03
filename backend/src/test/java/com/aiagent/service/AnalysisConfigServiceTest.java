package com.aiagent.service;

import com.aiagent.entity.AnalysisIntentRule;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.mapper.AnalysisConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单测：分析配置中心（内置回退 / 库配置读取 / CRUD 校验）。 */
class AnalysisConfigServiceTest {

    private final AnalysisConfigMapper mapper = mock(AnalysisConfigMapper.class);
    private final AnalysisConfigService service = new AnalysisConfigService(mapper);

    @Test
    void intentRules_shouldFallbackToBuiltinWhenDbEmpty() {
        when(mapper.selectIntentRules()).thenReturn(List.of());
        List<AnalysisConfigService.IntentRuleSpec> rules = service.intentRules();
        assertEquals(7, rules.size(), "内置意图规则应为 7 条");
        assertEquals("USER_PROFILE", rules.get(0).code());
        assertTrue(rules.get(0).keywords().contains("画像"));
    }

    @Test
    void intentRules_shouldReadDbWhenPresent() {
        AnalysisIntentRule row = new AnalysisIntentRule();
        row.setIntentCode("sales_trend");
        row.setIntentName("销售趋势");
        row.setKeywords("销售, 趋势, 增长");
        row.setPriority(1);
        row.setStatus(1);
        when(mapper.selectIntentRules()).thenReturn(List.of(row));
        List<AnalysisConfigService.IntentRuleSpec> rules = service.intentRules();
        assertEquals(1, rules.size());
        assertEquals("SALES_TREND", rules.get(0).code(), "意图编码应大写");
        assertEquals(List.of("销售", "趋势", "增长"), rules.get(0).keywords());
    }

    @Test
    void intentRules_shouldSkipDisabledRows() {
        AnalysisIntentRule disabled = new AnalysisIntentRule();
        disabled.setIntentCode("ANOMALY");
        disabled.setKeywords("下降");
        disabled.setStatus(0);
        when(mapper.selectIntentRules()).thenReturn(List.of(disabled));
        assertTrue(service.intentRules().isEmpty(), "停用规则应被过滤");
    }

    @Test
    void planConfigs_shouldFallbackToBuiltinWhenDbEmpty() {
        when(mapper.selectPlanConfigs()).thenReturn(List.of());
        List<AnalysisConfigService.PlanConfigSpec> specs = service.planConfigs();
        assertEquals(16, specs.size(), "内置计划配置应为 16 条（普通 8 + 政务 8）");
        long govCount = specs.stream().filter(AnalysisConfigService.PlanConfigSpec::gov).count();
        assertEquals(8, govCount);
    }

    @Test
    void planConfigs_shouldReadDbAndParseCsv() {
        AnalysisPlanConfig row = new AnalysisPlanConfig();
        row.setIntentCode("RANKING");
        row.setIsGov(1);
        row.setTableName("GOV_INFO_RECORD");
        row.setMetrics("发文量,日均发文量");
        row.setDimensions("公开单位");
        row.setChartType("bar");
        row.setTimeRange("近30天");
        row.setSqlTemplate("SELECT 1");
        row.setStatus(1);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(row));
        List<AnalysisConfigService.PlanConfigSpec> specs = service.planConfigs();
        assertEquals(1, specs.size());
        AnalysisConfigService.PlanConfigSpec spec = specs.get(0);
        assertTrue(spec.gov());
        assertEquals(List.of("发文量", "日均发文量"), spec.metrics());
    }

    @Test
    void createIntentRule_shouldRejectBlankCode() {
        AnalysisIntentRule rule = new AnalysisIntentRule();
        assertThrows(RuntimeException.class, () -> service.createIntentRule(rule));
        verify(mapper, never()).insertIntentRule(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createIntentRule_shouldFillDefaults() {
        AnalysisIntentRule rule = new AnalysisIntentRule();
        rule.setIntentCode("NEW_INTENT");
        rule.setKeywords("新词");
        service.createIntentRule(rule);
        assertEquals(1, rule.getStatus());
        assertEquals(0, rule.getPriority());
        verify(mapper).insertIntentRule(rule);
    }

    @Test
    void deleteIntentRule_shouldThrowWhenMissing() {
        when(mapper.deleteIntentRule(9L)).thenReturn(0);
        assertThrows(RuntimeException.class, () -> service.deleteIntentRule(9L));
    }

    @Test
    void govKeywords_shouldReturnBuiltinList() {
        assertTrue(service.govKeywords().contains("政务"));
        assertTrue(service.govKeywords().contains("发文"));
    }
}