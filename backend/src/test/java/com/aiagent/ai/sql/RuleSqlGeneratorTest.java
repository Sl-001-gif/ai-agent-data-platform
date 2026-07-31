package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleSqlGeneratorTest {

    private final RuleSqlGenerator generator = new RuleSqlGenerator();
    private final SqlValidator validator = new SqlValidator();

    private AnalysisPlan planFor(String table, String timeRange) {
        return new AnalysisPlan(table, "测试表", List.of("测试指标"), List.of("测试维度"), timeRange, "table", List.of());
    }

    private RecognizedIntent intentFor(String type) {
        return new RecognizedIntent(type, "测试意图", 0.8, List.of("测试"));
    }

    @Test
    void shouldGenerateSelectPerIntentWithExpectedTable() {
        for (String type : List.of("SALES_TREND", "COMPARISON", "STRUCTURE", "ANOMALY", "GENERAL")) {
            assertTableFor(type, "order_info");
        }
        for (String type : List.of("USER_PROFILE", "RETENTION")) {
            assertTableFor(type, "user_info");
        }
        assertTableFor("RANKING", "product_info");
    }

    private void assertTableFor(String type, String table) {
        SqlGenerator.GeneratedSql result = generator.generate(planFor(table, "近30天"), intentFor(type));
        assertTrue(result.sql().startsWith("SELECT"), type + " SQL 应以 SELECT 开头");
        assertTrue(result.sql().contains("FROM " + table), type + " SQL 应 FROM " + table);
        assertEquals("RULE", result.generatorType(), type + " generatorType 应为 RULE");
    }

    @Test
    void shouldKeepGroupByColumnsPerIntent() {
        assertContains("SALES_TREND", "GROUP BY order_date");
        assertContains("USER_PROFILE", "GROUP BY age_group, city");
        assertContains("COMPARISON", "GROUP BY region, channel");
        assertContains("RANKING", "GROUP BY category");
        assertContains("STRUCTURE", "GROUP BY category");
        assertContains("RETENTION", "GROUP BY register_date");
        assertContains("ANOMALY", "GROUP BY order_date, region");
        assertContains("GENERAL", "GROUP BY order_date, region");
    }

    private void assertContains(String type, String fragment) {
        String sql = generator.generate(planFor("order_info", "近30天"), intentFor(type)).sql();
        assertTrue(sql.contains(fragment), type + " 应包含 " + fragment);
    }

    @Test
    void shouldContainOrderingAndLimitForRankingAndAnomaly() {
        String ranking = generator.generate(planFor("product_info", null), intentFor("RANKING")).sql();
        assertTrue(ranking.contains("ORDER BY SUM(sales_volume) DESC LIMIT 10"), "RANKING 应含排序与 LIMIT 10");
        String anomaly = generator.generate(planFor("order_info", null), intentFor("ANOMALY")).sql();
        assertTrue(anomaly.contains("LIMIT 30"), "ANOMALY 应含 LIMIT 30");
    }

    @Test
    void shouldResolveTimeRangeToInterval() {
        assertEquals("INTERVAL 30 DAY", timeRangeOf("近30天"));
        assertEquals("INTERVAL 7 DAY", timeRangeOf("近7天"));
        assertEquals("INTERVAL 30 DAY", timeRangeOf(null));
        assertEquals("INTERVAL 30 DAY", timeRangeOf("上个月"));
    }

    private String timeRangeOf(String timeRange) {
        String sql = generator.generate(planFor("order_info", timeRange), intentFor("SALES_TREND")).sql();
        int idx = sql.indexOf("INTERVAL ");
        assertTrue(idx >= 0, "SQL 应含 INTERVAL 占位替换结果");
        return sql.substring(idx, sql.indexOf(" DAY", idx) + 4);
    }

    @Test
    void shouldFallbackToGeneralForNullOrUnknownIntent() {
        String nullSql = generator.generate(planFor("order_info", null), null).sql();
        assertTrue(nullSql.startsWith("SELECT"));
        assertTrue(nullSql.contains("FROM order_info"));
        String unknownSql = generator.generate(planFor("order_info", null), intentFor("UNKNOWN_TYPE")).sql();
        assertTrue(unknownSql.contains("FROM order_info"));
        assertTrue(unknownSql.contains("avg_order_amount"));
    }

    @Test
    void shouldPassValidatorForAllEightTemplates() {
        for (String type : List.of("SALES_TREND", "USER_PROFILE", "COMPARISON", "RANKING",
                "STRUCTURE", "RETENTION", "ANOMALY", "GENERAL")) {
            String table = switch (type) {
                case "USER_PROFILE", "RETENTION" -> "user_info";
                case "RANKING" -> "product_info";
                default -> "order_info";
            };
            SqlGenerator.GeneratedSql result = generator.generate(planFor(table, "近30天"), intentFor(type));
            SqlValidator.ValidationResult validation = validator.validate(result.sql(), table);
            assertTrue(validation.valid(), type + " 生成 SQL 应通过校验: " + validation.errors());
        }
    }
}