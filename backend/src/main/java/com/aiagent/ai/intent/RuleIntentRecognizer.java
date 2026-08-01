package com.aiagent.ai.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 基于关键词规则的意图识别器（第一版，预留 LLM 实现位）。 */
@Component
public class RuleIntentRecognizer implements IntentRecognizer {

    /** 意图规则，按优先级从高到低排列（先命中先胜出）。 */
    private static final Map<String, IntentRule> RULES = new LinkedHashMap<>();

    static {
        RULES.put("USER_PROFILE", new IntentRule("用户画像", List.of("用户画像", "人群画像", "客户画像", "画像", "人群", "偏好", "特征")));
        RULES.put("ANOMALY", new IntentRule("异常归因", List.of("下降", "下跌", "异常", "原因", "归因", "波动", "为什么")));
        RULES.put("RETENTION", new IntentRule("留存转化", List.of("留存", "转化", "复购", "流失")));
        RULES.put("COMPARISON", new IntentRule("对比分析", List.of("对比", "比较", "差异")));
        RULES.put("STRUCTURE", new IntentRule("占比结构", List.of("占比", "结构", "构成", "比例", "份额")));
        RULES.put("RANKING", new IntentRule("排名分析", List.of("排名", "排行", "最好", "最差", "top10", "top 10", "前10")));
        RULES.put("SALES_TREND", new IntentRule("销售趋势", List.of("销售", "销售额", "销量", "营收", "收入", "趋势", "走势", "增长")));
    }

    private static final double GENERAL_CONFIDENCE = 0.1;

    /** 政务类关键词：命中即在 matchedKeywords 追加 "政务公开" 标记，供计划器路由到 gov_info_record。 */
    private static final List<String> GOV_KEYWORDS = List.of("政务", "公开", "政府", "发文", "邵阳", "新宁");

    @Override
    public RecognizedIntent recognize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new RecognizedIntent("GENERAL", "通用探索", GENERAL_CONFIDENCE, markGov(text, List.of()));
        }
        String normalized = text.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, IntentRule> entry : RULES.entrySet()) {
            List<String> matched = new ArrayList<>();
            for (String keyword : entry.getValue().keywords()) {
                if (normalized.contains(keyword)) {
                    matched.add(keyword);
                }
            }
            if (!matched.isEmpty()) {
                double confidence = Math.min(1.0, 0.5 + 0.15 * matched.size());
                return new RecognizedIntent(entry.getKey(), entry.getValue().name(), confidence, markGov(text, matched));
            }
        }
        return new RecognizedIntent("GENERAL", "通用探索", GENERAL_CONFIDENCE, markGov(text, List.of()));
    }

    /** 意图规则（名称 + 关键词组）。 */
    private List<String> markGov(String text, List<String> matched) {
        if (text == null || text.trim().isEmpty()) {
            return matched;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : GOV_KEYWORDS) {
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
    private record IntentRule(String name, List<String> keywords) {
    }
}