package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.entity.AnalysisIntentRule;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.service.AnalysisConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 分析配置管理端：意图规则与计划配置 CRUD（仅 ADMIN，由 SecurityConfig 统一控制）。 */
@RestController
@RequestMapping("/api/admin/analysis-config")
public class AnalysisConfigAdminController {

    private final AnalysisConfigService analysisConfigService;

    public AnalysisConfigAdminController(AnalysisConfigService analysisConfigService) {
        this.analysisConfigService = analysisConfigService;
    }

    @GetMapping("/intent-rules")
    public ResponseEntity<ApiResponse<List<AnalysisIntentRule>>> listIntentRules() {
        return ResponseEntity.ok(ApiResponse.success(analysisConfigService.listIntentRules()));
    }

    @PostMapping("/intent-rules")
    public ResponseEntity<ApiResponse<AnalysisIntentRule>> createIntentRule(@RequestBody AnalysisIntentRule request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", analysisConfigService.createIntentRule(request)));
    }

    @PutMapping("/intent-rules/{id}")
    public ResponseEntity<ApiResponse<Void>> updateIntentRule(@PathVariable Long id, @RequestBody AnalysisIntentRule request) {
        analysisConfigService.updateIntentRule(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/intent-rules/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteIntentRule(@PathVariable Long id) {
        analysisConfigService.deleteIntentRule(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/plan-configs")
    public ResponseEntity<ApiResponse<List<AnalysisPlanConfig>>> listPlanConfigs() {
        return ResponseEntity.ok(ApiResponse.success(analysisConfigService.listPlanConfigs()));
    }

    @PostMapping("/plan-configs")
    public ResponseEntity<ApiResponse<AnalysisPlanConfig>> createPlanConfig(@RequestBody AnalysisPlanConfig request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", analysisConfigService.createPlanConfig(request)));
    }

    @PutMapping("/plan-configs/{id}")
    public ResponseEntity<ApiResponse<Void>> updatePlanConfig(@PathVariable Long id, @RequestBody AnalysisPlanConfig request) {
        analysisConfigService.updatePlanConfig(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/plan-configs/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePlanConfig(@PathVariable Long id) {
        analysisConfigService.deletePlanConfig(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }
}