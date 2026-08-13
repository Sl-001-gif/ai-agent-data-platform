package com.aiagent.ai.planner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 结构化分析计划。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisPlan {
    /** 计划类型编码（analysis_plan_type.type_code，如 NORMAL/GOV/自定义）。 */
    private String planType;
    /** 目标表名。 */
    private String targetTable;
    /** 目标表说明。 */
    private String tableComment;
    /** 待计算指标。 */
    private List<String> metrics;
    /** 分析维度。 */
    private List<String> dimensions;
    /** 时间范围，默认近30天。 */
    private String timeRange;
    /** 推荐图表类型。 */
    private String chartType;
    /** 执行步骤序列。 */
    private List<String> steps;

    /** 兼容构造：未指定类型（默认按目标表推导，用于测试与旧调用方）。 */
    public AnalysisPlan(String targetTable, String tableComment, List<String> metrics,
                        List<String> dimensions, String timeRange, String chartType, List<String> steps) {
        this(null, targetTable, tableComment, metrics, dimensions, timeRange, chartType, steps);
    }
}