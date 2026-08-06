package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 字段语义定义：字段名、类型、语义类型、业务含义与查询/聚合能力。 */
@Data
public class TableField {
    private Long id;
    private Long tableId;
    private String tableName;
    private String datasetName;
    private String fieldName;
    private String fieldType;
    private String fieldComment;
    private String businessMeaning;
    private Integer isMetric;
    private String semanticType;
    private Integer canQuery;
    private Integer canAgg;
    private Integer sort;
    private Long categoryId;
    private String categoryName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
