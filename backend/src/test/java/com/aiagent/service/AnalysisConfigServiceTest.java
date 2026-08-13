package com.aiagent.service;

import com.aiagent.entity.AnalysisIntentRule;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.entity.AnalysisPlanType;
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
        long govCount = specs.stream().filter(s -> "GOV".equals(s.typeCode())).count();
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
        assertEquals("GOV", spec.typeCode());
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

    // ---------- 计划类型（自定义 + 启停） ----------

    @Test
    void planTypes_shouldFallbackToBuiltinWhenDbEmpty() {
        when(mapper.selectPlanTypes()).thenReturn(List.of());
        List<AnalysisConfigService.PlanTypeSpec> types = service.planTypes();
        assertEquals(3, types.size(), "内置类型应为 NORMAL/GOV/STAT");
        assertEquals("NORMAL", types.get(0).code());
        assertTrue(types.stream().anyMatch(t -> "STAT".equals(t.code()) && t.keywords().contains("gdp")));
    }

    @Test
    void planTypes_shouldSkipDisabled() {
        AnalysisPlanType normal = new AnalysisPlanType();
        normal.setTypeCode("NORMAL"); normal.setTypeName("普通"); normal.setStatus(1);
        AnalysisPlanType disabled = new AnalysisPlanType();
        disabled.setTypeCode("XX"); disabled.setTypeName("自定义"); disabled.setStatus(0);
        when(mapper.selectPlanTypes()).thenReturn(List.of(normal, disabled));
        List<AnalysisConfigService.PlanTypeSpec> types = service.planTypes();
        assertEquals(1, types.size(), "停用类型应被过滤");
        assertEquals("NORMAL", types.get(0).code());
    }

    @Test
    void planConfigs_shouldExcludeRowsOfDisabledType() {
        AnalysisPlanType disabled = new AnalysisPlanType();
        disabled.setTypeCode("XX"); disabled.setTypeName("自定义"); disabled.setStatus(0);
        when(mapper.selectPlanTypes()).thenReturn(List.of(disabled));
        AnalysisPlanConfig row = new AnalysisPlanConfig();
        row.setIntentCode("GENERAL"); row.setPlanType("XX"); row.setTableName("xx_table");
        row.setMetrics("a"); row.setDimensions("b"); row.setChartType("table"); row.setStatus(1);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(row));
        assertTrue(service.planConfigs().isEmpty(), "停用类型的计划配置不应参与路由");
    }

    @Test
    void planConfigs_shouldDeriveTypeFromIsGovWhenBlank() {
        AnalysisPlanConfig gov = new AnalysisPlanConfig();
        gov.setIntentCode("GENERAL"); gov.setIsGov(1); gov.setTableName("GOV_INFO_RECORD");
        gov.setMetrics("发文量"); gov.setDimensions("类目"); gov.setChartType("pie"); gov.setStatus(1);
        when(mapper.selectPlanTypes()).thenReturn(List.of());
        when(mapper.selectPlanConfigs()).thenReturn(List.of(gov));
        List<AnalysisConfigService.PlanConfigSpec> specs = service.planConfigs();
        assertEquals(1, specs.size());
        assertEquals("GOV", specs.get(0).typeCode(), "plan_type 为空时应按 is_gov 推导");
    }

    @Test
    void createPlanType_shouldRejectDuplicateCode() {
        AnalysisPlanType existing = new AnalysisPlanType();
        existing.setId(1L); existing.setTypeCode("STAT");
        when(mapper.selectPlanTypeByCode("STAT")).thenReturn(existing);
        AnalysisPlanType request = new AnalysisPlanType();
        request.setTypeCode("stat"); request.setTypeName("统计");
        assertThrows(RuntimeException.class, () -> service.createPlanType(request));
        verify(mapper, never()).insertPlanType(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createPlanType_shouldNormalizeAndFillDefaults() {
        when(mapper.selectPlanTypeByCode("NEW_TYPE")).thenReturn(null);
        AnalysisPlanType request = new AnalysisPlanType();
        request.setTypeCode(" new_type "); request.setTypeName("新类型");
        service.createPlanType(request);
        assertEquals("NEW_TYPE", request.getTypeCode(), "类型编码应大写去空格");
        assertEquals("#409eff", request.getColor());
        assertEquals(1, request.getStatus());
        verify(mapper).insertPlanType(request);
    }

    @Test
    void deletePlanType_shouldResetRefsAndDelete() {
        AnalysisPlanType type = new AnalysisPlanType();
        type.setId(5L); type.setTypeCode("XX"); type.setTypeName("自定义");
        when(mapper.selectPlanTypeById(5L)).thenReturn(type);
        when(mapper.deletePlanType(5L)).thenReturn(1);
        service.deletePlanType(5L);
        verify(mapper).clearPlanTypeRefs("XX");
        verify(mapper).deletePlanType(5L);
    }

    @Test
    void deletePlanType_shouldRejectNormalType() {
        AnalysisPlanType normal = new AnalysisPlanType();
        normal.setId(1L); normal.setTypeCode("NORMAL"); normal.setTypeName("普通");
        when(mapper.selectPlanTypeById(1L)).thenReturn(normal);
        assertThrows(RuntimeException.class, () -> service.deletePlanType(1L));
        verify(mapper, never()).deletePlanType(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createPlanConfig_shouldSyncTypeToIsGov() {
        AnalysisPlanConfig config = new AnalysisPlanConfig();
        config.setIntentCode("STAT_TREND"); config.setTableName("stat_indicator");
        config.setPlanType("STAT");
        service.createPlanConfig(config);
        assertEquals("STAT", config.getPlanType());
        assertEquals(0, config.getIsGov(), "非 GOV 类型 is_gov 应为 0");
        verify(mapper).insertPlanConfig(config);
    }
}
