package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import com.aiagent.service.AnalysisConfigService;
import com.aiagent.util.TimeRangeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** 依据意图生成结构化分析计划：配置来自分析配置中心（库空回退内置），并支持从用户问题提取时间范围。 */
@Component
public class AnalysisPlanner {

    private static final String DEFAULT_TIME_RANGE = "近30天";
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

    /** 构建计划；question 用于提取时间范围（如「近3年按月」→ timeRange=近3年）。 */
    public AnalysisPlan buildPlan(RecognizedIntent intent, String question) {
        String type = intent == null || intent.getIntentType() == null ? "GENERAL" : intent.getIntentType();
        boolean govRelated = intent != null && intent.getMatchedKeywords() != null
                && intent.getMatchedKeywords().contains("政务公开");
        AnalysisConfigService.PlanConfigSpec spec = resolveSpec(type, govRelated);
        DemoMetadataCatalog.DemoTable table = metadataCatalog.getTable(spec.tableName());
        String tableComment = govRelated ? "政府信息公开记录" : (table == null ? spec.tableName() : table.comment());
        String timeRange = TimeRangeParser.extract(question);
        if (timeRange == null) {
            timeRange = spec.timeRange() == null || spec.timeRange().isBlank() ? DEFAULT_TIME_RANGE : spec.timeRange();
        }
        return new AnalysisPlan(spec.tableName(), tableComment,
                spec.metrics(), spec.dimensions(), timeRange, spec.chartType(), STEPS);
    }

    /** 按意图（普通/政务）查计划配置，未命中回退该分组的 GENERAL。 */
    private AnalysisConfigService.PlanConfigSpec resolveSpec(String type, boolean govRelated) {
        List<AnalysisConfigService.PlanConfigSpec> specs = configService.planConfigs();
        AnalysisConfigService.PlanConfigSpec matched = null;
        AnalysisConfigService.PlanConfigSpec general = null;
        for (AnalysisConfigService.PlanConfigSpec spec : specs) {
            if (spec.gov() != govRelated) {
                continue;
            }
            if ("GENERAL".equals(spec.intentCode())) {
                general = spec;
            }
            if (spec.intentCode().equals(type)) {
                matched = spec;
            }
        }
        return matched != null ? matched : general;
    }
}