package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.dto.ConnectionTestResult;
import com.aiagent.dto.DataSourceRequest;
import com.aiagent.entity.AiDataSource;
import com.aiagent.service.DataSourceAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端数据源管理：CRUD + 连接测试（仅 ADMIN 角色，由 SecurityConfig 统一控制）。 */
@RestController
@RequestMapping("/api/admin/datasource")
public class DataSourceAdminController {

    private final DataSourceAdminService dataSourceService;

    public DataSourceAdminController(DataSourceAdminService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AiDataSource>>> list() {
        return ResponseEntity.ok(ApiResponse.success(dataSourceService.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AiDataSource>> create(@Valid @RequestBody DataSourceRequest request,
                                                            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success("新增成功", dataSourceService.create(request, userId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> update(@PathVariable Long id, @Valid @RequestBody DataSourceRequest request) {
        dataSourceService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        dataSourceService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @PostMapping("/test")
    public ResponseEntity<ApiResponse<ConnectionTestResult>> test(@Valid @RequestBody DataSourceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("连接测试完成", dataSourceService.testConnection(request)));
    }
}