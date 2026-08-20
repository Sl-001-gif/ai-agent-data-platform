package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import com.aiagent.mapper.MetadataAdminMapper;
import com.aiagent.service.AnalysisConfigService;
import com.aiagent.util.TimeRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 依据意图生成结构化分析计划：配置来自分析配置中心（库空回退内置），并支持从用户问题提取时间范围。 */
@Component
public class AnalysisPlanner {

    private static final String DEFAULT_TIME_RANGE = "近30天";
    private static final String NORMAL_TYPE = "NORMAL";
    private static final String GOV_TYPE = "GOV";
    private static final List<String> STEPS =
            List.of("INTENT", "PLAN", "SQL", "VALIDATE", "EXECUTE", "CHART", "INTERPRET", "REPORT");

    private final DemoMetadataCatalog metadataCatalog;
    private final AnalysisConfigService configService;
    private final MetadataAdminMapper datasetMapper;

    /** 测试兜底：仅内置元数据 + 内置配置（不访问数据库）。 */
    public AnalysisPlanner(DemoMetadataCatalog metadataCatalog) {
        this(metadataCatalog, AnalysisConfigService.builtinOnly(), null);
    }

    /** 测试便捷构造：配置走库但不做数据集解析（datasetId 为 null 时不影响）。 */
    public AnalysisPlanner(DemoMetadataCatalog metadataCatalog, AnalysisConfigService configService) {
        this(metadataCatalog, configService, null);
    }

    @Autowired
    public AnalysisPlanner(DemoMetadataCatalog metadataCatalog, AnalysisConfigService configService,
                           MetadataAdminMapper datasetMapper) {
        this.metadataCatalog = metadataCatalog;
        this.configService = configService;
        this.datasetMapper = datasetMapper;
    }

    public AnalysisPlan buildPlan(RecognizedIntent intent) {
        return buildPlan(intent, null);
    }

    /** 构建计划；question 用于提取时间范围与类型关键词路由。 */
    public AnalysisPlan buildPlan(RecognizedIntent intent, String question) {
        return buildPlan(intent, question, null);
    }

    /** 构建计划；datasetId 非空时按数据集路由目标表与计划类型（如统计月报 → stat_monthly/STAT）。 */
    public AnalysisPlan buildPlan(RecognizedIntent intent, String question, Long datasetId) {
        String type = intent == null || intent.getIntentType() == null ? "GENERAL" : intent.getIntentType();
        String tableName = datasetTableName(datasetId);
        String typeCode = resolveTypeCode(intent, question, tableName);
        // 统计趋势归一：stat_monthly 表或 STAT 类型路由下的趋势/排名/对比类问题统一走统计趋势
        // （避免 SALES_TREND 回退 order_info；RANKING/COMPARISON/STAT_RANKING 走区县/快照细化而非 NORMAL 模板）
        if (("SALES_TREND".equalsIgnoreCase(type) || "STAT_TREND".equalsIgnoreCase(type)
                || "RANKING".equalsIgnoreCase(type) || "COMPARISON".equalsIgnoreCase(type)
                || "STAT_RANKING".equalsIgnoreCase(type))
                && ("STAT".equalsIgnoreCase(typeCode) || "stat_monthly".equalsIgnoreCase(tableName))) {
            type = "STAT_TREND";
        }
        AnalysisConfigService.PlanConfigSpec spec = configService.resolvePlanSpec(type, typeCode, tableName);
        if ("STRUCTURE".equalsIgnoreCase(type)) {
            spec = refineStructureSpec(spec, question, typeCode, tableName);
        } else if ("STAT_TREND".equalsIgnoreCase(type) && spec != null && "stat_monthly".equalsIgnoreCase(spec.tableName())) {
            spec = refineStatTrendSpec(spec, question);
        }
        DemoMetadataCatalog.DemoTable table = metadataCatalog.getTable(spec.tableName());
        String tableComment = table != null ? table.comment()
                : GOV_TYPE.equals(typeCode) ? "政府信息公开记录" : spec.tableName();
        String timeRange = TimeRangeParser.extract(question);
        if (timeRange == null) {
            timeRange = spec.timeRange() == null || spec.timeRange().isBlank() ? DEFAULT_TIME_RANGE : spec.timeRange();
        }
        AnalysisPlan plan = new AnalysisPlan(spec.tableName(), tableComment,
                spec.metrics(), spec.dimensions(), timeRange, spec.chartType(), STEPS);
        plan.setPlanType(typeCode);
        plan.setDatasetId(datasetId);
        return plan;
    }

    /**
     * 类型路由：命中政务关键词 → GOV（保留原行为）；否则按启用中自定义类型的路由关键词匹配；
     * 未命中任何类型 → NORMAL。
     */
    private String resolveTypeCode(RecognizedIntent intent, String question) {

        return resolveTypeCode(intent, question, null);
    }
    /** 类型路由：指定数据集时优先用该数据集配置的计划类型（如 stat_monthly → STAT）；否则命中政务关键词 → GOV；再按启用中自定义类型关键词。 */
    private String resolveTypeCode(RecognizedIntent intent, String question, String tableName) {
        if (tableName != null && !tableName.isBlank()) {
            String datasetType = datasetTypeCode(tableName);
            if (datasetType != null) {
                return datasetType;
            }
        }
        String text = question == null ? "" : question;
        boolean govRelated = intent != null && intent.getMatchedKeywords() != null
                && intent.getMatchedKeywords().contains("政务公开");
        // 先按启用中的业务类型关键词路由（如统计 → STAT），再回退政务标记，避免「经济/占比」等统计问题被「邵阳」政务标记抢走
        for (AnalysisConfigService.PlanTypeSpec type : configService.planTypes()) {
            if (NORMAL_TYPE.equals(type.code()) || GOV_TYPE.equals(type.code())) {
                continue;
            }
            if (matchesAny(text, type.keywords())) {
                return type.code();
            }
        }
        if (govRelated) {
            return GOV_TYPE;
        }
        return NORMAL_TYPE;
    }

    /** 数据集 → 目标表；数据集不存在返回 null（全库路由）。 */
    private String datasetTableName(Long datasetId) {
        if (datasetId == null) {
            return null;
        }
        try {
            com.aiagent.entity.Dataset dataset = datasetMapper.selectDatasetById(datasetId);
            return dataset != null ? dataset.getTableName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 数据集中该表配置的计划类型（从计划配置中按表名推断）；未配置返回 null。 */
    private String datasetTypeCode(String tableName) {
        for (AnalysisConfigService.PlanConfigSpec spec : configService.planConfigs()) {
            if (tableName.equalsIgnoreCase(spec.tableName()) && spec.typeCode() != null
                    && !spec.typeCode().isBlank() && !NORMAL_TYPE.equalsIgnoreCase(spec.typeCode())
                    && !GOV_TYPE.equalsIgnoreCase(spec.typeCode())) {
                return spec.typeCode().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** 结构整体指标：提问命中这些指标时视为结构分母，不参与扇区（如「一般公共预算收入中税收/非税占比」）。 */
    private static final List<String> STRUCTURE_TOTAL_METRICS = List.of(
            "一般公共预算收入", "预算收入", "地方一般公共预算收入",
            "各项存款", "各项贷款", "进出口", "固定资产投资", "社会消费品零售总额", "地区生产总值");

    /** STRUCTURE 计划细化：按问题维度关键词在同类配置中选择（地区/区县 vs 产业/行业），
     *  并对「占比+趋势/具体产业」做二次细化：趋势词 → 图表改 line、维度加期间；提及具体产业 → 指标只保留该产业；
     *  提问命中统计月报指标别名（税收/非税/存款等）→ 指标收敛到命中项，避免结构占比误落默认三大产业。 */
    private AnalysisConfigService.PlanConfigSpec refineStructureSpec(AnalysisConfigService.PlanConfigSpec spec,
                                                                     String question, String typeCode, String tableName) {
        if (spec == null || question == null || question.isBlank()) {
            return spec;
        }
        // 占比/结构特殊族：分行业增加值 / 功能分类支出 / 各行业投资 优先于默认三大产业
        List<String> detailMetrics = detailStructureMetrics(question);
        if (detailMetrics != null) {
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    detailMetrics, List.of("指标"), "bar", spec.timeRange(), spec.sqlTemplate());
        }
        boolean regionAsk = matchesAny(question, List.of("地区", "区县", "县市区", "区域", "各地", "分布"));
        boolean industryAsk = matchesAny(question, List.of("产业", "行业", "三次产业", "第一产业", "第二产业", "第三产业", "一产", "二产", "三产"));
        // 指标结构占比：非产业占比且命中统计月报指标别名（税收/非税/存款等）→ 收敛到命中项（剔除结构整体词），
        // 避免「一般公共预算收入中税收/非税占比」「各区县税收收入占比」这类问题误落默认三大产业
        List<String> indicatorHits = industryAsk ? List.of() : extractIndicators(question);
        if (!indicatorHits.isEmpty()) {
            boolean trendAsk = matchesAny(question, List.of("趋势", "走势", "变化", "演进", "变动", "逐年"));
            boolean snapshotCompare = matchesAny(question, List.of("对比", "排名", "比较", "高低", "分布", "谁高", "谁低", "最高", "最低"));
            List<String> metrics = new ArrayList<>(indicatorHits);
            metrics.removeAll(STRUCTURE_TOTAL_METRICS);
            if (metrics.isEmpty()) {
                metrics = indicatorHits;
            }
            List<String> dims;
            String chart;
            if (regionAsk) {
                // 区县维度占比：保留区县维度（SQL region 分支按收敛指标取数）
                dims = trendAsk ? List.of("期间", "区县") : List.of("区县");
                chart = trendAsk ? "line" : (snapshotCompare ? "bar" : spec.chartType());
            } else {
                dims = trendAsk ? List.of("期间", "指标") : List.of("指标");
                chart = trendAsk ? "line" : spec.chartType();
            }
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    metrics, dims, chart, spec.timeRange(), spec.sqlTemplate());
        }
        if (!regionAsk && !industryAsk) {
            return spec;
        }
        List<String> currentDims = spec.dimensions() == null ? List.of() : spec.dimensions();
        boolean currentRegion = currentDims.contains("区县");
        boolean currentIndustry = currentDims.contains("产业");
        if (!(regionAsk && currentRegion) && !(industryAsk && currentIndustry)) {
            for (AnalysisConfigService.PlanConfigSpec candidate : configService.planConfigs()) {
                if (!"STRUCTURE".equalsIgnoreCase(candidate.intentCode())) {
                    continue;
                }
                boolean sameTable = tableName != null && !tableName.isBlank()
                        && tableName.equalsIgnoreCase(candidate.tableName());
                boolean sameType = candidate.typeCode().equals(typeCode);
                if (!sameTable && !sameType) {
                    continue;
                }
                List<String> dims = candidate.dimensions() == null ? List.of() : candidate.dimensions();
                if (regionAsk && !currentRegion && dims.contains("区县")) {
                    spec = candidate;
                    break;
                }
                if (industryAsk && !currentIndustry && dims.contains("产业")) {
                    spec = candidate;
                    break;
                }
            }
        }
        return refineStructureTrend(spec, question);
    }

    /** 占比趋势二次细化：趋势词 → line + 期间维度；提到具体产业 → 指标收敛到该产业。 */
    private static AnalysisConfigService.PlanConfigSpec refineStructureTrend(AnalysisConfigService.PlanConfigSpec spec,
                                                                             String question) {
        boolean trendAsk = matchesAny(question, List.of("趋势", "走势", "变化", "演进", "变动", "逐年"));
        List<String> industries = extractIndustries(question);
        if (!trendAsk && industries.isEmpty()) {
            return spec;
        }
        List<String> metrics = industries.isEmpty() || spec.metrics() == null || spec.metrics().isEmpty()
                ? spec.metrics() : industries;
        List<String> dims = new ArrayList<>();
        if (trendAsk) {
            dims.add("期间");
            List<String> baseDims = spec.dimensions() == null ? List.of() : spec.dimensions();
            for (String d : baseDims) {
                if (!"期间".equals(d)) {
                    dims.add(d);
                }
            }
        } else {
            dims.addAll(spec.dimensions() == null ? List.<String>of() : spec.dimensions());
        }
        return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                metrics, dims, trendAsk ? "line" : spec.chartType(), spec.timeRange(), spec.sqlTemplate());
    }

    /** 区县/地区对比关键词：命中则趋势/快照按区县维度取数（SQL region 分支）。 */
    private static final List<String> REGION_KEYWORDS = List.of(
            "区县", "各县", "县市区", "分县", "县区", "各个县", "哪个县", "哪些县",
            "邵东", "双清", "大祥", "北塔", "隆回", "洞口", "新宁", "新邵", "武冈", "绥宁", "城步", "邵阳县");

    /** 快照对比关键词：命中则区县对比走最新一期柱状图（X 轴=区县），否则按期间趋势。 */
    private static final List<String> SNAPSHOT_KEYWORDS = List.of(
            "对比", "排名", "比较", "高低", "分布", "谁高", "谁低", "最高", "最低", "领先", "落后");

    /** 单期快照问法关键词：命中（且无阻止词）判定为「最新一期」快照，计划收敛为最新期间单指标。 */
    private static final List<String> SNAPSHOT_ASK_WORDS = List.of(
            "最新", "目前", "当前", "现在", "月末", "季末", "期末", "截至", "是多少", "多少了");

    /** 快照判定阻止词：命中任一则不按快照处理（趋势/结构/排名/明确累计期别/特定区域）。 */
    private static final List<String> SNAPSHOT_BLOCK_WORDS = List.of(
            "趋势", "走势", "变化", "演进", "变动", "逐年", "对比", "比较", "排名",
            "结构", "构成", "占比", "比例", "份额", "分别",
            "前三季度", "1-9月", "一季度", "1-3月", "上半年", "1-6月", "三季度", "二季度", "四季度",
            "1-4月", "1-5月", "1-7月", "1-8月", "1-10月", "1-11月", "1-12月", "年度", "全年", "累计期",
            "长株潭", "环长株潭", "湘南", "大湘西", "洞庭湖", "各市州");

    /** 单期快照判定：命中快照问法且未命中阻止词。 */
    private static boolean isSnapshotAsk(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return matchesAny(question, SNAPSHOT_ASK_WORDS) && !matchesAny(question, SNAPSHOT_BLOCK_WORDS);
    }


    /** 排名类问题 → 排名基数指标（无区县词也按区县快照柱状图；顺序即优先级，具体族先于泛化词）。 */
    private static final List<Map.Entry<String, String>> RANK_METRICS = List.of(
            Map.entry("一般公共预算收入排名", "一般公共预算收入"),
            Map.entry("预算收入排名", "一般公共预算收入"),
            Map.entry("财政收入排名", "一般公共预算收入"),
            Map.entry("农村居民人均可支配收入排名", "农村居民人均可支配收入"),
            Map.entry("城镇居民人均可支配收入排名", "城镇居民人均可支配收入"),
            Map.entry("全体居民人均可支配收入排名", "全体居民人均可支配收入"),
            Map.entry("可支配收入排名", "全体居民人均可支配收入"),
            Map.entry("收入排名", "全体居民人均可支配收入"),
            Map.entry("GDP排名", "地区生产总值"),
            Map.entry("生产总值排名", "地区生产总值"),
            Map.entry("经济总量排名", "地区生产总值"),
            Map.entry("总量排名", "地区生产总值"),
            Map.entry("规模工业增加值排名", "规模工业增加值"),
            Map.entry("工业增加值排名", "规模工业增加值"),
            Map.entry("工业投资排名", "工业投资"),
            Map.entry("产业投资排名", "产业投资"),
            Map.entry("房地产开发投资排名", "房地产开发投资"),
            Map.entry("房地产投资排名", "房地产开发投资"),
            Map.entry("高技术产业投资排名", "高技术产业投资"),
            Map.entry("基础设施建设投资排名", "基础设施建设投资"),
            Map.entry("基础设施投资排名", "基础设施建设投资"),
            Map.entry("固定资产投资排名", "固定资产投资"),
            Map.entry("固投排名", "固定资产投资"),
            Map.entry("投资排名", "固定资产投资"),
            Map.entry("社会消费品零售总额排名", "社会消费品零售总额"),
            Map.entry("零售总额排名", "社会消费品零售总额"),
            Map.entry("零售额排名", "社会消费品零售总额"),
            Map.entry("社零排名", "社会消费品零售总额"),
            Map.entry("进出口排名", "进出口"),
            Map.entry("出口排名", "出口"),
            Map.entry("外商直接投资排名", "外商直接投资"),
            Map.entry("外资排名", "外商直接投资"),
            Map.entry("用电排名", "全市用电总量(亿千瓦小时)"));

    /** 大类关键词（未命中具体指标时兜底）→ 族内指标集：外贸外资/金融运行/交通运输/财政收支/居民收入。 */
    private static final List<Map.Entry<List<String>, List<String>>> CATEGORY_METRICS = List.of(
            Map.entry(List.of("外贸", "外资"), List.of("进出口", "出口", "进口", "外商直接投资")),
            Map.entry(List.of("金融运行", "存贷款"), List.of("各项存款", "各项贷款")),
            Map.entry(List.of("用电"), List.of("全市用电总量(亿千瓦小时)", "工业用电量")),
            Map.entry(List.of("客运"), List.of("客运量(万人)", "旅客周转量(万人公里)")),
            Map.entry(List.of("货运"), List.of("公路(万吨)", "公路(万吨公里)")),
            Map.entry(List.of("财政", "预算收支"), List.of("一般公共预算收入", "一般公共预算支出")),
            Map.entry(List.of("可支配收入", "居民收入"),
                    List.of("全体居民人均可支配收入", "城镇居民人均可支配收入", "农村居民人均可支配收入")));

        /** 分行业增加值（经济核算按行业分，2025年1-9月起入库）占比/结构类问题指标集。 */
    private static final List<String> INDUSTRY_VALUE_ADDED_NAMES = List.of(
            "农林牧渔业增加值", "工业增加值", "建筑业增加值", "批发和零售业增加值", "交通运输仓储邮政业增加值",
            "住宿和餐饮业增加值", "金融业增加值", "房地产业增加值", "其他服务业增加值",
            "信息传输软件和信息技术服务业增加值", "租赁和商务服务业增加值");

    /** 一般公共预算支出功能分类指标集（财政月报，2025年1-9月）。 */
    private static final List<String> FUNCTIONAL_EXPENDITURE_NAMES = List.of(
            "一般公共服务", "公共安全", "教育", "科学技术", "文化旅游体育与传媒", "社会保障和就业",
            "卫生健康", "节能环保", "城乡社区事务", "农林水事务", "交通运输", "住房保障");

    /** 各行业固定资产投资增速指标集（投资月报按行业分）。 */
    private static final List<String> INDUSTRY_INVEST_NAMES = List.of(
            "农、林、牧、渔业投资", "采矿业投资", "制造业投资", "电力、热力、燃气及水的生产和供应业投资",
            "建筑业投资", "批发和零售业投资", "交通运输、仓储和邮政业投资", "住宿和餐饮业投资",
            "信息传输、软件和信息技术服务业投资", "金融业投资", "房地产业投资", "租赁和商务服务业投资",
            "科学研究和技术服务业投资", "水利、环境和公共设施管理业投资", "居民服务、修理和其他服务业投资",
            "教育投资", "卫生和社会工作投资", "文化、体育和娱乐业投资", "公共管理、社会保障和社会组织投资");

/** 排名类问题收敛：命中 RANK_METRICS 返回排名基数指标，未命中返回 null。 */
    private static String rankMetricFor(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        // 最长命中优先：避免「外商直接投资排名」被泛化词「投资排名」抢先匹配成 固定资产投资
        String bestKey = null;
        String bestValue = null;
        for (Map.Entry<String, String> entry : RANK_METRICS) {
            if (question.contains(entry.getKey()) && (bestKey == null || entry.getKey().length() > bestKey.length())) {
                bestKey = entry.getKey();
                bestValue = entry.getValue();
            }
        }
        return bestValue;
    }

    /** 大类关键词兜底：命中返回族内指标集，未命中返回空列表（具体指标提取优先）。 */
    private static List<String> categoryMetricsFor(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        for (Map.Entry<List<String>, List<String>> entry : CATEGORY_METRICS) {
            if (matchesAny(question, entry.getKey())) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    /** STAT_TREND + stat_monthly：趋势细化——指标收敛到问题提到的产业/指标（未提及默认三大产业），维度固定期间+产业/指标、图表 line；提问含区县词时按区县维度取数（对比类走最新一期柱状图）。 */
    private static AnalysisConfigService.PlanConfigSpec refineStatTrendSpec(AnalysisConfigService.PlanConfigSpec spec,
                                                                            String question) {
        // 各行业投资：行业投资族（增速排名/对比）优先于工业大类行业增加值族
        List<String> detailMetrics = detailStructureMetrics(question);
        if (detailMetrics != null && question != null && question.contains("投资")) {
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    INDUSTRY_INVEST_NAMES, List.of("期间", "行业"), "line", spec.timeRange(), spec.sqlTemplate());
        }
        // 存贷款：同时覆盖 各项存款 + 各项贷款（避免「存贷款」只命中「贷款」子串）
        if (question != null && question.contains("存贷款")) {
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    List.of("各项存款", "各项贷款"), List.of("期间", "指标"), "line", spec.timeRange(), spec.sqlTemplate());
        }
        // 规模工业大类行业模块：命中行业族关键词时指标收敛到全部行业（增速存 growth_rate）
        if (matchesAny(question, List.of("规模工业大类行业", "行业增加值", "各行业", "行业增速", "哪些行业"))) {
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    INDUSTRY_SECTOR_NAMES, List.of("期间", "行业"), "line", spec.timeRange(), spec.sqlTemplate());
        }
        List<String> structureMetrics = structureMetricsFor(question);
        if (structureMetrics != null) {
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    structureMetrics, List.of("期间", "指标"), "line", spec.timeRange(), spec.sqlTemplate());
        }
        // 排名类问题（无需区县词也按区县快照柱状图）：指标收敛到排名基数指标
        String rankMetric = rankMetricFor(question);
        if (rankMetric != null) {
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    List.of(rankMetric), List.of("区县"), "bar", spec.timeRange(), spec.sqlTemplate());
        }
        boolean regionAsk = matchesAny(question, REGION_KEYWORDS);
        List<String> industries = extractIndustries(question);
        List<String> indicators = industries.isEmpty() ? extractIndicators(question) : List.of();
        // 大类关键词（外贸/金融/交通/财政/居民收入）兜底收敛到族内指标，避免未命中具体指标时误落三大产业
        List<String> categoryMetrics = indicators.isEmpty() ? categoryMetricsFor(question) : List.of();
        // 区县对比未明确指标时默认收敛地区生产总值（region SQL 分支只支持单指标）
        List<String> metrics = !industries.isEmpty() ? industries
                : (!indicators.isEmpty() ? indicators
                        : (!categoryMetrics.isEmpty() ? categoryMetrics
                                : (regionAsk ? List.of("地区生产总值") : List.of("第一产业", "第二产业", "第三产业"))));
        // 单期快照：问题为「最新/当前/月末/是多少」等快照问法且无趋势/结构/明确累计期别/区域词 →
        // 收敛为最新期间单指标快照（bar），SQL 由规则引擎按 MAX(period) 动态取最新期别，规避 LLM 臆造/写错期别
        if (isSnapshotAsk(question)) {
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    metrics, List.of("指标"), "bar", "最新期间", spec.sqlTemplate());
        }
        if (regionAsk) {
            boolean snapshot = matchesAny(question, SNAPSHOT_KEYWORDS);
            return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                    metrics, snapshot ? List.of("区县") : List.of("期间", "区县"),
                    snapshot ? "bar" : "line", spec.timeRange(), spec.sqlTemplate());
        }
        String dimName = !industries.isEmpty() ? "产业" : "指标";
        return new AnalysisConfigService.PlanConfigSpec(spec.intentCode(), spec.typeCode(), spec.tableName(),
                metrics, List.of("期间", dimName), "line", spec.timeRange(), spec.sqlTemplate());
    }

    /** 统计月报常用指标别名表：提问关键词 → stat_monthly.indicator_name（SQL 按 indicator_name 精确匹配）。 */
    private static final List<Map.Entry<String, String>> INDICATOR_ALIASES = List.of(
            Map.entry("外商直接投资", "外商直接投资"),
            Map.entry("实际利用外资", "外商直接投资"),
            Map.entry("实际利用境外资金", "外商直接投资"),
            Map.entry("进出口", "进出口"),
            Map.entry("万美元", "进出口（万美元）"),
            Map.entry("出口交货值", "出口交货值"),
            Map.entry("出口", "出口"),
            Map.entry("进口", "进口"),
            Map.entry("社会消费品零售总额", "社会消费品零售总额"),
            Map.entry("固定资产投资", "固定资产投资"),
            Map.entry("规模工业增加值", "规模工业增加值"),
            Map.entry("规模工业利润总额", "规模工业利润总额"),
            Map.entry("利润总额", "规模工业利润总额"),
            Map.entry("规模工业营业收入", "规模工业营业收入"),
            Map.entry("工业产品销售产值", "工业产品销售产值(现价)"),
            Map.entry("亏损面", "亏损面"),
            Map.entry("亏损企业亏损额", "亏损企业亏损额"),
            Map.entry("从业人员平均人数", "从业人员平均人数(人)"),
            Map.entry("从业人数", "从业人员平均人数(人)"),
            Map.entry("工业用电量", "工业用电量"),
            Map.entry("工业投资", "工业投资"),
            Map.entry("产业投资", "产业投资"),
            Map.entry("高技术产业投资", "高技术产业投资"),
            Map.entry("房地产开发投资", "房地产开发投资"),
            Map.entry("基础设施建设投资", "基础设施建设投资"),
            Map.entry("民间投资", "民间投资"),
            Map.entry("施工项目个数", "施工项目个数"),
            Map.entry("金融业", "金融业"),
            Map.entry("农业总产值", "农业总产值(现价)"),
            Map.entry("信息传输", "信息传输、软件和信息技术服务业"),
            Map.entry("其他服务业", "其他服务业"),
            Map.entry("各项存款", "各项存款"),
            Map.entry("各项贷款", "各项贷款"),
            Map.entry("存款余额", "各项存款"),
            Map.entry("贷款余额", "各项贷款"),
            Map.entry("住户存款", "住户存款"),
            Map.entry("住户贷款", "住户贷款"),
            Map.entry("活期", "活期存款"),
            Map.entry("定期", "定期及其他存款"),
            Map.entry("中长期消费贷款", "中长期消费贷款"),
            Map.entry("短期消费贷款", "短期消费贷款"),
            Map.entry("票据融资", "票据融资"),
            Map.entry("金融机构本外币存款", "各项存款"),
            Map.entry("金融机构本外币贷款", "各项贷款"),
            Map.entry("地方一般公共预算收入", "一般公共预算收入"),
            Map.entry("一般公共预算支出", "一般公共预算支出"),
            Map.entry("财政收入", "一般公共预算收入"),
            Map.entry("财政支出", "一般公共预算支出"),
            Map.entry("预算收入", "一般公共预算收入"),
            Map.entry("地区生产总值", "地区生产总值"),
            Map.entry("生产总值", "地区生产总值"),
            Map.entry("gdp", "地区生产总值"),
            Map.entry("地区生产总值(GDP)", "地区生产总值"),
            Map.entry("税收收入", "税收收入"),
            Map.entry("税收", "税收收入"),
            Map.entry("非税收入", "非税收入"),
            Map.entry("增值税", "增值税"),
            Map.entry("个人所得税", "个人所得税"),
            Map.entry("个税", "个人所得税"),
            Map.entry("契税", "契税"),
            Map.entry("农林水", "农林水事务"),
            Map.entry("全体居民人均可支配收入", "全体居民人均可支配收入"),
            Map.entry("城镇居民人均可支配收入", "城镇居民人均可支配收入"),
            Map.entry("农村居民人均可支配收入", "农村居民人均可支配收入"),
            Map.entry("工资", "工资性收入"),
            Map.entry("经营净收入", "经营净收入"),
            Map.entry("财产净收入", "财产净收入"),
            Map.entry("转移净收入", "转移净收入"),
            Map.entry("消费支出", "全市居民人均消费支出"),
            Map.entry("商品房销售面积", "商品房销售面积"),
            Map.entry("住宅销售面积", "住宅销售面积"),
            Map.entry("住宅销售额", "住宅销售额"),
            Map.entry("商品房施工面积", "商品房施工面积"),
            Map.entry("商品房竣工面积", "商品房竣工面积"),
            Map.entry("商品房屋销售额", "商品房屋销售额"),
            Map.entry("限额以上法人", "限额以上法人单位零售额"),
            Map.entry("限额以上", "限额以上零售额"),
            Map.entry("汽车类", "汽车类零售额"),
            Map.entry("石油制品", "石油制品类零售额"),
            Map.entry("粮油", "粮油、食品类零售额"),
            Map.entry("居民消费价格指数", "居民消费价格指数"),
            Map.entry("用电总量", "全市用电总量(亿千瓦小时)"),
            Map.entry("公路客运量", "公路(万人)"),
            Map.entry("水运客运量", "水运(万人)"),
            Map.entry("公路旅客周转量", "公路(万人公里)"),
            Map.entry("水运旅客周转量", "水运(万人公里)"),
            Map.entry("客运量", "客运量(万人)"),
            Map.entry("全社会客运量", "客运量(万人)"),
            Map.entry("旅客周转量", "旅客周转量(万人公里)"),
            Map.entry("客运周转量", "旅客周转量(万人公里)"),
            Map.entry("货运量", "公路(万吨)"),
            Map.entry("全社会货运量", "公路(万吨)"),
            Map.entry("货物周转量", "公路(万吨公里)"),
            Map.entry("货运周转量", "公路(万吨公里)"),
            Map.entry("煤炭开采和洗选业", "煤炭开采和洗选业"),
            Map.entry("黑色金属矿采选业", "黑色金属矿采选业"),
            Map.entry("有色金属矿采选业", "有色金属矿采选业"),
            Map.entry("非金属矿采选业", "非金属矿采选业"),
            Map.entry("农副食品加工业", "农副食品加工业"),
            Map.entry("食品制造业", "食品制造业"),
            Map.entry("酒、饮料和精制茶制造业", "酒、饮料和精制茶制造业"),
            Map.entry("纺织业", "纺织业"),
            Map.entry("纺织服装、服饰业", "纺织服装、服饰业"),
            Map.entry("皮革、毛皮、羽毛及其制品和制鞋业", "皮革、毛皮、羽毛及其制品和制鞋业"),
            Map.entry("木材加工和木、竹、藤、棕、草制品业", "木材加工和木、竹、藤、棕、草制品业"),
            Map.entry("家具制造业", "家具制造业"),
            Map.entry("造纸和纸制品业", "造纸和纸制品业"),
            Map.entry("印刷和记录媒介复制业", "印刷和记录媒介复制业"),
            Map.entry("文教、工美、体育和娱乐用品制造业", "文教、工美、体育和娱乐用品制造业"),
            Map.entry("石油、煤炭及其他燃料加工业", "石油、煤炭及其他燃料加工业"),
            Map.entry("化学原料和化学制品制造业", "化学原料和化学制品制造业"),
            Map.entry("医药制造业", "医药制造业"),
            Map.entry("化学纤维制造业", "化学纤维制造业"),
            Map.entry("橡胶和塑料制品业", "橡胶和塑料制品业"),
            Map.entry("非金属矿物制品业", "非金属矿物制品业"),
            Map.entry("黑色金属冶炼和压延加工业", "黑色金属冶炼和压延加工业"),
            Map.entry("有色金属冶炼和压延加工业", "有色金属冶炼和压延加工业"),
            Map.entry("金属制品业", "金属制品业"),
            Map.entry("通用设备制造业", "通用设备制造业"),
            Map.entry("专用设备制造业", "专用设备制造业"),
            Map.entry("汽车制造业", "汽车制造业"),
            Map.entry("电气机械和器材制造业", "电气机械和器材制造业"),
            Map.entry("计算机、通信和其他电子设备制造业", "计算机、通信和其他电子设备制造业"),
            Map.entry("仪器仪表制造业", "仪器仪表制造业"),
            Map.entry("其他制造业", "其他制造业"),
            Map.entry("废弃资源综合利用业", "废弃资源综合利用业"),
            Map.entry("电力、热力生产和供应业", "电力、热力生产和供应业"),
            Map.entry("燃气生产和供应业", "燃气生产和供应业"),
            Map.entry("水的生产和供应业", "水的生产和供应业"));

    /** 规模工业大类行业增加值：35 个行业（工业8 sheet），增速存 growth_rate、绝对额存 value。 */
    private static final List<String> INDUSTRY_SECTOR_NAMES = List.of(
            "煤炭开采和洗选业",
            "黑色金属矿采选业",
            "有色金属矿采选业",
            "非金属矿采选业",
            "农副食品加工业",
            "食品制造业",
            "酒、饮料和精制茶制造业",
            "纺织业",
            "纺织服装、服饰业",
            "皮革、毛皮、羽毛及其制品和制鞋业",
            "木材加工和木、竹、藤、棕、草制品业",
            "家具制造业",
            "造纸和纸制品业",
            "印刷和记录媒介复制业",
            "文教、工美、体育和娱乐用品制造业",
            "石油、煤炭及其他燃料加工业",
            "化学原料和化学制品制造业",
            "医药制造业",
            "化学纤维制造业",
            "橡胶和塑料制品业",
            "非金属矿物制品业",
            "黑色金属冶炼和压延加工业",
            "有色金属冶炼和压延加工业",
            "金属制品业",
            "通用设备制造业",
            "专用设备制造业",
            "汽车制造业",
            "电气机械和器材制造业",
            "计算机、通信和其他电子设备制造业",
            "仪器仪表制造业",
            "其他制造业",
            "废弃资源综合利用业",
            "电力、热力生产和供应业",
            "燃气生产和供应业",
            "水的生产和供应业"
    );

    /** 结构类多指标问题：问题关键词 → 多指标列表（零售额构成/批零住餐/固投构成/存贷款结构/收入构成）。
     *  返回 null 表示无结构类命中，走常规单指标别名提取。 */
    private static List<String> structureMetricsFor(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        if (matchesAny(question, List.of("城镇", "城区", "乡村")) && question.contains("零售额")) {
            return List.of("城镇零售额", "城区零售额", "乡村零售额");
        }
        if (question.contains("零售额") && matchesAny(question, List.of("批发", "住宿餐饮", "住餐"))) {
            return List.of("批发和零售业零售额", "住宿和餐饮业零售额");
        }
        if (matchesAny(question, List.of("三次产业", "一产", "二产", "三产")) && question.contains("投资")) {
            return List.of("第一产业投资", "第二产业投资", "第三产业投资");
        }
        if (matchesAny(question, List.of("建筑安装", "设备工器具", "其他费用"))) {
            return List.of("建筑安装工程", "设备工器具购置", "其他费用");
        }
        if (question.contains("存款结构") || (question.contains("存款") && question.contains("结构"))) {
            return List.of("住户存款", "非金融企业存款", "广义政府存款", "非银行业金融机构存款");
        }
        if (question.contains("贷款结构") || (question.contains("贷款") && question.contains("结构"))) {
            return List.of("住户贷款", "非金融企业及机关团体贷款", "非银行业金融机构贷款");
        }
        if (question.contains("收入构成") || question.contains("收入结构")) {
            return List.of("工资性收入", "经营净收入", "财产净收入", "转移净收入");
        }
        return null;
    }

    /** 占比/结构类特殊族：分行业增加值 / 功能分类支出 / 各行业投资；未命中返回 null。 */
    private static List<String> detailStructureMetrics(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        if ((question.contains("分行业") || question.contains("按行业分")) && question.contains("增加值")) {
            return INDUSTRY_VALUE_ADDED_NAMES;
        }
        if (question.contains("功能分类") && (question.contains("支出") || question.contains("占比"))) {
            return FUNCTIONAL_EXPENDITURE_NAMES;
        }
        if (matchesAny(question, List.of("各行业", "哪些行业", "行业投资")) && question.contains("投资")) {
            return INDUSTRY_INVEST_NAMES;
        }
        return null;
    }

    /** 「出口」独立匹配：排除「进出口」中的「出口」子串（前一个字符为「进」时不视为独立指标）。 */
    private static final Pattern EXIT_KEYWORD = Pattern.compile("(?<!进)出口(?!交货值)");

    /** 从问题中提取命中的统计月报指标名（同值去重；「出口」用正则避免被「进出口」误吞）；未命中返回空列表。 */
    private static List<String> extractIndicators(String question) {
        List<String> result = new ArrayList<>();
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> alias : INDICATOR_ALIASES) {
            String key = alias.getKey();
            boolean hit = "出口".equals(key) ? EXIT_KEYWORD.matcher(normalized).find() : normalized.contains(key);
            if (hit) {
                String value = alias.getValue();
                if (!result.contains(value)) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    /** 从问题中提取提及的产业（含一产/二产/三产简称），未提任何产业返回空列表。 */
    private static List<String> extractIndustries(String question) {
        List<String> result = new ArrayList<>();
        // 「三次产业」整体表述 = 一、二、三产全部（避免「三产」子串误命中只收敛第三产业）
        if (question.contains("三次产业") || question.contains("三大产业") || question.contains("产业结构")) {
            result.add("第一产业");
            result.add("第二产业");
            result.add("第三产业");
            return result;
        }
        if (question.contains("一产")) {
            result.add("第一产业");
        }
        if (question.contains("二产")) {
            result.add("第二产业");
        }
        if (question.contains("三产")) {
            result.add("第三产业");
        }
        return result;
    }

    private static boolean matchesAny(String text, List<String> keywords) {
        if (text.isEmpty() || keywords == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
