package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisPlannerTest {

    private AnalysisPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new AnalysisPlanner(new DemoMetadataCatalog());
    }

    private AnalysisPlan planFor(String type) {
        return planner.buildPlan(new RecognizedIntent(type, "测试意图", 0.8, List.of("测试")));
    }

    @Test
    void shouldReturnFixedEightStepsForEveryIntent() {
        for (String type : List.of("SALES_TREND", "USER_PROFILE", "COMPARISON", "RANKING",
                "STRUCTURE", "RETENTION", "ANOMALY", "GENERAL")) {
            AnalysisPlan plan = planFor(type);
            assertEquals(8, plan.getSteps().size(), "steps should be 8 for " + type);
            assertEquals("INTENT", plan.getSteps().get(0));
            assertEquals("REPORT", plan.getSteps().get(7));
        }
    }

    @Test
    void shouldMapChartTypePerIntent() {
        assertEquals("line", planFor("SALES_TREND").getChartType());
        assertEquals("bar", planFor("USER_PROFILE").getChartType());
        assertEquals("bar", planFor("COMPARISON").getChartType());
        assertEquals("bar", planFor("RANKING").getChartType());
        assertEquals("pie", planFor("STRUCTURE").getChartType());
        assertEquals("line", planFor("RETENTION").getChartType());
        assertEquals("table", planFor("ANOMALY").getChartType());
        assertEquals("table", planFor("GENERAL").getChartType());
    }

    @Test
    void shouldFillPlanFieldsForSalesTrend() {
        AnalysisPlan plan = planFor("SALES_TREND");
        assertEquals("order_info", plan.getTargetTable());
        assertEquals("订单表", plan.getTableComment());
        assertEquals("近30天", plan.getTimeRange());
        assertFalse(plan.getMetrics().isEmpty());
        assertFalse(plan.getDimensions().isEmpty());
        assertTrue(plan.getMetrics().contains("销售额"));
    }

    @Test
    void shouldFallbackToGeneralForUnknownType() {
        AnalysisPlan plan = planFor("UNKNOWN_TYPE");
        assertEquals("order_info", plan.getTargetTable());
        assertEquals("table", plan.getChartType());
    }

    @Test
    void shouldHandleNullIntent() {
        AnalysisPlan plan = planner.buildPlan(null);
        assertEquals("order_info", plan.getTargetTable());
        assertEquals("table", plan.getChartType());
    }

    @Test
    void shouldKeepPlanMetricsConsistentWithMetadataCatalog() {
        DemoMetadataCatalog catalog = new DemoMetadataCatalog();
        for (String type : List.of("SALES_TREND", "USER_PROFILE", "COMPARISON", "RANKING",
                "STRUCTURE", "RETENTION", "ANOMALY", "GENERAL")) {
            AnalysisPlan plan = planFor(type);
            DemoMetadataCatalog.DemoTable table = catalog.getTable(plan.getTargetTable());
            assertNotNull(table, "catalog table should exist for " + type);
            for (String metric : plan.getMetrics()) {
                assertTrue(table.metrics().contains(metric),
                        "metric [" + metric + "] of " + type + " not in catalog table " + plan.getTargetTable());
            }
        }
    }}