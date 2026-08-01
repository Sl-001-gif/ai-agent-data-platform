package com.aiagent.ai.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** K2 推理模型 temperature 兼容（只允许 1，省略参数）回归测试。 */
class LlmClientTest {

    @Test
    void k2ModelsSkipTemperatureParameter() {
        assertFalse(LlmClient.supportsTemperature("kimi-k2.7-code"));
        assertFalse(LlmClient.supportsTemperature("kimi-k2.6"));
        assertFalse(LlmClient.supportsTemperature("KIMI-K2.7-CODE"));
    }

    @Test
    void otherModelsKeepTemperatureParameter() {
        assertTrue(LlmClient.supportsTemperature("gpt-4o"));
        assertTrue(LlmClient.supportsTemperature("moonshot-v1-8k"));
        assertTrue(LlmClient.supportsTemperature(null));
    }
}