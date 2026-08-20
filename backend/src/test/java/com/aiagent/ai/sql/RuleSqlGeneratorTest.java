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
        String table = switch (type) {
            case "USER_PROFILE", "RETENTION" -> "user_info";
            case "RANKING" -> "product_info";
            default -> "order_info";
        };
        String sql = generator.generate(planFor(table, "近30天"), intentFor(type)).sql();
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

    @Test
    void shouldBuildStatMonthlyStructureSnapshotAndTrend() {
        AnalysisPlan snapshot = new AnalysisPlan("stat_monthly", "统计月报", List.of("第一产业", "第二产业", "第三产业"),
                List.of("产业"), "最新期间", "pie", List.of());
        SqlGenerator.GeneratedSql s1 = generator.generate(snapshot, intentFor("STRUCTURE"));
        assertTrue(s1.sql().contains("indicator_name IN ('第一产业增加值', '第二产业增加值', '第三产业增加值')"), "快照 SQL 应含三大产业过滤（治理后规范名）");
        assertTrue(s1.sql().contains("period = (SELECT period"), "快照 SQL 应取最新期间");
        assertTrue(validator.validate(s1.sql(), "stat_monthly").valid(), "快照 SQL 应通过校验");

        AnalysisPlan trend = new AnalysisPlan("stat_monthly", "统计月报", List.of("第一产业"),
                List.of("期间", "产业"), "近5年", "line", List.of());
        SqlGenerator.GeneratedSql s2 = generator.generate(trend, intentFor("STRUCTURE"));
        assertTrue(s2.sql().contains("indicator_name IN ('第一产业增加值')"), "趋势 SQL 应按提及产业过滤（治理后规范名）");
        assertTrue(s2.sql().contains("ORDER BY period, indicator_name"), "趋势 SQL 应按期间排序");
        assertTrue(s2.sql().contains("- 5 + 1"), "趋势 SQL 应按近5年过滤年份");
        assertTrue(validator.validate(s2.sql(), "stat_monthly").valid(), "趋势 SQL 应通过校验");
    }

    @Test
    void shouldBuildStatTrendForStatMonthlyDataset() {
        AnalysisPlan trend = new AnalysisPlan("stat_monthly", "统计月报", List.of("第一产业"),
                List.of("期间", "产业"), "近5年", "line", List.of());
        SqlGenerator.GeneratedSql result = generator.generate(trend, intentFor("STAT_TREND"));
        assertTrue(result.sql().contains("indicator_name IN ('第一产业增加值')"), "STAT_TREND 趋势 SQL 应按提及产业过滤（治理后规范名）");
        assertTrue(result.sql().contains("ORDER BY period, indicator_name"), "STAT_TREND 趋势 SQL 应按期间排序");
        assertTrue(result.sql().contains("- 5 + 1"), "STAT_TREND 趋势 SQL 应按近5年过滤年份");
        assertEquals("RULE", result.generatorType(), "STAT_TREND 应为 RULE 生成");
        assertTrue(validator.validate(result.sql(), "stat_monthly").valid(), "STAT_TREND SQL 应通过校验");
    }
    @Test
    void shouldFilterAbsoluteYearForStatTrend() {
        AnalysisPlan trend = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值(GDP)"),
                List.of("期间", "指标"), "2024年", "line", List.of());
        SqlGenerator.GeneratedSql result = generator.generate(trend, intentFor("STAT_TREND"));
        assertTrue(result.sql().contains("= 2024"), "绝对年份应按当年精确过滤: " + result.sql());
        assertTrue(result.sql().contains("indicator_name IN ('地区生产总值')"), "指标别名应映射为规范名: " + result.sql());
        assertFalse(result.sql().contains("- 2024 + 1"), "绝对年份不应生成窗口减法过滤");
        assertTrue(validator.validate(result.sql(), "stat_monthly").valid(), "绝对年份 SQL 应通过校验");
    }

    @Test
    void shouldFilterSamePeriodTypeAndExpandIncomeForStatTrend() {
        AnalysisPlan trend = new AnalysisPlan("stat_monthly", "统计月报", List.of("城镇居民人均可支配收入"),
                List.of("期间", "指标"), "近3年", "line", List.of());
        SqlGenerator.GeneratedSql result = generator.generate(trend, intentFor("STAT_TREND"));
        assertTrue(result.sql().contains("SUBSTRING(period, LOCATE('年', period) + 1) = "),
                "趋势 SQL 应按同期别过滤（避免累计期别混画）: " + result.sql());
        assertTrue(result.sql().contains("全体居民人均可支配收入"), "收入指标应补齐全体居民: " + result.sql());
        assertTrue(result.sql().contains("农村居民人均可支配收入"), "收入指标应补齐农村居民: " + result.sql());
        assertTrue(result.sql().contains("城镇居民人均可支配收入"), "收入指标应保留城镇居民: " + result.sql());
        assertTrue(validator.validate(result.sql(), "stat_monthly").valid(), "同期别过滤 SQL 应通过校验");
    }

    @Test
    void shouldMapCountyRegionIndicatorToCanonicalName() {
        AnalysisPlan snapshot = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值"),
                List.of("区县"), "最新期间", "bar", List.of());
        SqlGenerator.GeneratedSql s1 = generator.generate(snapshot, intentFor("STRUCTURE"));
        assertTrue(s1.sql().contains("indicator_name = '地区生产总值'"), "区县快照 SQL 应用规范名: " + s1.sql());
        assertFalse(s1.sql().contains("分县"), "区县快照 SQL 不应含旧指标名: " + s1.sql());
        assertTrue(s1.sql().contains("region <> '全市'"), "区县快照 SQL 应排除全市");
        assertTrue(validator.validate(s1.sql(), "stat_monthly").valid(), "区县快照 SQL 应通过校验");

        AnalysisPlan trend = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值"),
                List.of("区县"), "近5年", "line", List.of());
        SqlGenerator.GeneratedSql s2 = generator.generate(trend, intentFor("STRUCTURE"));
        assertTrue(s2.sql().contains("indicator_name = '地区生产总值'"), "区县趋势 SQL 应用规范名: " + s2.sql());
        assertTrue(s2.sql().contains("ORDER BY period, region"), "区县趋势 SQL 应按期间与地区排序");
        assertTrue(validator.validate(s2.sql(), "stat_monthly").valid(), "区县趋势 SQL 应通过校验");

        AnalysisPlan regionTrend = new AnalysisPlan("stat_monthly", "统计月报", List.of("规模工业增加值"),
                List.of("期间", "区县"), "近3年", "line", List.of());
        SqlGenerator.GeneratedSql s3 = generator.generate(regionTrend, intentFor("STAT_TREND"));
        assertTrue(s3.sql().contains("indicator_name = '规模工业增加值'"), "区县趋势 SQL 应用提问指标: " + s3.sql());
        assertTrue(s3.sql().contains("COALESCE(value, growth_rate)"), "区县 SQL 应兼容增速列: " + s3.sql());
        assertTrue(s3.sql().contains("region <> '全市'"), "区县 SQL 应排除全市");
        assertTrue(s3.sql().contains("value IS NOT NULL OR growth_rate IS NOT NULL"), "区县 SQL 应过滤 value 与 growth_rate");
        assertTrue(validator.validate(s3.sql(), "stat_monthly").valid(), "区县趋势 SQL 应通过校验");

        AnalysisPlan regionSnapshot = new AnalysisPlan("stat_monthly", "统计月报", List.of("规模工业增加值"),
                List.of("区县"), "最新期间", "bar", List.of());
        SqlGenerator.GeneratedSql s4 = generator.generate(regionSnapshot, intentFor("STAT_TREND"));
        assertTrue(s4.sql().contains("period = (SELECT period"), "区县快照 SQL 应取最新期间: " + s4.sql());
        assertTrue(s4.sql().contains("ORDER BY value DESC"), "区县快照 SQL 应按值排序");
        assertTrue(validator.validate(s4.sql(), "stat_monthly").valid(), "区县快照 SQL 应通过校验");

        AnalysisPlan fiscalStructure = new AnalysisPlan("stat_monthly", "统计月报", List.of("税收收入", "非税收入"),
                List.of("指标"), "最新期间", "pie", List.of());
        SqlGenerator.GeneratedSql s5 = generator.generate(fiscalStructure, intentFor("STRUCTURE"));
        assertTrue(s5.sql().contains("indicator_name IN ('税收收入', '非税收入')"), "指标结构 SQL 应按命中指标过滤: " + s5.sql());
        assertTrue(s5.sql().contains("period = (SELECT period"), "指标结构 SQL 应取最新期间: " + s5.sql());
        assertTrue(validator.validate(s5.sql(), "stat_monthly").valid(), "指标结构 SQL 应通过校验");
    }

    @Test
    void shouldSelectGrowthRateForIndustrySectorRows() {
        AnalysisPlan trend = new AnalysisPlan("stat_monthly", "统计月报", List.of("通用设备制造业", "专用设备制造业"),
                List.of("期间", "行业"), "近1年", "line", List.of());
        SqlGenerator.GeneratedSql result = generator.generate(trend, intentFor("STAT_TREND"));
        assertTrue(result.sql().contains("COALESCE(value, growth_rate)"), "行业行 value 为空应回退 growth_rate");
        assertTrue(result.sql().contains("value IS NOT NULL OR growth_rate IS NOT NULL"), "应同时过滤 value 与 growth_rate");
        assertTrue(result.sql().contains("%"), "行业增速单位应兜底为 %");
        assertTrue(validator.validate(result.sql(), "stat_monthly").valid(), "行业增速 SQL 应通过校验");
    }

    @Test
    void shouldScopeSnapshotLatestPeriodToAbsoluteYear() {
        // 区县快照（排名/对比）：绝对年份应把「最新期间」限定到当年，避免取到表内最新期
        AnalysisPlan regionSnapshot = new AnalysisPlan("stat_monthly", "统计月报", List.of("城镇居民人均可支配收入"),
                List.of("区县"), "2023年", "bar", List.of());
        SqlGenerator.GeneratedSql s1 = generator.generate(regionSnapshot, intentFor("STAT_TREND"));
        assertTrue(s1.sql().contains("= 2023"), "区县快照应把最新期间限定到 2023 年: " + s1.sql());
        assertTrue(s1.sql().contains("period = (SELECT period FROM stat_monthly WHERE indicator_name = '城镇居民人均可支配收入' AND region <> '全市'"),
                "区县快照年份约束应落在期间子查询内: " + s1.sql());
        assertTrue(validator.validate(s1.sql(), "stat_monthly").valid(), "区县快照 SQL 应通过校验");

        // 产业快照（占比）：同样按绝对年份限定最新期间
        AnalysisPlan industrySnapshot = new AnalysisPlan("stat_monthly", "统计月报", List.of("第一产业", "第二产业", "第三产业"),
                List.of("产业"), "2024年", "pie", List.of());
        SqlGenerator.GeneratedSql s2 = generator.generate(industrySnapshot, intentFor("STRUCTURE"));
        assertTrue(s2.sql().contains("= 2024"), "产业快照应把最新期间限定到 2024 年: " + s2.sql());
        assertTrue(validator.validate(s2.sql(), "stat_monthly").valid(), "产业快照 SQL 应通过校验");

        // 最新期间 / 近N年：不应引入绝对年份过滤，仍取表内最新期
        AnalysisPlan latest = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值"),
                List.of("区县"), "最新期间", "bar", List.of());
        SqlGenerator.GeneratedSql s3 = generator.generate(latest, intentFor("STAT_TREND"));
        assertFalse(s3.sql().matches("(?s).*=\\s*\\d{4}.*"), "最新期间快照不应含绝对年份过滤: " + s3.sql());

        AnalysisPlan recent = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值"),
                List.of("区县"), "近3年", "bar", List.of());
        SqlGenerator.GeneratedSql s4 = generator.generate(recent, intentFor("STAT_TREND"));
        assertFalse(s4.sql().matches("(?s).*=\\s*\\d{4}.*"), "近3年快照不应误生成年份等号过滤: " + s4.sql());
        assertTrue(validator.validate(s4.sql(), "stat_monthly").valid(), "近3年快照 SQL 应通过校验");
    }
    @Test
    void statSnapshotShouldResolveLatestPeriodWithPeriodColumn() {
        AnalysisPlan plan = new AnalysisPlan("stat_monthly", "统计月报", List.of("各项存款"),
                List.of("指标"), "最新期间", "bar", List.of());
        SqlGenerator.GeneratedSql result = generator.generate(plan, intentFor("STAT_TREND"));
        assertTrue(result.sql().startsWith("SELECT period, indicator_name AS industry"), "快照 SQL 应带 period 列: " + result.sql());
        assertTrue(result.sql().contains("period = (SELECT period FROM stat_monthly"), "快照应动态取最新期别: " + result.sql());
        assertEquals("RULE", result.generatorType());
    }

    @Test
    void statTrendShouldKeepSameCumulativePeriod() {
        AnalysisPlan plan = new AnalysisPlan("stat_monthly", "统计月报", List.of("产业投资"),
                List.of("期间", "指标"), "近3年", "line", List.of());
        SqlGenerator.GeneratedSql result = generator.generate(plan, intentFor("STAT_TREND"));
        assertTrue(result.sql().contains("SUBSTRING(period, LOCATE('年', period) + 1) = (SELECT SUBSTRING(period, LOCATE('年', period) + 1)"),
                "趋势应锚定同累计期别: " + result.sql());
        assertTrue(result.sql().contains("ORDER BY period, indicator_name"), "趋势应按期间排序: " + result.sql());
    }
}
