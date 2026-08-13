package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 分析计划配置：意图(普通/政务)对应的目标表、指标、维度、图表、时间范围与规则 SQL 模板。 */
@Data
public class AnalysisPlanConfig {
    private Long id;
    private String intentCode;
    private Integer isGov;
    private String planType;
    private String tableName;
    private String metrics;
    private String dimensions;
    private String chartType;
    private String timeRange;
    private String sqlTemplate;
    private Integer status;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}