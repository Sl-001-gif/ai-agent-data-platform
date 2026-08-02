package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleSqlGeneratorGovTest {

    private final RuleSqlGenerator generator = new RuleSqlGenerator();

    private AnalysisPlan govPlan(String type) {
        return new AnalysisPlan("GOV_INFO_RECORD", "政府信息公开记录", List.of("发文量"),
                List.of("公开单位"), "近30天", "bar",
                List.of("INTENT", "PLAN", "SQL", "VALIDATE", "EXECUTE", "CHART", "INTERPRET", "REPORT"));
    }

    @Test
    void shouldUseGovTemplateForUppercaseTargetTable() {
        SqlGenerator.GeneratedSql generated = generator.generate(govPlan("RANKING"),
                new RecognizedIntent("RANKING", "排名分析", 0.8, List.of("政务公开")));
        assertTrue(generated.sql().contains("gov_info_record"));
        assertTrue(generated.sql().contains("COALESCE(NULLIF(publish_unit,''), category) AS unit"));
        assertTrue(generated.sql().contains("GROUP BY unit"));
    }

    @Test
    void shouldUseGovTemplateForLowercaseTargetTable() {
        AnalysisPlan plan = govPlan("SALES_TREND");
        plan.setTargetTable("gov_info_record");
        SqlGenerator.GeneratedSql generated = generator.generate(plan,
                new RecognizedIntent("SALES_TREND", "发布趋势", 0.8, List.of("政务公开")));
        assertTrue(generated.sql().contains("gov_info_record"));
        assertTrue(generated.sql().contains("DATE_FORMAT(publish_date"));
    }

    @Test
    void shouldFallbackToGovGeneralForUnknownType() {
        SqlGenerator.GeneratedSql generated = generator.generate(govPlan("USER_PROFILE"),
                new RecognizedIntent("USER_PROFILE", "用户画像", 0.8, List.of("政务公开")));
        assertTrue(generated.sql().contains("gov_info_record"));
        assertTrue(generated.sql().contains("GROUP BY category, publish_unit"));
    }

    @Test
    void rankingShouldFallbackUnitToCategoryWhenEmpty() {
        SqlGenerator.GeneratedSql generated = generator.generate(govPlan("RANKING"),
                new RecognizedIntent("RANKING", "排名分析", 0.8, List.of("发文", "政务公开")));
        assertTrue(generated.sql().contains("COALESCE(NULLIF(publish_unit,''), category) AS unit"));
        assertTrue(generated.sql().contains("GROUP BY unit"));
        assertTrue(generated.sql().contains("LIMIT 10"));
    }
}
