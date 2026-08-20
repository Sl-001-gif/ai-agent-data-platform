package com.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AnalysisParseRequest {

    @NotBlank(message = "分析目标不能为空")
    private String text;

    /** 数据集 ID，为空时全库元数据路由。 */
    private Long datasetId;

    /** 模型配置 ID，为空时按用途自动路由。 */
    private Long modelConfigId;

    /** 会话标题（新建会话时可选，缺省自动命名；不是分析问题）。 */
    private String title;

    /** 多轮分析目标（会话级背景，可选；不是单次分析问题）。 */
    private String analysisGoal;

    /** 会话状态（新建会话时可选：ACTIVE 进行中 / ARCHIVED 已完成，缺省 ACTIVE）。 */
    private String status;

    /** 会话 ID（生成计划时可选：为空则新建会话，非空则复用并追加/覆盖轮次）。 */
    private Long sessionId;
}