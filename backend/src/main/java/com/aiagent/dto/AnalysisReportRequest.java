package com.aiagent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 报告生成请求：基于已执行会话生成报告，默认最近一轮，可按轮次生成。 */
@Data
public class AnalysisReportRequest {

    @NotNull(message = "会话ID不能为空")
    private Long sessionId;

    /** 轮次，为空时默认最近一轮。 */
    private Integer roundNo;

    /** 模型配置 ID，为空时按用途自动路由。 */
    private Long modelConfigId;
}