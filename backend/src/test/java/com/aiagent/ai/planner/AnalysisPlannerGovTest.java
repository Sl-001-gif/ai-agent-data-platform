package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisPlannerGovTest {

    private AnalysisPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new AnalysisPlanner(new DemoMetadataCatalog());
    }

    private RecognizedIntent govIntent(String type) {
        return new RecognizedIntent(type, "测试意图", 0.8, List.of("政务公开"));
    }

    @Test
    void shouldRouteGovRankingToGovTable() {
        AnalysisPlan plan = planner.buildPlan(govIntent("RANKING"));
        assertEquals("GOV_INFO_RECORD", plan.getTargetTable());
        assertEquals("政府信息公开记录", plan.getTableComment());
        assertEquals("bar", plan.getChartType());
    }

    @Test
    void shouldRouteGovStructureToPie() {
        AnalysisPlan plan = planner.buildPlan(govIntent("STRUCTURE"));
        assertEquals("GOV_INFO_RECORD", plan.getTargetTable());
        assertEquals("pie", plan.getChartType());
    }

    @Test
    void shouldKeepDemoRoutingWithoutGovMarker() {
        AnalysisPlan plan = planner.buildPlan(new RecognizedIntent("RANKING", "测试意图", 0.8, List.of("排名")));
        assertEquals("product_info", plan.getTargetTable());
    }
}