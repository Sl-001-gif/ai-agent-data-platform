package com.aiagent.service;

import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisReportMapper;
import com.aiagent.mapper.AnalysisSessionMapper;
import com.aiagent.mapper.AnalysisStepMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 分析会话与步骤追踪：会话复用 + 多轮（round）步骤落库。 */
@Service
public class AnalysisTraceService {

    private static final int MAX_OUTPUT_LENGTH = 60000;

    private final AnalysisSessionMapper sessionMapper;
    private final AnalysisStepMapper stepMapper;
    private final AnalysisReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    public AnalysisTraceService(AnalysisSessionMapper sessionMapper, AnalysisStepMapper stepMapper,
                                AnalysisReportMapper reportMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.stepMapper = stepMapper;
        this.reportMapper = reportMapper;
        this.objectMapper = objectMapper;
    }

    /** 开始或复用结果：会话 + 本次落库轮次。 */
    public record StartResult(AnalysisSession session, Integer roundNo) {
    }

    /**
     * 开始或复用会话：
     * - sessionId 为空：新建会话（datasetId 可选），轮次从 1 开始。
     * - sessionId 非空：校验归属并刷新标题/目标/数据集；
     *   与最近一轮问题相同（规范化比较）则覆盖该轮（清理该轮旧步骤与旧报告），否则追加新轮。
     */
    public StartResult startOrReuse(Long userId, Long sessionId, String title, String goal,
                                    Long datasetId, String question) {
        if (sessionId == null) {
            AnalysisSession session = new AnalysisSession();
            session.setUserId(userId);
            session.setTitle(title == null || title.isBlank() ? "智能分析会话" : title);
            session.setAnalysisGoal(goal);
            session.setDatasetId(datasetId);
            session.setStatus("ACTIVE");
            sessionMapper.insert(session);
            return new StartResult(session, 1);
        }
        AnalysisSession existing = validateOwnership(userId, sessionId);
        if (goal != null) {
            existing.setAnalysisGoal(goal);
        }
        if (datasetId != null) {
            existing.setDatasetId(datasetId);
        }
        if (title != null && !title.isBlank()) {
            existing.setTitle(title);
        }
        existing.setStatus("ACTIVE");
        sessionMapper.update(existing);

        List<AnalysisStep> steps = stepMapper.selectBySessionId(sessionId);
        Integer latest = latestRound(steps);
        String latestQuestion = latestQuestion(steps, latest);
        int roundNo;
        if (latest != null && latestQuestion != null && normalize(latestQuestion).equals(normalize(question))) {
            roundNo = latest;
            stepMapper.deleteBySessionIdAndRound(sessionId, roundNo);
            reportMapper.deleteBySessionIdAndRound(sessionId, roundNo);
        } else {
            roundNo = latest == null ? 1 : latest + 1;
        }
        return new StartResult(existing, roundNo);
    }

    /** 只读校验会话归属（不清理步骤），供报告生成等只读场景复用。 */
    public AnalysisSession validateOwnership(Long userId, Long sessionId) {
        AnalysisSession existing = sessionMapper.selectById(sessionId);
        if (existing == null || !userId.equals(existing.getUserId())) {
            throw new RuntimeException("会话不存在或无权访问");
        }
        return existing;
    }

    /** 会话最大轮次；无步骤返回 null。 */
    public Integer latestRound(Long sessionId) {
        return latestRound(stepMapper.selectBySessionId(sessionId));
    }

    /** 追加步骤记录，output 超限安全截断后入库。 */
    public void appendStep(Long sessionId, Integer roundNo, int order, String stepType, String input, String output,
                           String status, String error, long durationMs) {
        AnalysisStep step = new AnalysisStep();
        step.setSessionId(sessionId);
        step.setRoundNo(roundNo == null ? 1 : roundNo);
        step.setStepOrder(order);
        step.setStepType(stepType);
        step.setInputData(input);
        step.setOutputData(truncate(output));
        step.setStatus(status);
        step.setErrorMessage(error);
        step.setDurationMs(durationMs);
        stepMapper.insert(step);
    }

    /** 最近轮次号；无步骤返回 null。 */
    static Integer latestRound(List<AnalysisStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        int max = 0;
        for (AnalysisStep step : steps) {
            if (step.getRoundNo() != null && step.getRoundNo() > max) {
                max = step.getRoundNo();
            }
        }
        return max == 0 ? null : max;
    }

    /** 指定轮次的 INTENT 输入（原始问题，JSON 解码后返回）。 */
    static String latestQuestion(List<AnalysisStep> steps, Integer roundNo) {
        if (steps == null || roundNo == null) {
            return null;
        }
        for (AnalysisStep step : steps) {
            if (roundNo.equals(step.getRoundNo()) && "INTENT".equals(step.getStepType())) {
                return decodeQuestion(step.getInputData());
            }
        }
        return null;
    }

    /** 问题文本规范化：去首尾空白、压缩连续空白。 */
    static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /** INTENT 步骤 input 为 JSON 字符串；解析失败按原文。 */
    private static String decodeQuestion(String json) {
        if (json == null) {
            return null;
        }
        String value = json.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            try {
                return new ObjectMapper().readTree(value).asText();
            } catch (Exception e) {
                return json;
            }
        }
        return json;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_OUTPUT_LENGTH) {
            return value;
        }
        // EXECUTE 大结果集截断时保持 JSON 合法，避免 report 反序列化失败。
        String cut = value.substring(0, MAX_OUTPUT_LENGTH);
        int brace = cut.lastIndexOf('}');
        if (brace < 0) {
            return cut;
        }
        String base = cut.substring(0, brace + 1);
        int opens = countChar(base, '[');
        int closes = countChar(base, ']');
        return opens > closes ? base + "]}" : base;
    }

    private static int countChar(String value, char ch) {
        int n = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ch) {
                n++;
            }
        }
        return n;
    }
}
