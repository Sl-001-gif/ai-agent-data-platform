package com.aiagent.ai.intent;

import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.prompt.PromptLoader;
import com.aiagent.mapper.AiConfigMapper;
import com.aiagent.service.AnalysisConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** LLM 意图识别：LLM 优先 + 稳定策略（与规则一致或规则很弱才采用 LLM 结果），未配置/异常回退规则。 */
class LlmIntentRecognizerTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final AnalysisConfigService configService = AnalysisConfigService.builtinOnly();
    private final RuleIntentRecognizer ruleRecognizer = new RuleIntentRecognizer();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptLoader promptLoader = new PromptLoader(mock(AiConfigMapper.class));
    private final LlmIntentRecognizer recognizer =
            new LlmIntentRecognizer(llmClient, modelRouter, ruleRecognizer, configService, objectMapper, promptLoader);

    @Test
    void shouldUseRuleWhenLlmNotConfigured() {
        when(llmClient.isConfigured()).thenReturn(false);

        RecognizedIntent intent = recognizer.recognize("分析最近30天的销售趋势");

        assertEquals("SALES_TREND", intent.getIntentType(), "未配置 LLM 应回退规则");
    }

    @Test
    void shouldUseLlmResultWhenTypeAgreesWithRule() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn(
                "{\"intentType\":\"SALES_TREND\",\"intentName\":\"销售趋势\",\"confidence\":0.92,\"matchedKeywords\":[\"趋势\"]}");

        RecognizedIntent intent = recognizer.recognize("分析最近30天的销售趋势");

        assertEquals("SALES_TREND", intent.getIntentType());
        assertEquals(0.92, intent.getConfidence(), 0.0001, "与规则一致时应采用 LLM 置信度");
    }

    @Test
    void shouldFallbackToRuleWhenLlmTypeUnknown() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn(
                "{\"intentType\":\"NOT_A_CODE\",\"intentName\":\"未知\",\"confidence\":0.9,\"matchedKeywords\":[]}");

        RecognizedIntent intent = recognizer.recognize("分析最近30天的销售趋势");

        assertEquals("SALES_TREND", intent.getIntentType(), "未登记编码应回退规则");
    }

    @Test
    void shouldKeepRuleWhenLlmDisagreesOnKnownType() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn(
                "{\"intentType\":\"GENERAL\",\"intentName\":\"通用\",\"confidence\":0.8,\"matchedKeywords\":[]}");

        RecognizedIntent intent = recognizer.recognize("分析最近30天的销售趋势");

        assertEquals("SALES_TREND", intent.getIntentType(), "规则明确时 LLM 分歧不应改变路由");
        assertEquals(0.8, intent.getConfidence(), 0.0001, "应保留规则置信度（命中 销售/趋势 两词）");
    }

    @Test
    void shouldUseLlmWhenRuleIsWeak() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn(
                "{\"intentType\":\"RANKING\",\"intentName\":\"排名\",\"confidence\":0.85,\"matchedKeywords\":[\"排名\"]}");

        RecognizedIntent intent = recognizer.recognize("帮我看看这组数据怎么样");

        assertEquals("RANKING", intent.getIntentType(), "规则 GENERAL 时应采用 LLM 明确意图");
    }

    @Test
    void shouldFallbackToRuleWhenLlmThrows() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenThrow(new RuntimeException("LLM 失败"));

        RecognizedIntent intent = recognizer.recognize("分析最近30天的销售趋势");

        assertEquals("SALES_TREND", intent.getIntentType(), "LLM 异常应回退规则");
    }

    @Test
    void shouldStripCodeFenceFromLlmOutput() {
        assertEquals("{\"a\":1}", LlmIntentRecognizer.cleanJson("```json\n{\"a\":1}\n```"));
        assertEquals("{}", LlmIntentRecognizer.cleanJson("无 JSON 输出"));
        assertEquals("{}", LlmIntentRecognizer.cleanJson(null));
    }
}