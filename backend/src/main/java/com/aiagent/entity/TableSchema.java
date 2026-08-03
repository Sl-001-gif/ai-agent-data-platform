package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 数据表结构定义：归属于某个数据集。 */
@Data
public class TableSchema {
    private Long id;
    private Long datasetId;
    private String datasetName;
    private String tableName;
    private String tableComment;
    private String relationDesc;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
