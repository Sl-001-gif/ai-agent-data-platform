package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** Prompt 模板配置：按任务类型（INTENT/SQL/CHART/INTERPRET/RECOMMEND）预置提示词基线。 */
@Data
public class PromptTemplate {
    private Long id;
    private String name;
    private String type;
    private String content;
    private Integer version;
    private Integer status;
    /** 变量名逗号分隔（datasetSchema/userQuestion/originSQL 等）。 */
    private String variables;
    /** 排序权重。 */
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}