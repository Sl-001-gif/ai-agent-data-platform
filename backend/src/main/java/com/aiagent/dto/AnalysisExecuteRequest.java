package com.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 分析执行请求：分析目标 + 可选会话 ID。 */
@Data
public class AnalysisExecuteRequest {

    @NotBlank(message = "分析目标不能为空")
    private String text;

    /** 会话 ID，为空时新建会话。 */
    private Long sessionId;
}