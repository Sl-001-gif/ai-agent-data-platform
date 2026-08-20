package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** Agent 多步分析计划：宏观目标拆解为多步分析步骤，执行与报告结果一并落库。 */
@Data
public class AgentPlan {
    private Long id;
    private Long userId;
    private String title;
    private String goal;
    private Long datasetId;
    private Long modelConfigId;
    private String status;
    /** 步骤 JSON：[{stepNo,name,question,logic,chartType,status,durationMs,error,intentType,targetTable,sqlPurpose,sql,rowCount,columns,rows,interpretation}] */
    private String stepsJson;
    private String reportTitle;
    private String reportContent;
    private String reportGeneratorType;
    /** 报告图表数据 JSON 数组：[{chartType,title,columns,rows}] */
    private String reportChartsJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}