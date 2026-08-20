package com.aiagent.controller;

import com.aiagent.dto.AnalysisParseRequest;
import com.aiagent.dto.PageResult;
import com.aiagent.dto.ApiResponse;
import jakarta.validation.Valid;
import com.aiagent.dto.BatchDeleteRequest;
import com.aiagent.entity.AiModelConfig;
import com.aiagent.entity.AnalysisReport;
import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.service.AiConfigService;
import com.aiagent.service.AnalysisHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 分析历史入口：会话列表/删除（含批量）、会话步骤、报告历史、数据集与模型选项。 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisHistoryController {

    private final AnalysisHistoryService historyService;
    private final AiConfigService aiConfigService;

    public AnalysisHistoryController(AnalysisHistoryService historyService, AiConfigService aiConfigService) {
        this.historyService = historyService;
        this.aiConfigService = aiConfigService;
    }

    /** 新建会话（仅创建会话容器，不触发分析）。 */
    @PostMapping("/session")
    public ResponseEntity<ApiResponse<AnalysisSession>> createSession(@Valid @RequestBody AnalysisParseRequest request,
                                                                      Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        AnalysisSession session = historyService.createSession(userId, request.getTitle(),
                request.getText(), request.getDatasetId(), request.getStatus());
        return ResponseEntity.ok(ApiResponse.success("会话创建成功", session));
    }

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<PageResult<AnalysisSession>>> listSessions(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long datasetId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.listSessions(userId, keyword, datasetId, page, pageSize)));
    }

    @DeleteMapping("/session/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSession(@PathVariable Long id, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        historyService.deleteSession(userId, id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @PostMapping("/sessions/batch-delete")
    public ResponseEntity<ApiResponse<Void>> batchDeleteSessions(@RequestBody BatchDeleteRequest body,
                                                                  Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        historyService.deleteSessions(userId, body == null ? null : body.getIds());
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/session/{id}/steps")
    public ResponseEntity<ApiResponse<List<AnalysisStep>>> listSteps(@PathVariable Long id, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.listSteps(userId, id)));
    }

    @GetMapping("/datasets")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> datasetOptions() {
        return ResponseEntity.ok(ApiResponse.success(historyService.listDatasetOptions()));
    }

    @GetMapping("/models")
    public ResponseEntity<ApiResponse<List<AiModelConfig>>> modelOptions() {
        return ResponseEntity.ok(ApiResponse.success(aiConfigService.listEnabledModelOptions()));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<PageResult<AnalysisReport>>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.listReports(userId, page, pageSize)));
    }

    /** 某会话某轮次的报告内容（roundNo 为空取最新一轮），供会话详情查看报告，避免全量拉取报告列表。 */
    @GetMapping("/session/{sessionId}/report")
    public ResponseEntity<ApiResponse<AnalysisReport>> sessionReport(@PathVariable Long sessionId,
                                                                     @RequestParam(required = false) Integer roundNo,
                                                                     Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.reportBySessionAndRound(userId, sessionId, roundNo)));
    }

    @DeleteMapping("/report/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteReport(@PathVariable Long id, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        historyService.deleteReport(userId, id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/report/{id}")
    public ResponseEntity<ApiResponse<AnalysisReport>> reportDetail(@PathVariable Long id, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.reportDetail(userId, id)));
    }
}