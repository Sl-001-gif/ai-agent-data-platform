package com.aiagent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 报告生成请求：基于已执行会话生成报告。 */
@Data
public class AnalysisReportRequest {

    @NotNull(message = "会话ID不能为空")
    private Long sessionId;
}