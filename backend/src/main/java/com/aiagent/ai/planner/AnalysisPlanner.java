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

    private final DemoMetadataCatalog metadataCatalog;

    public AnalysisPlanner(DemoMetadataCatalog metadataCatalog) {
        this.metadataCatalog = metadataCatalog;
    }

    public AnalysisPlan buildPlan(RecognizedIntent intent) {
        String type = intent == null || intent.getIntentType() == null ? "GENERAL" : intent.getIntentType();
        PlanSpec spec = SPECS.getOrDefault(type, SPECS.get("GENERAL"));
        DemoMetadataCatalog.DemoTable table = metadataCatalog.getTable(spec.tableName());
        String tableComment = table == null ? spec.tableName() : table.comment();
        return new AnalysisPlan(spec.tableName(), tableComment,
                spec.metrics(), spec.dimensions(), DEFAULT_TIME_RANGE, spec.chartType(), STEPS);
    }

    /** 意图 → 计划要素映射。 */
    private record PlanSpec(String tableName, List<String> metrics, List<String> dimensions, String chartType) {
    }
}