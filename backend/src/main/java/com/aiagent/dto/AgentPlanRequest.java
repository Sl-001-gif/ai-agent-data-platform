package com.aiagent.dto;

import lombok.Data;

/** Agent 分析计划请求：宏观目标拆解/执行/报告 共用。 */
@Data
public class AgentPlanRequest {
    /** 宏观分析目标（拆解用）。 */
    private String goal;
    /** 计划标题（可选，缺省取目标前 50 字）。 */
    private String title;
    private Long datasetId;
    /** 步骤执行/报告生成模型配置（null 按用途自动路由）。 */
    private Long modelConfigId;
}