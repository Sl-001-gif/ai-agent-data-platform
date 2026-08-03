package com.aiagent.ai.intent;

import com.aiagent.service.AnalysisConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 基于关键词规则的意图识别器：规则来自分析配置中心，库空回退内置，行为与旧静态配置一致。 */
@Component
public class RuleIntentRecognizer implements IntentRecognizer {

    private final AnalysisConfigService configService;

    /** 测试兜底：使用内置配置（不访问数据库）。 */
    public RuleIntentRecognizer() {
        this(AnalysisConfigService.builtinOnly());
    }

    @Autowired
    public RuleIntentRecognizer(AnalysisConfigService configService) {
        this.configService = configService;
    }

    private static final double GENERAL_CONFIDENCE = 0.1;

    @Override
    public RecognizedIntent recognize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new RecognizedIntent("GENERAL", "通用探索", GENERAL_CONFIDENCE, markGov(text, List.of()));
        }
        String normalized = text.toLowerCase(Locale.ROOT);

        for (AnalysisConfigService.IntentRuleSpec rule : configService.intentRules()) {
            List<String> matched = new ArrayList<>();
            for (String keyword : rule.keywords()) {
                if (normalized.contains(keyword)) {
                    matched.add(keyword);
                }
            }
            if (!matched.isEmpty()) {
                double confidence = Math.min(1.0, 0.5 + 0.15 * matched.size());
                return new RecognizedIntent(rule.code(), rule.name(), confidence, markGov(text, matched));
            }
        }
        return new RecognizedIntent("GENERAL", "通用探索", GENERAL_CONFIDENCE, markGov(text, List.of()));
    }

    /** 命中政务关键词时在 matchedKeywords 追加「政务公开」标记，供计划器路由到 gov_info_record。 */
    private List<String> markGov(String text, List<String> matched) {
        if (text == null || text.trim().isEmpty()) {
            return matched;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : configService.govKeywords()) {
            if (normalized.contains(keyword)) {
                List<String> result = new ArrayList<>(matched);
                if (!result.contains("政务公开")) {
                    result.add("政务公开");
                }
                return result;
            }
        }
        return matched;
    }
}