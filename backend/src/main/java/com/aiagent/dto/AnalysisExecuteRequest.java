package com.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 分析执行请求：分析目标 + 可选会话/数据集/模型。 */
@Data
public class AnalysisExecuteRequest {

    @NotBlank(message = "分析目标不能为空")
    private String text;

    /** 会话 ID，为空时新建会话。 */
    private Long sessionId;

    /** 数据集 ID，为空时全库元数据路由。 */
    private Long datasetId;

    /** 模型配置 ID，为空时按用途自动路由。 */
    private Long modelConfigId;

    /** 会话标题（新建会话时可选，缺省自动命名；不是分析问题）。 */
    private String title;

    /** 多轮分析目标（会话级背景，可选；不是单次分析问题）。 */
    private String analysisGoal;
}