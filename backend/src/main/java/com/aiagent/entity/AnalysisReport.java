package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** 分析报告（覆盖式：同一会话重复生成覆盖旧报告）。 */
@Data
public class AnalysisReport {
    private Long id;
    private Long sessionId;
    private Integer roundNo;
    private String title;
    private String content;
    private String status;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 报告图表数据（非持久化，详情接口按会话轮次步骤重建）：chartType/title/columns/rows。 */
    private Map<String, Object> chart;
}
