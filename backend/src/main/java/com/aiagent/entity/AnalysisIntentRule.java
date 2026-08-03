package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 分析意图规则配置：关键词命中即识别为该意图，按优先级先命中先胜出。 */
@Data
public class AnalysisIntentRule {
    private Long id;
    private String intentCode;
    private String intentName;
    private String keywords;
    private Integer priority;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}