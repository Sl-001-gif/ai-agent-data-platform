package com.aiagent.ai.intent;

/** 分析意图识别器接口（预留 LLM 实现位）。 */
public interface IntentRecognizer {

    /** 从自然语言输入中识别分析意图。 */
    RecognizedIntent recognize(String text);
}