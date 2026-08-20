package com.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiagent.entity.AnalysisReport;
import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.dto.PageResult;
import com.aiagent.entity.Dataset;
import com.aiagent.mapper.AnalysisReportMapper;
import com.aiagent.mapper.AnalysisSessionMapper;
import com.aiagent.mapper.AnalysisStepMapper;
import com.aiagent.mapper.MetadataAdminMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分析历史查询：会话列表/删除（含批量）、会话步骤、报告列表/详情（仅本人数据）+ 数据集/模型选项。 */
@Service
public class AnalysisHistoryService {

    private final AnalysisSessionMapper sessionMapper;
    private final AnalysisStepMapper stepMapper;
    private final AnalysisReportMapper reportMapper;
    private final AnalysisTraceService traceService;
    private final MetadataAdminMapper metadataAdminMapper;
    private final ObjectMapper objectMapper;

    public AnalysisHistoryService(AnalysisSessionMapper sessionMapper, AnalysisStepMapper stepMapper,
                                  AnalysisReportMapper reportMapper, AnalysisTraceService traceService,
                                  MetadataAdminMapper metadataAdminMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.stepMapper = stepMapper;
        this.reportMapper = reportMapper;
        this.traceService = traceService;
        this.metadataAdminMapper = metadataAdminMapper;
        this.objectMapper = objectMapper;
    }

    /** 新建会话（仅建会话容器，不触发分析）：标题/多轮分析目标/数据集/状态。 */
    public AnalysisSession createSession(Long userId, String title, String goal, Long datasetId, String status) {
        AnalysisSession session = new AnalysisSession();
        session.setUserId(userId);
        session.setTitle(title == null || title.isBlank() ? (goal == null ? "未命名会话" : goal) : title);
        session.setAnalysisGoal(goal);
        session.setDatasetId(datasetId);
        session.setStatus(status == null || status.isBlank() ? "ACTIVE" : status);
        sessionMapper.insert(session);
        return session;
    }

    /** 删除报告：校验归属后删除。 */
    public void deleteReport(Long userId, Long reportId) {
        AnalysisReport report = reportMapper.selectById(reportId);
        if (report == null || !userId.equals(report.getCreateBy())) {
            throw new RuntimeException("报告不存在或无权访问");
        }
        reportMapper.deleteById(reportId);
    }

    private static final int MAX_PAGE_SIZE = 1000;

    public PageResult<AnalysisSession> listSessions(Long userId, String keyword, Long datasetId, int page, int pageSize) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = sessionMapper.countByUserId(userId, kw, datasetId);
        int offset = (int) Math.min(Math.max((long) (page - 1) * size, 0L), total);
        List<AnalysisSession> rows = total == 0 || offset >= total ? new ArrayList<>()
                : sessionMapper.selectByUserId(userId, kw, datasetId, offset, size);
        return PageResult.of(rows, total);
    }

    /** 删除会话：校验归属后级联删除步骤与报告。 */
    public void deleteSession(Long userId, Long sessionId) {
        traceService.validateOwnership(userId, sessionId);
        stepMapper.deleteBySessionId(sessionId);
        reportMapper.deleteBySessionId(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    /** 批量删除会话：逐个校验归属后删除（步骤/报告依赖外键级联）。 */
    @Transactional
    public void deleteSessions(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            traceService.validateOwnership(userId, id);
        }
        sessionMapper.deleteBatch(ids);
    }

    /** 登录用户可见的数据集选项（不含连接信息），供会话绑定与筛选。 */
    public List<Map<String, Object>> listDatasetOptions() {
        List<Dataset> rows = metadataAdminMapper.selectDatasetList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Dataset row : rows) {
            if (row.getStatus() != null && row.getStatus() != 1) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("name", row.getName());
            item.put("description", row.getDescription());
            item.put("tableName", row.getTableName());
            result.add(item);
        }
        return result;
    }

    public List<AnalysisStep> listSteps(Long userId, Long sessionId) {
        traceService.validateOwnership(userId, sessionId);
        return stepMapper.selectBySessionId(sessionId);
    }

    public PageResult<AnalysisReport> listReports(Long userId, int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = reportMapper.countByUserId(userId);
        int offset = (int) Math.min(Math.max((long) (page - 1) * size, 0L), total);
        List<AnalysisReport> rows = total == 0 || offset >= total ? new ArrayList<>()
                : reportMapper.selectByUserId(userId, offset, size);
        return PageResult.of(rows, total);
    }

    /** 某会话某轮次的报告（仅本人数据；roundNo 为空取最新一轮）。 */
    public AnalysisReport reportBySessionAndRound(Long userId, Long sessionId, Integer roundNo) {
        traceService.validateOwnership(userId, sessionId);
        AnalysisReport report = roundNo == null ? reportMapper.selectBySessionId(sessionId)
                : reportMapper.selectBySessionIdAndRound(sessionId, roundNo);
        if (report == null) {
            throw new RuntimeException("该轮未生成报告");
        }
        return report;
    }

    public AnalysisReport reportDetail(Long userId, Long reportId) {
        AnalysisReport report = reportMapper.selectById(reportId);
        if (report == null || !userId.equals(report.getCreateBy())) {
            throw new RuntimeException("报告不存在或无权访问");
        }
        report.setChart(buildChartFromSteps(report.getSessionId(), report.getRoundNo()));
        return report;
    }

    /** 按会话轮次重建报告图表数据：PLAN(类型/标题) + EXECUTE(列/行)，缺失返回 null。 */
    private Map<String, Object> buildChartFromSteps(Long sessionId, Integer roundNo) {
        List<AnalysisStep> steps = stepMapper.selectBySessionId(sessionId);
        if (steps == null || steps.isEmpty() || roundNo == null) {
            return null;
        }
        Map<String, Object> plan = null;
        Map<String, Object> execution = null;
        for (AnalysisStep step : steps) {
            if (!roundNo.equals(step.getRoundNo())) {
                continue;
            }
            try {
                if ("PLAN".equals(step.getStepType())) {
                    plan = objectMapper.readValue(step.getOutputData(), Map.class);
                } else if ("EXECUTE".equals(step.getStepType())) {
                    execution = objectMapper.readValue(step.getOutputData(), Map.class);
                }
            } catch (Exception ignored) {
                // 步骤数据解析失败时跳过该步骤，仍尝试其他步骤
            }
        }
        if (execution == null || execution.get("columns") == null) {
            return null;
        }
        Map<String, Object> chart = new LinkedHashMap<>();
        Object chartType = plan == null ? null : plan.get("chartType");
        chart.put("chartType", chartType == null ? "table" : String.valueOf(chartType));
        Object table = plan == null ? null : plan.get("targetTable");
        Object metrics = plan == null ? null : plan.get("metrics");
        String metricText = metrics instanceof List ? String.join("、", (List<String>) metrics) : "";
        chart.put("title", (table == null ? "" : table) + (metricText.isEmpty() ? "" : "「" + metricText + "」"));
        chart.put("columns", execution.get("columns"));
        Object rows = execution.get("rows");
        List<?> rowList = rows instanceof List ? (List<?>) rows : List.of();
        chart.put("rows", rowList.size() > 500 ? rowList.subList(0, 500) : rowList);
        return chart;
    }
}