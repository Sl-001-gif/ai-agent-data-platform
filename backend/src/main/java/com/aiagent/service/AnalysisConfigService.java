package com.aiagent.service;

import com.aiagent.entity.AnalysisIntentRule;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.mapper.AnalysisConfigMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 分析规则配置中心：意图规则与计划配置优先读库，库表为空时回退内置默认值（与旧静态配置逐字一致，
 * 保证行为不变）。管理员在管理端增删改后，规则引擎立即按新配置生效。
 */
@Service
public class AnalysisConfigService {

    /** 内置政务关键词：命中即在 matchedKeywords 追加「政务公开」，供计划器路由到 GOV_INFO_RECORD。 */
    private static final List<String> GOV_KEYWORDS = List.of("政务", "公开", "政府", "发文", "邵阳", "新宁");

    private final AnalysisConfigMapper analysisConfigMapper;

    public AnalysisConfigService(AnalysisConfigMapper analysisConfigMapper) {
        this.analysisConfigMapper = analysisConfigMapper;
    }

    /** 仅内置配置的服务实例：用于规则引擎无参构造的测试兜底，不访问数据库。 */
    public static AnalysisConfigService builtinOnly() {
        return new AnalysisConfigService(null);
    }

    public List<String> govKeywords() {
        return GOV_KEYWORDS;
    }

    /** 意图规则（按优先级升序）。库空回退内置。 */
    public List<IntentRuleSpec> intentRules() {
        List<AnalysisIntentRule> rows = analysisConfigMapper == null ? List.of() : analysisConfigMapper.selectIntentRules();
        if (rows == null || rows.isEmpty()) {
            return BUILTIN_INTENT_RULES;
        }
        List<IntentRuleSpec> specs = new ArrayList<>();
        for (AnalysisIntentRule row : rows) {
            if (row.getStatus() != null && row.getStatus() == 0) {
                continue;
            }
            if (row.getIntentCode() == null || row.getIntentCode().isBlank() || row.getKeywords() == null) {
                continue;
            }
            specs.add(new IntentRuleSpec(row.getIntentCode().trim().toUpperCase(Locale.ROOT),
                    row.getIntentName() == null ? row.getIntentCode() : row.getIntentName(),
                    splitCsv(row.getKeywords())));
        }
        return specs;
    }

    /** 计划配置（普通 + 政务）。库空回退内置 16 条。 */
    public List<PlanConfigSpec> planConfigs() {
        List<AnalysisPlanConfig> rows = analysisConfigMapper == null ? List.of() : analysisConfigMapper.selectPlanConfigs();
        if (rows == null || rows.isEmpty()) {
            return BUILTIN_PLAN_CONFIGS;
        }
        List<PlanConfigSpec> specs = new ArrayList<>();
        for (AnalysisPlanConfig row : rows) {
            if (row.getStatus() != null && row.getStatus() == 0) {
                continue;
            }
            if (row.getIntentCode() == null || row.getIntentCode().isBlank() || row.getTableName() == null) {
                continue;
            }
            specs.add(new PlanConfigSpec(row.getIntentCode().trim().toUpperCase(Locale.ROOT),
                    row.getIsGov() != null && row.getIsGov() == 1,
                    row.getTableName().trim(),
                    splitCsv(row.getMetrics()),
                    splitCsv(row.getDimensions()),
                    row.getChartType() == null || row.getChartType().isBlank() ? "table" : row.getChartType(),
                    row.getTimeRange() == null || row.getTimeRange().isBlank() ? "近30天" : row.getTimeRange(),
                    row.getSqlTemplate()));
        }
        return specs;
    }

    // ---------- 管理端 CRUD ----------

    public List<AnalysisIntentRule> listIntentRules() {
        return analysisConfigMapper.selectIntentRules();
    }

    public AnalysisIntentRule createIntentRule(AnalysisIntentRule rule) {
        requireNotBlank(rule.getIntentCode(), "意图编码不能为空");
        requireNotBlank(rule.getKeywords(), "关键词不能为空");
        if (rule.getPriority() == null) {
            rule.setPriority(0);
        }
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        analysisConfigMapper.insertIntentRule(rule);
        return rule;
    }

    public void updateIntentRule(Long id, AnalysisIntentRule rule) {
        requireExisting(analysisConfigMapper.selectIntentRuleById(id), "意图规则不存在");
        rule.setId(id);
        analysisConfigMapper.updateIntentRule(rule);
    }

    public void deleteIntentRule(Long id) {
        requireRows(analysisConfigMapper.deleteIntentRule(id), "意图规则不存在");
    }

    public List<AnalysisPlanConfig> listPlanConfigs() {
        return analysisConfigMapper.selectPlanConfigs();
    }

    public AnalysisPlanConfig createPlanConfig(AnalysisPlanConfig config) {
        requireNotBlank(config.getIntentCode(), "意图编码不能为空");
        requireNotBlank(config.getTableName(), "目标表名不能为空");
        if (config.getIsGov() == null) {
            config.setIsGov(0);
        }
        if (config.getChartType() == null || config.getChartType().isBlank()) {
            config.setChartType("table");
        }
        if (config.getTimeRange() == null || config.getTimeRange().isBlank()) {
            config.setTimeRange("近30天");
        }
        if (config.getStatus() == null) {
            config.setStatus(1);
        }
        if (config.getSort() == null) {
            config.setSort(0);
        }
        analysisConfigMapper.insertPlanConfig(config);
        return config;
    }

    public void updatePlanConfig(Long id, AnalysisPlanConfig config) {
        requireExisting(analysisConfigMapper.selectPlanConfigById(id), "计划配置不存在");
        config.setId(id);
        analysisConfigMapper.updatePlanConfig(config);
    }

    public void deletePlanConfig(Long id) {
        requireRows(analysisConfigMapper.deletePlanConfig(id), "计划配置不存在");
    }

    private static void requireExisting(Object existing, String message) {
        if (existing == null) {
            throw new RuntimeException(message);
        }
    }

    private static void requireRows(int rows, String message) {
        if (rows == 0) {
            throw new RuntimeException(message);
        }
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException(message);
        }
    }

    /** 逗号分隔字符串 → 去空白关键词列表。 */
    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 意图规则配置（供规则引擎消费）。 */
    public record IntentRuleSpec(String code, String name, List<String> keywords) {
    }

    /** 计划配置（供计划器/规则 SQL 消费）。 */
    public record PlanConfigSpec(String intentCode, boolean gov, String tableName, List<String> metrics,
                                 List<String> dimensions, String chartType, String timeRange, String sqlTemplate) {
    }

    // ---------- 内置默认（与旧静态配置逐字一致） ----------

    private static final List<IntentRuleSpec> BUILTIN_INTENT_RULES = List.of(
            new IntentRuleSpec("USER_PROFILE", "用户画像", List.of("用户画像", "人群画像", "客户画像", "画像", "人群", "偏好", "特征")),
            new IntentRuleSpec("ANOMALY", "异常归因", List.of("下降", "下跌", "异常", "原因", "归因", "波动", "为什么")),
            new IntentRuleSpec("RETENTION", "留存转化", List.of("留存", "转化", "复购", "流失")),
            new IntentRuleSpec("COMPARISON", "对比分析", List.of("对比", "比较", "差异")),
            new IntentRuleSpec("STRUCTURE", "占比结构", List.of("占比", "结构", "构成", "比例", "份额")),
            new IntentRuleSpec("RANKING", "排名分析", List.of("排名", "排行", "最好", "最差", "top10", "top 10", "前10")),
            new IntentRuleSpec("SALES_TREND", "销售趋势", List.of("销售", "销售额", "销量", "营收", "收入", "趋势", "走势", "增长")));

    private static final String GOV_TABLE = "GOV_INFO_RECORD";

    private static final List<PlanConfigSpec> BUILTIN_PLAN_CONFIGS = List.of(
            new PlanConfigSpec("SALES_TREND", false, "order_info", List.of("订单量", "销售额"), List.of("日期"), "line", "近30天",
                    "SELECT order_date, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount "
                            + "FROM order_info WHERE order_date >= {timeRange} GROUP BY order_date ORDER BY order_date"),
            new PlanConfigSpec("USER_PROFILE", false, "user_info", List.of("新增用户数", "活跃用户数"), List.of("年龄段", "城市"), "bar", "近30天",
                    "SELECT age_group, city, SUM(new_user_count) AS new_user_count, SUM(active_user_count) AS active_user_count "
                            + "FROM user_info GROUP BY age_group, city"),
            new PlanConfigSpec("COMPARISON", false, "order_info", List.of("销售额", "订单量"), List.of("区域", "渠道"), "bar", "近30天",
                    "SELECT region, channel, SUM(sales_amount) AS sales_amount, SUM(order_count) AS order_count "
                            + "FROM order_info GROUP BY region, channel"),
            new PlanConfigSpec("RANKING", false, "product_info", List.of("销量", "销售额"), List.of("品类"), "bar", "近30天",
                    "SELECT category, SUM(sales_volume) AS sales_volume, SUM(sales_amount) AS sales_amount "
                            + "FROM product_info GROUP BY category ORDER BY SUM(sales_volume) DESC LIMIT 10"),
            new PlanConfigSpec("STRUCTURE", false, "order_info", List.of("销售额"), List.of("品类"), "pie", "近30天",
                    "SELECT category, SUM(sales_amount) AS sales_amount FROM order_info GROUP BY category"),
            new PlanConfigSpec("RETENTION", false, "user_info", List.of("留存率", "新增用户数"), List.of("日期"), "line", "近30天",
                    "SELECT register_date, AVG(retention_rate) AS retention_rate FROM user_info GROUP BY register_date ORDER BY register_date"),
            new PlanConfigSpec("ANOMALY", false, "order_info", List.of("订单量", "销售额"), List.of("日期", "区域"), "table", "近30天",
                    "SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount "
                            + "FROM order_info GROUP BY order_date, region ORDER BY order_date DESC LIMIT 30"),
            new PlanConfigSpec("GENERAL", false, "order_info", List.of("订单量", "销售额", "客单价"), List.of("日期", "区域"), "table", "近30天",
                    "SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount, "
                            + "ROUND(SUM(sales_amount) / NULLIF(SUM(order_count), 0), 2) AS avg_order_amount "
                            + "FROM order_info GROUP BY order_date, region ORDER BY order_date"),
            new PlanConfigSpec("SALES_TREND", true, GOV_TABLE, List.of("发文量", "日均发文量"), List.of("发布日期"), "line", "近30天",
                    "SELECT DATE_FORMAT(publish_date,'%Y-%m') AS month, COUNT(*) AS doc_count "
                            + "FROM gov_info_record WHERE publish_date >= {timeRange} GROUP BY month ORDER BY month"),
            new PlanConfigSpec("RANKING", true, GOV_TABLE, List.of("发文量"), List.of("公开单位"), "bar", "近30天",
                    "SELECT COALESCE(NULLIF(publish_unit,''), category) AS unit, COUNT(*) AS doc_count "
                            + "FROM gov_info_record GROUP BY unit ORDER BY doc_count DESC LIMIT 10"),
            new PlanConfigSpec("STRUCTURE", true, GOV_TABLE, List.of("发文量"), List.of("公开类目"), "pie", "近30天",
                    "SELECT category, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category"),
            new PlanConfigSpec("USER_PROFILE", true, GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("COMPARISON", true, GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("RETENTION", true, GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("ANOMALY", true, GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("GENERAL", true, GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"));
}