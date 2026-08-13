package com.aiagent.service;

import com.aiagent.entity.AnalysisIntentRule;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.entity.AnalysisPlanType;
import com.aiagent.mapper.AnalysisConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 分析规则配置中心：意图规则与计划配置优先读库，库表为空时回退内置默认值（与旧静态配置逐字一致，
 * 保证行为不变）。管理员在管理端增删改后，规则引擎立即按新配置生效。
 * 计划类型：普通/政务/自定义字典，类型支持启用/停用；停用类型的计划配置不参与 AI 路由。
 */
@Service
public class AnalysisConfigService {

    /** 内置政务关键词：命中即在 matchedKeywords 追加「政务公开」，供计划器路由到 GOV 类型。 */
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
            specs.add(new IntentRuleSpec(upper(row.getIntentCode()),
                    row.getIntentName() == null ? row.getIntentCode() : row.getIntentName(),
                    splitCsv(row.getKeywords())));
        }
        return specs;
    }

    /** 计划配置（按类型分组）。库空回退内置 16 条；停用类型的配置被过滤。 */
    public List<PlanConfigSpec> planConfigs() {
        List<AnalysisPlanConfig> rows = analysisConfigMapper == null ? List.of() : analysisConfigMapper.selectPlanConfigs();
        if (rows == null || rows.isEmpty()) {
            return BUILTIN_PLAN_CONFIGS;
        }
        Set<String> disabledTypes = disabledTypeCodes();
        List<PlanConfigSpec> specs = new ArrayList<>();
        for (AnalysisPlanConfig row : rows) {
            if (row.getStatus() != null && row.getStatus() == 0) {
                continue;
            }
            if (row.getIntentCode() == null || row.getIntentCode().isBlank() || row.getTableName() == null) {
                continue;
            }
            String typeCode = normalizeType(row.getPlanType(), row.getIsGov());
            if (disabledTypes.contains(typeCode)) {
                continue;
            }
            specs.add(new PlanConfigSpec(upper(row.getIntentCode()), typeCode,
                    row.getTableName().trim(),
                    splitCsv(row.getMetrics()),
                    splitCsv(row.getDimensions()),
                    row.getChartType() == null || row.getChartType().isBlank() ? "table" : row.getChartType(),
                    row.getTimeRange() == null || row.getTimeRange().isBlank() ? "近30天" : row.getTimeRange(),
                    row.getSqlTemplate()));
        }
        return specs;
    }

    /** 启用中的计划类型（供计划器做关键词类型路由）。库空回退内置 3 类。 */
    public List<PlanTypeSpec> planTypes() {
        List<AnalysisPlanType> rows = analysisConfigMapper == null ? List.of() : analysisConfigMapper.selectPlanTypes();
        if (rows == null || rows.isEmpty()) {
            return BUILTIN_PLAN_TYPES;
        }
        List<PlanTypeSpec> specs = new ArrayList<>();
        for (AnalysisPlanType row : rows) {
            if (row.getStatus() != null && row.getStatus() == 0) {
                continue;
            }
            if (row.getTypeCode() == null || row.getTypeCode().isBlank()) {
                continue;
            }
            specs.add(new PlanTypeSpec(upper(row.getTypeCode()),
                    row.getTypeName() == null ? row.getTypeCode() : row.getTypeName(),
                    splitCsv(row.getRouteKeywords())));
        }
        return specs;
    }

    /**
     * 按（意图编码 × 计划类型）解析计划配置，供计划器与规则 SQL 生成器共用，保证表与模板一致。
     * 匹配链：同类型命中 → 同类型 GENERAL → 普通类型命中 → 普通类型 GENERAL。
     */
    public PlanConfigSpec resolvePlanSpec(String intentType, String typeCode) {
        List<PlanConfigSpec> specs = planConfigs();
        PlanConfigSpec matched = null;
        PlanConfigSpec general = null;
        PlanConfigSpec normMatched = null;
        PlanConfigSpec normGeneral = null;
        for (PlanConfigSpec spec : specs) {
            if (spec.typeCode().equals(typeCode)) {
                if (spec.intentCode().equals(intentType) && matched == null) {
                    matched = spec;
                }
                if ("GENERAL".equals(spec.intentCode()) && general == null) {
                    general = spec;
                }
            } else if ("NORMAL".equals(spec.typeCode())) {
                if (spec.intentCode().equals(intentType) && normMatched == null) {
                    normMatched = spec;
                }
                if ("GENERAL".equals(spec.intentCode())) {
                    normGeneral = spec;
                }
            }
        }
        if (matched != null) {
            return matched;
        }
        if (general != null) {
            return general;
        }
        if (normMatched != null) {
            return normMatched;
        }
        return normGeneral != null ? normGeneral : (specs.isEmpty() ? null : specs.get(0));
    }

    /** 停用类型的编码集合。 */
    private Set<String> disabledTypeCodes() {
        if (analysisConfigMapper == null) {
            return Set.of();
        }
        List<AnalysisPlanType> types = analysisConfigMapper.selectPlanTypes();
        if (types == null || types.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (AnalysisPlanType type : types) {
            if (type.getStatus() != null && type.getStatus() == 0 && type.getTypeCode() != null) {
                set.add(upper(type.getTypeCode()));
            }
        }
        return set;
    }

    /** plan_type 规范化：空值按旧 is_gov 兼容推导。 */
    static String normalizeType(String planType, Integer isGov) {
        if (planType != null && !planType.isBlank()) {
            return planType.trim().toUpperCase(Locale.ROOT);
        }
        return isGov != null && isGov == 1 ? "GOV" : "NORMAL";
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    // ---------- 管理端 CRUD：意图规则 ----------

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

    // ---------- 管理端 CRUD：计划配置 ----------

    public List<AnalysisPlanConfig> listPlanConfigs() {
        return analysisConfigMapper.selectPlanConfigs();
    }

    public AnalysisPlanConfig createPlanConfig(AnalysisPlanConfig config) {
        requireNotBlank(config.getIntentCode(), "意图编码不能为空");
        requireNotBlank(config.getTableName(), "目标表名不能为空");
        syncType(config);
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
        syncType(config);
        config.setId(id);
        analysisConfigMapper.updatePlanConfig(config);
    }

    public void deletePlanConfig(Long id) {
        requireRows(analysisConfigMapper.deletePlanConfig(id), "计划配置不存在");
    }

    /** 类型与旧 is_gov 列双向一致：GOV→is_gov=1，其余→0。 */
    private static void syncType(AnalysisPlanConfig config) {
        String typeCode = normalizeType(config.getPlanType(), config.getIsGov());
        config.setPlanType(typeCode);
        config.setIsGov("GOV".equals(typeCode) ? 1 : 0);
    }

    // ---------- 管理端 CRUD：计划类型 ----------

    public List<AnalysisPlanType> listPlanTypes() {
        return analysisConfigMapper.selectPlanTypes();
    }

    public AnalysisPlanType createPlanType(AnalysisPlanType type) {
        requireNotBlank(type.getTypeCode(), "类型编码不能为空");
        requireNotBlank(type.getTypeName(), "类型名称不能为空");
        String code = upper(type.getTypeCode());
        if (analysisConfigMapper.selectPlanTypeByCode(code) != null) {
            throw new RuntimeException("类型编码已存在：" + code);
        }
        type.setTypeCode(code);
        if (type.getColor() == null || type.getColor().isBlank()) {
            type.setColor("#409eff");
        }
        if (type.getSort() == null) {
            type.setSort(0);
        }
        if (type.getStatus() == null) {
            type.setStatus(1);
        }
        analysisConfigMapper.insertPlanType(type);
        return type;
    }

    public void updatePlanType(Long id, AnalysisPlanType type) {
        requireExisting(analysisConfigMapper.selectPlanTypeById(id), "计划类型不存在");
        requireNotBlank(type.getTypeCode(), "类型编码不能为空");
        requireNotBlank(type.getTypeName(), "类型名称不能为空");
        String code = upper(type.getTypeCode());
        AnalysisPlanType existing = analysisConfigMapper.selectPlanTypeByCode(code);
        if (existing != null && !existing.getId().equals(id)) {
            throw new RuntimeException("类型编码已存在：" + code);
        }
        type.setId(id);
        type.setTypeCode(code);
        analysisConfigMapper.updatePlanType(type);
    }

    @Transactional
    public void deletePlanType(Long id) {
        AnalysisPlanType type = analysisConfigMapper.selectPlanTypeById(id);
        requireExisting(type, "计划类型不存在");
        if ("NORMAL".equals(type.getTypeCode())) {
            throw new RuntimeException("内置「普通」类型不可删除");
        }
        analysisConfigMapper.clearPlanTypeRefs(type.getTypeCode());
        requireRows(analysisConfigMapper.deletePlanType(id), "计划类型不存在");
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

    /** 计划类型配置（供计划器关键词路由消费）。 */
    public record PlanTypeSpec(String code, String name, List<String> keywords) {
    }

    /** 计划配置（供计划器/规则 SQL 消费），typeCode 替代旧 gov 布尔。 */
    public record PlanConfigSpec(String intentCode, String typeCode, String tableName, List<String> metrics,
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

    private static final List<PlanTypeSpec> BUILTIN_PLAN_TYPES = List.of(
            new PlanTypeSpec("NORMAL", "普通", List.of()),
            new PlanTypeSpec("GOV", "政务", GOV_KEYWORDS),
            new PlanTypeSpec("STAT", "统计", List.of("统计", "gdp", "生产总值", "财政收入", "规上工业", "规模以上工业",
                    "工业增加值", "居民收入", "社会消费品零售", "固定资产投资", "统计局", "经济指标", "增速", "增幅")));

    private static final List<PlanConfigSpec> BUILTIN_PLAN_CONFIGS = List.of(
            new PlanConfigSpec("SALES_TREND", "NORMAL", "order_info", List.of("订单量", "销售额"), List.of("日期"), "line", "近30天",
                    "SELECT order_date, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount "
                            + "FROM order_info WHERE order_date >= {timeRange} GROUP BY order_date ORDER BY order_date"),
            new PlanConfigSpec("USER_PROFILE", "NORMAL", "user_info", List.of("新增用户数", "活跃用户数"), List.of("年龄段", "城市"), "bar", "近30天",
                    "SELECT age_group, city, SUM(new_user_count) AS new_user_count, SUM(active_user_count) AS active_user_count "
                            + "FROM user_info GROUP BY age_group, city"),
            new PlanConfigSpec("COMPARISON", "NORMAL", "order_info", List.of("销售额", "订单量"), List.of("区域", "渠道"), "bar", "近30天",
                    "SELECT region, channel, SUM(sales_amount) AS sales_amount, SUM(order_count) AS order_count "
                            + "FROM order_info GROUP BY region, channel"),
            new PlanConfigSpec("RANKING", "NORMAL", "product_info", List.of("销量", "销售额"), List.of("品类"), "bar", "近30天",
                    "SELECT category, SUM(sales_volume) AS sales_volume, SUM(sales_amount) AS sales_amount "
                            + "FROM product_info GROUP BY category ORDER BY SUM(sales_volume) DESC LIMIT 10"),
            new PlanConfigSpec("STRUCTURE", "NORMAL", "order_info", List.of("销售额"), List.of("品类"), "pie", "近30天",
                    "SELECT category, SUM(sales_amount) AS sales_amount FROM order_info GROUP BY category"),
            new PlanConfigSpec("RETENTION", "NORMAL", "user_info", List.of("留存率", "新增用户数"), List.of("日期"), "line", "近30天",
                    "SELECT register_date, AVG(retention_rate) AS retention_rate FROM user_info GROUP BY register_date ORDER BY register_date"),
            new PlanConfigSpec("ANOMALY", "NORMAL", "order_info", List.of("订单量", "销售额"), List.of("日期", "区域"), "table", "近30天",
                    "SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount "
                            + "FROM order_info GROUP BY order_date, region ORDER BY order_date DESC LIMIT 30"),
            new PlanConfigSpec("GENERAL", "NORMAL", "order_info", List.of("订单量", "销售额", "客单价"), List.of("日期", "区域"), "table", "近30天",
                    "SELECT order_date, region, SUM(order_count) AS order_count, SUM(sales_amount) AS sales_amount, "
                            + "ROUND(SUM(sales_amount) / NULLIF(SUM(order_count), 0), 2) AS avg_order_amount "
                            + "FROM order_info GROUP BY order_date, region ORDER BY order_date"),
            new PlanConfigSpec("SALES_TREND", "GOV", GOV_TABLE, List.of("发文量", "日均发文量"), List.of("发布日期"), "line", "近30天",
                    "SELECT DATE_FORMAT(publish_date,'%Y-%m') AS month, COUNT(*) AS doc_count "
                            + "FROM gov_info_record WHERE publish_date >= {timeRange} GROUP BY month ORDER BY month"),
            new PlanConfigSpec("RANKING", "GOV", GOV_TABLE, List.of("发文量"), List.of("公开单位"), "bar", "近30天",
                    "SELECT COALESCE(NULLIF(publish_unit,''), category) AS unit, COUNT(*) AS doc_count "
                            + "FROM gov_info_record GROUP BY unit ORDER BY doc_count DESC LIMIT 10"),
            new PlanConfigSpec("STRUCTURE", "GOV", GOV_TABLE, List.of("发文量"), List.of("公开类目"), "pie", "近30天",
                    "SELECT category, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category"),
            new PlanConfigSpec("USER_PROFILE", "GOV", GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("COMPARISON", "GOV", GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("RETENTION", "GOV", GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("ANOMALY", "GOV", GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"),
            new PlanConfigSpec("GENERAL", "GOV", GOV_TABLE, List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table", "近30天",
                    "SELECT category, publish_unit, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category, publish_unit"));
}