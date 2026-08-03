package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.entity.AiModelConfig;
import com.aiagent.entity.PromptTemplate;
import com.aiagent.service.AiConfigService;
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

/** AI 能力配置管理端：模型配置与 Prompt 模板 CRUD（仅 ADMIN，由 SecurityConfig 统一控制）。 */
@RestController
@RequestMapping("/api/admin/ai-config")
public class AiConfigAdminController {

    private final AiConfigService aiConfigService;

    public AiConfigAdminController(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @GetMapping("/models")
    public ResponseEntity<ApiResponse<List<AiModelConfig>>> listModels() {
        return ResponseEntity.ok(ApiResponse.success(aiConfigService.listModels()));
    }

    @PostMapping("/models")
    public ResponseEntity<ApiResponse<AiModelConfig>> createModel(@RequestBody AiModelConfig request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", aiConfigService.createModel(request)));
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<ApiResponse<Void>> updateModel(@PathVariable Long id, @RequestBody AiModelConfig request) {
        aiConfigService.updateModel(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteModel(@PathVariable Long id) {
        aiConfigService.deleteModel(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/prompts")
    public ResponseEntity<ApiResponse<List<PromptTemplate>>> listPrompts() {
        return ResponseEntity.ok(ApiResponse.success(aiConfigService.listPrompts()));
    }

    @PostMapping("/prompts")
    public ResponseEntity<ApiResponse<PromptTemplate>> createPrompt(@RequestBody PromptTemplate request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", aiConfigService.createPrompt(request)));
    }

    @PutMapping("/prompts/{id}")
    public ResponseEntity<ApiResponse<Void>> updatePrompt(@PathVariable Long id, @RequestBody PromptTemplate request) {
        aiConfigService.updatePrompt(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/prompts/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePrompt(@PathVariable Long id) {
        aiConfigService.deletePrompt(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }
}