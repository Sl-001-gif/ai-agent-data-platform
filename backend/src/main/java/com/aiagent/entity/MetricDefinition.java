package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 指标口径定义：指标名称、编码、类型、口径公式与 SQL 表达式。 */
@Data
public class MetricDefinition {
    private Long id;
    private String name;
    private String description;
    private Long datasetId;
    private String datasetName;
    private String metricCode;
    private String metricType;
    private String calculationFormula;
    private String sqlExpression;
    private Integer sort;
    private Long tableId;
    private Long fieldId;
    private Integer status;
    private Long categoryId;
    private String categoryName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
