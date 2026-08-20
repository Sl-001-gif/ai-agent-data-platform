package com.aiagent.ai.intent;

import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.prompt.PromptLoader;
import com.aiagent.service.AnalysisConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LLM 意图识别器（@Primary）：LLM 优先，规则兜底。
 * 稳定策略：仅当 LLM 意图编码与规则一致，或规则本身很弱（GENERAL/低置信）时采用 LLM 结果，
 * 否则维持规则结果 —— 保证计划路由与集成测试确定性；未配置 Key/输出非法时整体回退规则。
 */
@Component
@Primary
public class LlmIntentRecognizer implements IntentRecognizer {

    private static final String SYSTEM_PROMPT =
            "你是数据分析意图识别器。根据用户问题判断分析意图，只输出一个 JSON 对象，不要 markdown、不要任何解释。"
                    + "JSON 字段：intentType（必须从给定可选项中选一个）、intentName（中文名称）、"
                    + "confidence（0~1 的置信度）、matchedKeywords（命中的关键词数组）。";

    private final LlmClient llmClient;
    private final ModelRouter modelRouter;
    private final RuleIntentRecognizer ruleIntentRecognizer;
    private final AnalysisConfigService configService;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;

    public LlmIntentRecognizer(LlmClient llmClient, ModelRouter modelRouter,
                               RuleIntentRecognizer ruleIntentRecognizer,
                               AnalysisConfigService configService, ObjectMapper objectMapper,
                               PromptLoader promptLoader) {
        this.llmClient = llmClient;
        this.modelRouter = modelRouter;
        this.ruleIntentRecognizer = ruleIntentRecognizer;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    @Override
    public RecognizedIntent recognize(String text) {
        RecognizedIntent rule = ruleIntentRecognizer.recognize(text);
        if (text == null || text.trim().isEmpty() || !llmClient.isConfigured()) {
            return rule;
        }
        try {
            List<String> codes = knownCodes();
            String user = "可选意图编码：" + String.join("、", codes) + "。\n用户问题：" + text;
            String raw = llmClient.chat(promptLoader.load("INTENT", SYSTEM_PROMPT), user, modelRouter.resolve("INTENT"));
            JsonNode node = objectMapper.readTree(cleanJson(raw));
            String type = node.path("intentType").asText("");
            if (!codes.contains(type)) {
                return rule;
            }
            String name = node.path("intentName").asText("");
            double confidence = Math.max(0, Math.min(1, node.path("confidence").asDouble(0.5)));
            List<String> keywords = new ArrayList<>();
            JsonNode arr = node.path("matchedKeywords");
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    String kw = item.asText("");
                    if (!kw.isBlank()) {
                        keywords.add(kw);
                    }
                }
            }
            markGov(text, keywords);
            RecognizedIntent llm = new RecognizedIntent(type, name.isBlank() ? type : name, confidence, keywords);
            boolean sameType = type.equals(rule.getIntentType());
            boolean ruleWeak = "GENERAL".equals(rule.getIntentType()) || rule.getConfidence() < 0.3;
            return sameType || ruleWeak ? llm : rule;
        } catch (RuntimeException e) {
            return rule;
        } catch (Exception e) {
            return rule;
        }
    }

    /** 配置中心已登记的意图编码（保持 LLM 输出与计划配置一致）。 */
    private List<String> knownCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (AnalysisConfigService.IntentRuleSpec rule : configService.intentRules()) {
            codes.add(rule.code());
        }
        return new ArrayList<>(codes);
    }

    /** 提取首个 { ... } JSON 对象，兼容 LLM 输出带代码块围栏。 */
    static String cleanJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "{}";
    }

    /** 命中政务关键词时追加「政务公开」标记（与规则识别器一致，保证 GOV 路由）。 */
    private void markGov(String text, List<String> keywords) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String keyword : configService.govKeywords()) {
            if (normalized.contains(keyword)) {
                if (!keywords.contains("政务公开")) {
                    keywords.add("政务公开");
                }
                return;
            }
        }
    }
}