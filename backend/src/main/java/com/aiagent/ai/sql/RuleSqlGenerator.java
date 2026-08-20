package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.service.AnalysisConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于模板的规则 SQL 生成器：模板来自分析配置中心（库空回退内置），{timeRange} 按时间范围替换。 */
@Component
public class RuleSqlGenerator implements SqlGenerator {

    private static final Pattern TIME_UNIT_PATTERN = Pattern.compile("(\\d+)\\s*(年|个月|月|周|星期|天|日)");
    private static final String DEFAULT_INTERVAL = "DATE_SUB(CURDATE(), INTERVAL 30 DAY)";
    private static final Pattern YEAR_RANGE_PATTERN = Pattern.compile("(\\d+)\\s*年");
    private static final String GENERAL_TYPE = "GENERAL";
    private static final String GOV_TABLE = "GOV_INFO_RECORD";

    private final AnalysisConfigService configService;

    public RuleSqlGenerator() {
        this(AnalysisConfigService.builtinOnly());
    }

    @Autowired
    public RuleSqlGenerator(AnalysisConfigService configService) {
        this.configService = configService;
    }

    @Override
    public GeneratedSql generate(AnalysisPlan plan, RecognizedIntent intent) {
        String type = intent == null || intent.getIntentType() == null ? GENERAL_TYPE : intent.getIntentType();
        String targetTable = plan == null ? null : plan.getTargetTable();
        // 统计月报：一律按结构化指标生成（趋势按期间、快照取最新期间），与意图类型无关，避免 SALES_TREND 等误入通用模板
        if ("stat_monthly".equalsIgnoreCase(targetTable)) {
            return new GeneratedSql(statStructureSql(plan), "RULE");
        }

        boolean govTarget = GOV_TABLE.equalsIgnoreCase(targetTable);
        String typeCode = plan != null && plan.getPlanType() != null && !plan.getPlanType().isBlank()
                ? plan.getPlanType().trim().toUpperCase(Locale.ROOT)
                : (govTarget ? "GOV" : "NORMAL");
        AnalysisConfigService.PlanConfigSpec spec = configService.resolvePlanSpec(type, typeCode, targetTable);
        String template = spec == null || spec.sqlTemplate() == null ? null : spec.sqlTemplate();
        if (template == null) {
            template = generalTemplate(govTarget || "GOV".equals(typeCode));
        }
        String sql = template.replace("{timeRange}", buildTimeRange(plan));
        return new GeneratedSql(sql, "RULE");
    }

    /** STRUCTURE + stat_monthly：趋势（line）按期间取三大产业/区县数据（近N年过滤），快照取最新期间；指标按计划收敛。 */
    private static String statStructureSql(AnalysisPlan plan) {
        boolean trend = "line".equalsIgnoreCase(plan.getChartType());
        boolean regionDim = plan.getDimensions() != null
                && (plan.getDimensions().contains("区县") || plan.getDimensions().contains("region"));
        if (regionDim) {
            // 区县口径按计划指标取数（默认地区生产总值，治理后统一用规范名）；值列兼容 value 与 growth_rate（增速类指标只有 growth_rate）
            String indicator = plan.getMetrics() == null || plan.getMetrics().isEmpty()
                    ? "地区生产总值" : canonicalIndicator(plan.getMetrics().get(0));
            String scope = "indicator_name = '" + indicator + "' AND region <> '全市' AND (value IS NOT NULL OR growth_rate IS NOT NULL)";
            if (trend) {
                return "SELECT period, region, COALESCE(value, growth_rate) AS value, growth_rate, COALESCE(NULLIF(unit, ''), '%') AS unit FROM stat_monthly "
                        + "WHERE " + scope + yearFilter(plan.getTimeRange(), "'" + indicator + "'", false) + periodTypeFilter(scope)
                        + " ORDER BY period, region";
            }
            String periodScope = scope + absoluteYearClause(plan.getTimeRange());
            return "SELECT region, COALESCE(value, growth_rate) AS value, growth_rate, COALESCE(NULLIF(unit, ''), '%') AS unit FROM stat_monthly "
                    + "WHERE " + scope
                    + " AND period = (SELECT period FROM stat_monthly WHERE " + periodScope + " ORDER BY "
                    + "CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) DESC, "
                    + "CAST(SUBSTRING(period, LOCATE('-', CONCAT(period, '-')) + 1, 2) AS UNSIGNED) DESC LIMIT 1) "
                    + "ORDER BY value DESC";
        }
        List<String> metrics = expandIncome(plan.getMetrics() == null || plan.getMetrics().isEmpty()
                ? List.of("第一产业", "第二产业", "第三产业") : plan.getMetrics());
        String inList = String.join(", ", metrics.stream().map(RuleSqlGenerator::canonicalIndicator)
                .map(m -> "'" + m + "'").toList());
        String scope = "indicator_name IN (" + inList + ") AND region = '全市' AND (value IS NOT NULL OR growth_rate IS NOT NULL)";
        if (trend) {
            return "SELECT period, indicator_name AS industry, COALESCE(value, growth_rate) AS value, COALESCE(NULLIF(unit, ''), '%') AS unit FROM stat_monthly "
                    + "WHERE " + scope + yearFilter(plan.getTimeRange(), inList, true) + periodTypeFilter(scope)
                    + " ORDER BY period, indicator_name";
        }
        String industryPeriodScope = scope + absoluteYearClause(plan.getTimeRange());
        return "SELECT period, indicator_name AS industry, COALESCE(value, growth_rate) AS value, COALESCE(NULLIF(unit, ''), '%') AS unit FROM stat_monthly "
                + "WHERE " + scope
                + " AND period = (SELECT period FROM stat_monthly WHERE " + industryPeriodScope + " ORDER BY "
                + "CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) DESC, "
                + "CAST(SUBSTRING(period, LOCATE('-', CONCAT(period, '-')) + 1, 2) AS UNSIGNED) DESC LIMIT 1) "
                + "ORDER BY value DESC";
    }

    /** 指标治理后的规范名映射：旧模板名/别名 → stat_monthly.indicator_name 当前规范名（治理已把库内指标名归一）。 */
    private static final Map<String, String> INDICATOR_CANONICAL = Map.of(
            "第一产业", "第一产业增加值",
            "第二产业", "第二产业增加值",
            "第三产业", "第三产业增加值",
            "地区生产总值(GDP)", "地区生产总值",
            "生产总值(GDP)", "地区生产总值",
            "分县（市、区）GDP", "地区生产总值",
            "分县(市、区)GDP", "地区生产总值");

    /** 收入指标体系：任一命中时补齐 全体/城镇/农村 三系列，保证城乡收入比可算。 */
    private static final Set<String> INCOME_INDICATORS = Set.of(
            "全体居民人均可支配收入", "城镇居民人均可支配收入", "农村居民人均可支配收入");

    /** 收入指标三系列补齐。 */
    private static List<String> expandIncome(List<String> metrics) {
        boolean hit = metrics != null && metrics.stream().anyMatch(INCOME_INDICATORS::contains);
        return hit ? List.of("全体居民人均可支配收入", "城镇居民人均可支配收入", "农村居民人均可支配收入") : metrics;
    }

    /**
     * 累计期别过滤：趋势只保留各年同一累计期别（以该指标最新期别为基准，如 2025年1-9月 → 各年 1-9月），
     * 避免跨期别混画产生「一季度→三季度累计增幅 182%」类假象。
     */
    private static String periodTypeFilter(String baseScope) {
        return " AND SUBSTRING(period, LOCATE('年', period) + 1) = "
                + "(SELECT SUBSTRING(period, LOCATE('年', period) + 1) FROM stat_monthly WHERE " + baseScope
                + " ORDER BY CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) DESC, "
                + "CAST(SUBSTRING(period, LOCATE('-', CONCAT(period, '-')) + 1, 2) AS UNSIGNED) DESC LIMIT 1)";
    }

    /** 指标名归一：命中映射返回规范名，未命中原样透传（治理后其余指标名已为规范名）。 */
    private static String canonicalIndicator(String name) {
        String canonical = INDICATOR_CANONICAL.get(name);
        return canonical == null ? name : canonical;
    }

    /** 近N年时间范围 → 以数据最新年份为锚的年份下限过滤；无匹配返回空串。 */
    private static String yearFilter(String timeRange, String inList, boolean regionAll) {
        if (timeRange == null) {
            return "";
        }
        Matcher matcher = YEAR_RANGE_PATTERN.matcher(timeRange);
        if (!matcher.find()) {
            return "";
        }
        int years = Integer.parseInt(matcher.group(1));
        if (years <= 0) {
            return "";
        }
        // 绝对年份（如「2024年」）：精确过滤当年，避免误当「近N年」窗口
        String absolute = absoluteYearClause(timeRange);
        if (!absolute.isEmpty()) {
            return absolute;
        }
        String subScope = regionAll
                ? "indicator_name IN (" + inList + ") AND region = '全市' AND (value IS NOT NULL OR growth_rate IS NOT NULL)"
                : "indicator_name = " + inList + " AND region <> '全市' AND (value IS NOT NULL OR growth_rate IS NOT NULL)";
        return " AND CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) >= "
                + "(SELECT MAX(CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED)) FROM stat_monthly "
                + "WHERE " + subScope + ") - " + years + " + 1";
    }

    /** 绝对年份（如「2023年」）→ 当年过滤子句；非绝对年份（最新期间/近N年）返回空串，快照仍取表内最新期间。 */
    private static String absoluteYearClause(String timeRange) {
        if (timeRange == null) {
            return "";
        }
        Matcher matcher = YEAR_RANGE_PATTERN.matcher(timeRange);
        if (!matcher.find()) {
            return "";
        }
        int years = Integer.parseInt(matcher.group(1));
        if (years < 1000 || years > 2999) {
            return "";
        }
        return " AND CAST(SUBSTRING_INDEX(period, '年', 1) AS UNSIGNED) = " + years;
    }

    /** 内置 GENERAL 兜底模板（与旧静态配置一致）。 */
    private static String generalTemplate(boolean gov) {
        if (gov) {
            return "SELECT category, publish_unit, COUNT(*) AS doc_count "
                    + "FROM gov_info_record GROUP BY category, publish_unit";
        }
        return "SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount, "
                + "ROUND(SUM(sales_amount) / NULLIF(SUM(order_count), 0), 2) AS avg_order_amount "
                + "FROM order_info GROUP BY order_date, region ORDER BY order_date";
    }

    /** 从计划时间范围解析天数与单位，替换 {timeRange} 为 DATE_SUB 表达式；无匹配默认 30 天。 */
    private String buildTimeRange(AnalysisPlan plan) {
        String range = plan == null || plan.getTimeRange() == null ? null : plan.getTimeRange();
        if (range != null) {
            Matcher matcher = TIME_UNIT_PATTERN.matcher(range);
            if (matcher.find()) {
                // 4 位年份（绝对年份，如 2024年）不适用于 order_info 这类 DATE_SUB 相对窗口，回退默认区间
                if ("年".equals(matcher.group(2)) && Integer.parseInt(matcher.group(1)) >= 1000
                        && Integer.parseInt(matcher.group(1)) <= 2999) {
                    return DEFAULT_INTERVAL;
                }
                String unit = switch (matcher.group(2)) {
                    case "年" -> "YEAR";
                    case "个月", "月" -> "MONTH";
                    case "周", "星期" -> "WEEK";
                    default -> "DAY";
                };
                return "DATE_SUB(CURDATE(), INTERVAL " + matcher.group(1) + " " + unit + ")";
            }
        }
        return DEFAULT_INTERVAL;
    }
}
