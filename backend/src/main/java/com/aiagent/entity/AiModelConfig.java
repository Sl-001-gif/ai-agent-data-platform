package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 模型配置：按任务用途路由（text/sql/report）。apiKey 不在接口暴露，环境变量 AI_API_KEY 优先。 */
@Data
public class AiModelConfig {
    private Long id;
    private String name;
    private String modelName;
    private String apiKey;
    private String endpoint;
    private Integer maxTokens;
    private Double temperature;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}