package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 分析会话。 */
@Data
public class AnalysisSession {
    private Long id;
    private Long userId;
    private Long datasetId;
    private String title;
    private String analysisGoal;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
