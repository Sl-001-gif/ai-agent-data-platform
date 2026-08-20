package com.aiagent.ai.context;

import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisStepMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话历史上下文构建：取指定轮次之前的最近 N 轮（问题+解读结论），供解读/报告注入 Prompt。 */
@Component
public class AnalysisContextBuilder {

    private static final int DEFAULT_LIMIT = 3;

    private final AnalysisStepMapper stepMapper;
    private final ObjectMapper objectMapper;

    public AnalysisContextBuilder(AnalysisStepMapper stepMapper, ObjectMapper objectMapper) {
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
    }

    /** 构建历史上下文文本；无历史返回空串。 */
    public String buildHistoryContext(Long sessionId, Integer excludeRound) {
        return buildHistoryContext(sessionId, excludeRound, DEFAULT_LIMIT);
    }

    /** 构建历史上下文文本；limitRounds 控制最多引用前几轮，无历史返回空串。 */
    public String buildHistoryContext(Long sessionId, Integer excludeRound, int limitRounds) {
        if (sessionId == null) {
            return "";
        }
        List<AnalysisStep> steps = stepMapper.selectBySessionId(sessionId);
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        Map<Integer, List<AnalysisStep>> byRound = new LinkedHashMap<>();
        for (AnalysisStep step : steps) {
            if (step.getRoundNo() == null || step.getRoundNo() >= (excludeRound == null ? Integer.MAX_VALUE : excludeRound)) {
                continue;
            }
            byRound.computeIfAbsent(step.getRoundNo(), k -> new ArrayList<>()).add(step);
        }
        List<Integer> rounds = new ArrayList<>(byRound.keySet());
        rounds.sort(Integer::compareTo);
        int start = Math.max(0, rounds.size() - Math.max(1, limitRounds));
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < rounds.size(); i++) {
            List<AnalysisStep> roundSteps = byRound.get(rounds.get(i));
            String question = questionOf(roundSteps);
            String conclusion = conclusionOf(roundSteps);
            if (question == null && conclusion == null) {
                continue;
            }
            sb.append("第").append(rounds.get(i)).append("轮分析");
            if (question != null) {
                sb.append("，问题：").append(question);
            }
            if (conclusion != null) {
                sb.append("，结论：").append(conclusion);
            }
            sb.append("\n");
        }
        if (sb.length() == 0) {
            return "";
        }
        return "【会话历史分析（同一会话之前的分析，供参考，勿与本次分析混淆）】\n" + sb.toString().trim();
    }

    private String questionOf(List<AnalysisStep> steps) {
        for (AnalysisStep step : steps) {
            if ("INTENT".equals(step.getStepType())) {
                return readText(step.getInputData());
            }
        }
        return null;
    }

    private String conclusionOf(List<AnalysisStep> steps) {
        for (AnalysisStep step : steps) {
            if ("INTERPRET".equals(step.getStepType())) {
                return readInterpretation(step.getOutputData());
            }
        }
        return null;
    }

    /** INTENT 的 input 为 JSON 字符串；解析失败按原文。 */
    private String readText(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json).asText();
        } catch (Exception e) {
            return json;
        }
    }

    /** 从 INTERPRET 步骤输出提取解读正文。 */
    private String readInterpretation(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String text = node.path("interpretation").path("text").asText(null);
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }
}