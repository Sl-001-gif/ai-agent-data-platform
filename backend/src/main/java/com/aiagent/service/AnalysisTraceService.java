package com.aiagent.service;

import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisSessionMapper;
import com.aiagent.mapper.AnalysisStepMapper;
import org.springframework.stereotype.Service;

/** 分析会话与步骤追踪：会话覆盖式复用 + 步骤落库。 */
@Service
public class AnalysisTraceService {

    private static final int MAX_OUTPUT_LENGTH = 5000;

    private final AnalysisSessionMapper sessionMapper;
    private final AnalysisStepMapper stepMapper;

    public AnalysisTraceService(AnalysisSessionMapper sessionMapper, AnalysisStepMapper stepMapper) {
        this.sessionMapper = sessionMapper;
        this.stepMapper = stepMapper;
    }

    /**
     * 开始或复用会话：sessionId 为空新建（ACTIVE）；非空则校验归属，
     * 复用前覆盖式清理旧步骤，并刷新标题与状态。
     */
    public AnalysisSession startOrReuse(Long userId, Long sessionId, String title) {
        if (sessionId == null) {
            AnalysisSession session = new AnalysisSession();
            session.setUserId(userId);
            session.setTitle(title);
            session.setStatus("ACTIVE");
            sessionMapper.insert(session);
            return session;
        }
        AnalysisSession existing = sessionMapper.selectById(sessionId);
        if (existing == null || !userId.equals(existing.getUserId())) {
            throw new RuntimeException("会话不存在或无权访问");
        }
        stepMapper.deleteBySessionId(sessionId);
        existing.setTitle(title);
        existing.setStatus("ACTIVE");
        sessionMapper.update(existing);
        return existing;
    }

    /** 追加步骤记录，output 超过 5000 字符截断后入库。 */
    public void appendStep(Long sessionId, int order, String stepType, String input, String output,
                           String status, String error, long durationMs) {
        AnalysisStep step = new AnalysisStep();
        step.setSessionId(sessionId);
        step.setStepOrder(order);
        step.setStepType(stepType);
        step.setInputData(input);
        step.setOutputData(truncate(output));
        step.setStatus(status);
        step.setErrorMessage(error);
        step.setDurationMs(durationMs);
        stepMapper.insert(step);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_OUTPUT_LENGTH ? text.substring(0, MAX_OUTPUT_LENGTH) : text;
    }
}