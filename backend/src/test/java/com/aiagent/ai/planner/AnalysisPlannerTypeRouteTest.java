package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.entity.AnalysisPlanType;
import com.aiagent.mapper.AnalysisConfigMapper;
import com.aiagent.service.AnalysisConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L1：计划类型自定义路由（GOV 保留原行为 / 自定义类型关键词路由 / 停用类型不生效）。 */
class AnalysisPlannerTypeRouteTest {

    private AnalysisPlanner planner;
    private AnalysisConfigMapper mapper;

    private AnalysisPlanType type(String code, String name, String keywords, int status) {
        AnalysisPlanType t = new AnalysisPlanType();
        t.setTypeCode(code);
        t.setTypeName(name);
        t.setRouteKeywords(keywords);
        t.setStatus(status);
        return t;
    }

    private AnalysisPlanConfig config(String intentCode, String typeCode, String table, String chart) {
        AnalysisPlanConfig c = new AnalysisPlanConfig();
        c.setIntentCode(intentCode);
        c.setPlanType(typeCode);
        c.setTableName(table);
        c.setMetrics("指标");
        c.setDimensions("维度");
        c.setChartType(chart);
        c.setTimeRange("近30天");
        c.setStatus(1);
        return c;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AnalysisConfigMapper.class);
        when(mapper.selectPlanTypes()).thenReturn(List.of(
                type("NORMAL", "普通", "", 1),
                type("GOV", "政务", "政务,公开,邵阳", 1),
                type("STAT", "统计", "统计,gdp,经济,增速", 1)));
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("SALES_TREND", "NORMAL", "order_info", "line"),
                config("STAT_TREND", "NORMAL", "stat_indicator", "line"),
                config("STAT_TREND", "GOV", "stat_indicator", "line"),
                config("GENERAL", "GOV", "GOV_INFO_RECORD", "table"),
                config("GENERAL", "NORMAL", "order_info", "table")));
        planner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper));
    }

    private RecognizedIntent intent(String type, List<String> keywords) {
        return new RecognizedIntent(type, "测试意图", 0.8, keywords);
    }

    @Test
    void shouldRouteGovKeywordToGovTypePreservingOldBehavior() {
        AnalysisPlan plan = planner.buildPlan(intent("STAT_TREND", List.of("经济", "政务公开")), "邵阳经济趋势");
        assertEquals("GOV", plan.getPlanType());
        assertEquals("stat_indicator", plan.getTargetTable());
    }

    @Test
    void shouldRouteCustomTypeByRouteKeywords() {
        AnalysisPlan plan = planner.buildPlan(intent("STAT_TREND", List.of("gdp")), "2025年GDP趋势");
        assertEquals("STAT", plan.getPlanType());
        assertEquals("stat_indicator", plan.getTargetTable());
    }

    @Test
    void shouldFallbackToNormalWhenNoTypeKeywordHit() {
        AnalysisPlan plan = planner.buildPlan(intent("SALES_TREND", List.of("趋势")), "分析销售趋势");
        assertEquals("NORMAL", plan.getPlanType());
        assertEquals("order_info", plan.getTargetTable());
    }

    @Test
    void disabledTypeShouldNotRoute() {
        when(mapper.selectPlanTypes()).thenReturn(List.of(
                type("NORMAL", "普通", "", 1),
                type("GOV", "政务", "政务,公开,邵阳", 1),
                type("STAT", "统计", "统计,gdp,经济,增速", 0)));
        AnalysisPlan plan = planner.buildPlan(intent("STAT_TREND", List.of("gdp")), "2025年GDP趋势");
        assertEquals("NORMAL", plan.getPlanType(), "停用类型不应参与路由");
        assertEquals("stat_indicator", plan.getTargetTable(), "回退普通类型同意图配置");
    }
}