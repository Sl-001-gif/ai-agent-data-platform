package com.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AnalysisParseRequest {

    @NotBlank(message = "分析目标不能为空")
    private String text;
}