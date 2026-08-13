package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.service.AnalysisConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于模板的规则 SQL 生成器：模板来自分析配置中心（库空回退内置），{timeRange} 按时间范围替换。 */
@Component
public class RuleSqlGenerator implements SqlGenerator {

    private static final Pattern TIME_UNIT_PATTERN = Pattern.compile("(\\d+)\\s*(年|个月|月|周|星期|天|日)");
    private static final String DEFAULT_INTERVAL = "DATE_SUB(CURDATE(), INTERVAL 30 DAY)";
    private static final String GENERAL_TYPE = "GENERAL";
    private static final String GOV_TABLE = "GOV_INFO_RECORD";

    private final AnalysisConfigService configService;

    /** 测试兜底：使用内置配置（不访问数据库）。 */
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
        boolean govTarget = GOV_TABLE.equalsIgnoreCase(targetTable);
        String typeCode = plan != null && plan.getPlanType() != null && !plan.getPlanType().isBlank()
                ? plan.getPlanType().trim().toUpperCase(Locale.ROOT)
                : (govTarget ? "GOV" : "NORMAL");
        AnalysisConfigService.PlanConfigSpec spec = configService.resolvePlanSpec(type, typeCode);
        String template = spec == null || spec.sqlTemplate() == null ? null : spec.sqlTemplate();
        if (template == null) {
            template = generalTemplate(govTarget || "GOV".equals(typeCode));
        }
        String sql = template.replace("{timeRange}", buildTimeRange(plan));
        return new GeneratedSql(sql, "RULE");
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