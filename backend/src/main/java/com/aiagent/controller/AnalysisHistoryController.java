package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.entity.AnalysisReport;
import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.service.AnalysisHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 分析历史入口：会话列表/删除、会话步骤（计划与执行追踪）、报告历史。 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisHistoryController {

    private final AnalysisHistoryService historyService;

    public AnalysisHistoryController(AnalysisHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<AnalysisSession>>> listSessions(
            @RequestParam(required = false) String keyword, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.listSessions(userId, keyword)));
    }

    @DeleteMapping("/session/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSession(@PathVariable Long id, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        historyService.deleteSession(userId, id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/session/{id}/steps")
    public ResponseEntity<ApiResponse<List<AnalysisStep>>> listSteps(@PathVariable Long id, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.listSteps(userId, id)));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<AnalysisReport>>> listReports(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.listReports(userId)));
    }

    @GetMapping("/report/{id}")
    public ResponseEntity<ApiResponse<AnalysisReport>> reportDetail(@PathVariable Long id, Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(historyService.reportDetail(userId, id)));
    }
}