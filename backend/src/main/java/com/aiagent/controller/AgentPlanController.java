package com.aiagent.controller;

import com.aiagent.dto.AgentPlanRequest;
import com.aiagent.entity.AgentPlan;
import com.aiagent.service.AgentPlanService;
import com.aiagent.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent 多步分析计划：拆解 → 执行 → 报告 三页共用接口。 */
@RestController
@RequestMapping("/api/agent-plan")
public class AgentPlanController {

    private final AgentPlanService agentPlanService;

    public AgentPlanController(AgentPlanService agentPlanService) {
        this.agentPlanService = agentPlanService;
    }

    /** 拆解宏观目标生成计划。 */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody AgentPlanRequest request,
                                                                   Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        try {
            AgentPlan plan = agentPlanService.decompose(userId, request.getGoal(), request.getDatasetId(),
                    request.getModelConfigId(), request.getTitle());
            return ResponseEntity.ok(ApiResponse.success("计划生成成功", detail(plan)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(new ApiResponse<>(422, e.getMessage(), Map.of()));
        }
    }

    /** 执行计划：逐步执行并返回最新状态。 */
    @PostMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> execute(@PathVariable Long id,
                                                                    Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        try {
            AgentPlan plan = agentPlanService.execute(id, userId);
            return ResponseEntity.ok(ApiResponse.success("执行完成", detail(plan)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(new ApiResponse<>(404, e.getMessage(), Map.of()));
        }
    }

    /** 生成报告（覆盖式）。 */
    @PostMapping("/{id}/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(@PathVariable Long id,
                                                                   @RequestBody(required = false) AgentPlanRequest request,
                                                                   Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        Long modelConfigId = request == null ? null : request.getModelConfigId();
        try {
            AgentPlan plan = agentPlanService.generateReport(id, userId, modelConfigId);
            return ResponseEntity.ok(ApiResponse.success("报告生成成功", reportDetail(plan)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(new ApiResponse<>(404, e.getMessage(), Map.of()));
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(new ApiResponse<>(422, e.getMessage(), Map.of()));
        }
    }

    /** 计划详情（含步骤状态与结果）。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@PathVariable Long id,
                                                                   Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        try {
            return ResponseEntity.ok(ApiResponse.success("查询成功", detail(agentPlanService.loadOwned(id, userId))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(new ApiResponse<>(404, e.getMessage(), Map.of()));
        }
    }

    /** 历史计划列表。 */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentPlan plan : agentPlanService.list(userId)) {
            rows.add(summary(plan));
        }
        return ResponseEntity.ok(ApiResponse.success("查询成功", rows));
    }

    /** 历史报告列表（已生成报告的计划）。 */
    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reports(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentPlan plan : agentPlanService.listReports(userId)) {
            Map<String, Object> row = summary(plan);
            row.put("reportTitle", plan.getReportTitle());
            row.put("reportGeneratorType", plan.getReportGeneratorType());
            rows.add(row);
        }
        return ResponseEntity.ok(ApiResponse.success("查询成功", rows));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id,
                                                                   Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        try {
            agentPlanService.delete(id, userId);
            return ResponseEntity.ok(ApiResponse.success("删除成功", Map.of()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(new ApiResponse<>(404, e.getMessage(), Map.of()));
        }
    }

    private Map<String, Object> summary(AgentPlan plan) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", plan.getId());
        row.put("title", plan.getTitle());
        row.put("goal", plan.getGoal());
        row.put("datasetId", plan.getDatasetId());
        row.put("status", plan.getStatus());
        row.put("createTime", plan.getCreateTime());
        return row;
    }

    private Map<String, Object> detail(AgentPlan plan) {
        Map<String, Object> data = summary(plan);
        List<AgentPlanService.PlanStep> steps = agentPlanService.parseSteps(plan.getStepsJson());
        data.put("steps", steps);
        data.put("reportTitle", plan.getReportTitle());
        data.put("reportContent", plan.getReportContent());
        data.put("reportGeneratorType", plan.getReportGeneratorType());
        data.put("charts", agentPlanService.buildCharts(steps));
        return data;
    }

    private Map<String, Object> reportDetail(AgentPlan plan) {
        Map<String, Object> data = summary(plan);
        List<AgentPlanService.PlanStep> steps = agentPlanService.parseSteps(plan.getStepsJson());
        data.put("steps", steps);
        data.put("reportTitle", plan.getReportTitle());
        data.put("reportContent", plan.getReportContent());
        data.put("reportGeneratorType", plan.getReportGeneratorType());
        data.put("charts", agentPlanService.buildCharts(steps));
        return data;
    }
}
