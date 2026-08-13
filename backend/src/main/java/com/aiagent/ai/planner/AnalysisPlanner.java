package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import com.aiagent.service.AnalysisConfigService;
import com.aiagent.util.TimeRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

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

    /** 测试兜底：仅内置元数据 + 内置配置（不访问数据库）。 */
    public AnalysisPlanner(DemoMetadataCatalog metadataCatalog) {
        this(metadataCatalog, AnalysisConfigService.builtinOnly());
    }

    @Autowired
    public AnalysisPlanner(DemoMetadataCatalog metadataCatalog, AnalysisConfigService configService) {
        this.metadataCatalog = metadataCatalog;
        this.configService = configService;
    }

    public AnalysisPlan buildPlan(RecognizedIntent intent) {
        return buildPlan(intent, null);
    }

    /** 构建计划；question 用于提取时间范围与类型关键词路由。 */
    public AnalysisPlan buildPlan(RecognizedIntent intent, String question) {
        String type = intent == null || intent.getIntentType() == null ? "GENERAL" : intent.getIntentType();
        String typeCode = resolveTypeCode(intent, question);
        AnalysisConfigService.PlanConfigSpec spec = configService.resolvePlanSpec(type, typeCode);
        DemoMetadataCatalog.DemoTable table = metadataCatalog.getTable(spec.tableName());
        String tableComment = table != null ? table.comment()
                : GOV_TYPE.equals(typeCode) ? "政府信息公开记录" : spec.tableName();
        String timeRange = TimeRangeParser.extract(question);
        if (timeRange == null) {
            timeRange = spec.timeRange() == null || spec.timeRange().isBlank() ? DEFAULT_TIME_RANGE : spec.timeRange();
        }
        return new AnalysisPlan(typeCode, spec.tableName(), tableComment,
                spec.metrics(), spec.dimensions(), timeRange, spec.chartType(), STEPS);
    }

    /**
     * 类型路由：命中政务关键词 → GOV（保留原行为）；否则按启用中自定义类型的路由关键词匹配；
     * 未命中任何类型 → NORMAL。
     */
    private String resolveTypeCode(RecognizedIntent intent, String question) {
        boolean govRelated = intent != null && intent.getMatchedKeywords() != null
                && intent.getMatchedKeywords().contains("政务公开");
        if (govRelated) {
            return GOV_TYPE;
        }
        String text = question == null ? "" : question;
        for (AnalysisConfigService.PlanTypeSpec type : configService.planTypes()) {
            if (NORMAL_TYPE.equals(type.code()) || GOV_TYPE.equals(type.code())) {
                continue;
            }
            if (matchesAny(text, type.keywords())) {
                return type.code();
            }
        }
        return NORMAL_TYPE;
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