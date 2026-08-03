package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import com.aiagent.ai.sql.RuleSqlGenerator;
import com.aiagent.ai.sql.SqlGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L1 单测：从用户问题提取时间范围（「近3年」缺口修复）贯通计划与规则 SQL。 */
class AnalysisPlannerTimeRangeTest {

    private AnalysisPlanner planner;
    private RuleSqlGenerator generator;

    @BeforeEach
    void setUp() {
        planner = new AnalysisPlanner(new DemoMetadataCatalog());
        generator = new RuleSqlGenerator();
    }

    @Test
    void shouldExtractYearRangeFromGovQuestion() {
        RecognizedIntent intent = new RecognizedIntent("SALES_TREND", "销售趋势", 0.8, List.of("政务公开"));
        AnalysisPlan plan = planner.buildPlan(intent, "近3年按月发文趋势");
        assertEquals("GOV_INFO_RECORD", plan.getTargetTable());
        assertEquals("近3年", plan.getTimeRange());

        SqlGenerator.GeneratedSql generated = generator.generate(plan, intent);
        assertTrue(generated.sql().contains("INTERVAL 3 YEAR"), "近3年应生成 YEAR 区间，实际: " + generated.sql());
        assertTrue(generated.sql().contains("DATE_FORMAT(publish_date,'%Y-%m')"), "应保持按月聚合");
    }

    @Test
    void shouldFallbackToDefaultRangeWhenNoRangeInQuestion() {
        AnalysisPlan plan = planner.buildPlan(
                new RecognizedIntent("SALES_TREND", "销售趋势", 0.8, List.of("政务公开")), "各单位发文量排名");
        assertEquals("近30天", plan.getTimeRange());
    }
}