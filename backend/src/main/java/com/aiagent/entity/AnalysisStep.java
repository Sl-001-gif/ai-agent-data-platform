package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** Agent 执行步骤记录。 */
@Data
public class AnalysisStep {
    private Long id;
    private Long sessionId;
    private Integer stepOrder;
    private String stepType;
    private String inputData;
    private String outputData;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createTime;
}