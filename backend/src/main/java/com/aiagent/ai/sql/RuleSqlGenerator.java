package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于静态模板的规则 SQL 生成器（演示口径，物理列名定义在本类内）。 */
@Component
public class RuleSqlGenerator implements SqlGenerator {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final String DEFAULT_DAYS = "30";
    private static final String GENERAL_TYPE = "GENERAL";
    private static final String GOV_TABLE = "GOV_INFO_RECORD";

    /** 意图 → 模板 SQL，按意图类型排列，未知/空意图回退 GENERAL。 */
    private static final Map<String, String> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("SALES_TREND",
                "SELECT order_date, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount "
                        + "FROM order_info WHERE order_date >= {timeRange} GROUP BY order_date ORDER BY order_date");
        TEMPLATES.put("USER_PROFILE",
                "SELECT age_group, city, SUM(new_user_count) AS new_user_count, SUM(active_user_count) AS active_user_count "
                        + "FROM user_info GROUP BY age_group, city");
        TEMPLATES.put("COMPARISON",
                "SELECT region, channel, SUM(sales_amount) AS sales_amount, SUM(order_count) AS order_count "
                        + "FROM order_info GROUP BY region, channel");
        TEMPLATES.put("RANKING",
                "SELECT category, SUM(sales_volume) AS sales_volume, SUM(sales_amount) AS sales_amount "
                        + "FROM product_info GROUP BY category ORDER BY SUM(sales_volume) DESC LIMIT 10");
        TEMPLATES.put("STRUCTURE",
                "SELECT category, SUM(sales_amount) AS sales_amount FROM order_info GROUP BY category");
        TEMPLATES.put("RETENTION",
                "SELECT register_date, AVG(retention_rate) AS retention_rate FROM user_info GROUP BY register_date ORDER BY register_date");
        TEMPLATES.put("ANOMALY",
                "SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount "
                        + "FROM order_info GROUP BY order_date, region ORDER BY order_date DESC LIMIT 30");
        TEMPLATES.put("GENERAL",
                "SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount, "
                        + "ROUND(SUM(sales_amount) / NULLIF(SUM(order_count), 0), 2) AS avg_order_amount "
                        + "FROM order_info GROUP BY order_date, region ORDER BY order_date");
    }

    /** GOV 意图 → 模板 SQL：目标表为 GOV_INFO_RECORD 时使用，其余意图回退 GENERAL gov 模板。 */
    private static final Map<String, String> GOV_TEMPLATES = new LinkedHashMap<>();

    static {
        GOV_TEMPLATES.put("SALES_TREND",
                "SELECT DATE_FORMAT(publish_date,'%Y-%m') AS month, COUNT(*) AS doc_count "
                        + "FROM gov_info_record WHERE publish_date >= {timeRange} GROUP BY month ORDER BY month");
        GOV_TEMPLATES.put("RANKING",
                "SELECT publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY publish_unit "
                        + "ORDER BY doc_count DESC LIMIT 10");
        GOV_TEMPLATES.put("STRUCTURE",
                "SELECT category, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category");
        GOV_TEMPLATES.put("GENERAL",
                "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit");
    }

    @Override
    public GeneratedSql generate(AnalysisPlan plan, RecognizedIntent intent) {
        String type = intent == null || intent.getIntentType() == null ? GENERAL_TYPE : intent.getIntentType();
        String targetTable = plan == null ? null : plan.getTargetTable();
        Map<String, String> templates = GOV_TABLE.equalsIgnoreCase(targetTable) ? GOV_TEMPLATES : TEMPLATES;
        String template = templates.getOrDefault(type, templates.get(GENERAL_TYPE));
        String sql = template.replace("{timeRange}", buildTimeRange(plan));
        return new GeneratedSql(sql, "RULE");
    }

    /** 从计划时间范围提取天数，替换 {timeRange} 为 DATE_SUB 表达式；无数字/为 null 默认 30 天。 */
    private String buildTimeRange(AnalysisPlan plan) {
        String range = plan == null || plan.getTimeRange() == null ? null : plan.getTimeRange();
        String days = DEFAULT_DAYS;
        if (range != null) {
            Matcher matcher = NUMBER_PATTERN.matcher(range);
            if (matcher.find()) {
                days = matcher.group();
            }
        }
        return "DATE_SUB(CURDATE(), INTERVAL " + days + " DAY)";
    }
}