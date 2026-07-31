package com.aiagent.ai.intent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 分析意图识别结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecognizedIntent {
    /** 意图编码，如 SALES_TREND / USER_PROFILE / GENERAL。 */
    private String intentType;
    /** 意图名称，如 销售趋势。 */
    private String intentName;
    /** 置信度 0~1。 */
    private double confidence;
    /** 命中的关键词。 */
    private List<String> matchedKeywords;
}