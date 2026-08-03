package com.aiagent.service;

import com.aiagent.entity.AnalysisReport;
import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisReportMapper;
import com.aiagent.mapper.AnalysisSessionMapper;
import com.aiagent.mapper.AnalysisStepMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 分析历史查询：会话列表/删除、会话步骤、报告列表/详情（仅本人数据）。 */
@Service
public class AnalysisHistoryService {

    private final AnalysisSessionMapper sessionMapper;
    private final AnalysisStepMapper stepMapper;
    private final AnalysisReportMapper reportMapper;
    private final AnalysisTraceService traceService;

    public AnalysisHistoryService(AnalysisSessionMapper sessionMapper, AnalysisStepMapper stepMapper,
                                  AnalysisReportMapper reportMapper, AnalysisTraceService traceService) {
        this.sessionMapper = sessionMapper;
        this.stepMapper = stepMapper;
        this.reportMapper = reportMapper;
        this.traceService = traceService;
    }

    public List<AnalysisSession> listSessions(Long userId, String keyword) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return sessionMapper.selectByUserId(userId, kw);
    }

    /** 删除会话：校验归属后级联删除步骤与报告。 */
    public void deleteSession(Long userId, Long sessionId) {
        traceService.validateOwnership(userId, sessionId);
        stepMapper.deleteBySessionId(sessionId);
        reportMapper.deleteBySessionId(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    public List<AnalysisStep> listSteps(Long userId, Long sessionId) {
        traceService.validateOwnership(userId, sessionId);
        return stepMapper.selectBySessionId(sessionId);
    }

    public List<AnalysisReport> listReports(Long userId) {
        return reportMapper.selectByUserId(userId);
    }

    public AnalysisReport reportDetail(Long userId, Long reportId) {
        AnalysisReport report = reportMapper.selectById(reportId);
        if (report == null || !userId.equals(report.getCreateBy())) {
            throw new RuntimeException("报告不存在或无权访问");
        }
        return report;
    }
}