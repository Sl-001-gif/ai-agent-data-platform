package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 分析报告（覆盖式：同一会话重复生成覆盖旧报告）。 */
@Data
public class AnalysisReport {
    private Long id;
    private Long sessionId;
    private String title;
    private String content;
    private String status;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}