package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 依据意图生成结构化分析计划，当前引用内置演示元数据。 */
@Component
public class AnalysisPlanner {

    private static final String DEFAULT_TIME_RANGE = "近30天";
    private static final List<String> STEPS =
            List.of("INTENT", "PLAN", "SQL", "VALIDATE", "EXECUTE", "CHART", "INTERPRET", "REPORT");

    private static final Map<String, PlanSpec> SPECS = Map.of(
            "SALES_TREND", new PlanSpec("order_info", List.of("订单量", "销售额"), List.of("日期"), "line"),
            "USER_PROFILE", new PlanSpec("user_info", List.of("新增用户数", "活跃用户数"), List.of("年龄段", "城市"), "bar"),
            "COMPARISON", new PlanSpec("order_info", List.of("销售额", "订单量"), List.of("区域", "渠道"), "bar"),
            "RANKING", new PlanSpec("product_info", List.of("销量", "销售额"), List.of("品类"), "bar"),
            "STRUCTURE", new PlanSpec("order_info", List.of("销售额"), List.of("品类"), "pie"),
            "RETENTION", new PlanSpec("user_info", List.of("留存率", "新增用户数"), List.of("日期"), "line"),
            "ANOMALY", new PlanSpec("order_info", List.of("订单量", "销售额"), List.of("日期", "区域"), "table"),
            "GENERAL", new PlanSpec("order_info", List.of("订单量", "销售额", "客单价"), List.of("日期", "区域"), "table")
    );

    /** 政务类意图 → 计划要素映射（目标表 gov_info_record）。 */
    private static final Map<String, PlanSpec> GOV_SPECS = Map.of(
            "SALES_TREND", new PlanSpec("GOV_INFO_RECORD", List.of("发文量", "日均发文量"), List.of("发布日期"), "line"),
            "RANKING", new PlanSpec("GOV_INFO_RECORD", List.of("发文量"), List.of("公开单位"), "bar"),
            "STRUCTURE", new PlanSpec("GOV_INFO_RECORD", List.of("发文量"), List.of("公开类目"), "pie"),
            "GENERAL", new PlanSpec("GOV_INFO_RECORD", List.of("发文量", "类目占比"), List.of("公开类目", "公开单位"), "table"));
    private final DemoMetadataCatalog metadataCatalog;

    public AnalysisPlanner(DemoMetadataCatalog metadataCatalog) {
        this.metadataCatalog = metadataCatalog;
    }

    public AnalysisPlan buildPlan(RecognizedIntent intent) {
        String type = intent == null || intent.getIntentType() == null ? "GENERAL" : intent.getIntentType();
        boolean govRelated = intent != null && intent.getMatchedKeywords() != null
                && intent.getMatchedKeywords().contains("政务公开");
        Map<String, PlanSpec> specs = govRelated ? GOV_SPECS : SPECS;
        PlanSpec spec = specs.getOrDefault(type, specs.get("GENERAL"));
        DemoMetadataCatalog.DemoTable table = metadataCatalog.getTable(spec.tableName());
        String tableComment = govRelated ? "政府信息公开记录" : (table == null ? spec.tableName() : table.comment());
        return new AnalysisPlan(spec.tableName(), tableComment,
                spec.metrics(), spec.dimensions(), DEFAULT_TIME_RANGE, spec.chartType(), STEPS);
    }

    /** 意图 → 计划要素映射。 */
    private record PlanSpec(String tableName, List<String> metrics, List<String> dimensions, String chartType) {
    }
}