package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.entity.Dataset;
import com.aiagent.entity.MetricDefinition;
import com.aiagent.entity.TableField;
import com.aiagent.entity.TableSchema;
import com.aiagent.service.MetadataAdminService;
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

/** 数据元配置管理端：数据集/数据表/字段语义/指标口径 CRUD（仅 ADMIN，由 SecurityConfig 统一控制）。 */
@RestController
@RequestMapping("/api/admin")
public class MetadataAdminController {

    private final MetadataAdminService metadataAdminService;

    public MetadataAdminController(MetadataAdminService metadataAdminService) {
        this.metadataAdminService = metadataAdminService;
    }

    @GetMapping("/dataset")
    public ResponseEntity<ApiResponse<List<Dataset>>> listDatasets() {
        return ResponseEntity.ok(ApiResponse.success(metadataAdminService.listDatasets()));
    }

    @PostMapping("/dataset")
    public ResponseEntity<ApiResponse<Dataset>> createDataset(@RequestBody Dataset request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", metadataAdminService.createDataset(request)));
    }

    @PutMapping("/dataset/{id}")
    public ResponseEntity<ApiResponse<Void>> updateDataset(@PathVariable Long id, @RequestBody Dataset request) {
        metadataAdminService.updateDataset(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/dataset/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDataset(@PathVariable Long id) {
        metadataAdminService.deleteDataset(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/data-table")
    public ResponseEntity<ApiResponse<List<TableSchema>>> listTables() {
        return ResponseEntity.ok(ApiResponse.success(metadataAdminService.listTables()));
    }

    @PostMapping("/data-table")
    public ResponseEntity<ApiResponse<TableSchema>> createTable(@RequestBody TableSchema request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", metadataAdminService.createTable(request)));
    }

    @PutMapping("/data-table/{id}")
    public ResponseEntity<ApiResponse<Void>> updateTable(@PathVariable Long id, @RequestBody TableSchema request) {
        metadataAdminService.updateTable(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/data-table/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTable(@PathVariable Long id) {
        metadataAdminService.deleteTable(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/field-semantic")
    public ResponseEntity<ApiResponse<List<TableField>>> listFields() {
        return ResponseEntity.ok(ApiResponse.success(metadataAdminService.listFields()));
    }

    @PostMapping("/field-semantic")
    public ResponseEntity<ApiResponse<TableField>> createField(@RequestBody TableField request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", metadataAdminService.createField(request)));
    }

    @PutMapping("/field-semantic/{id}")
    public ResponseEntity<ApiResponse<Void>> updateField(@PathVariable Long id, @RequestBody TableField request) {
        metadataAdminService.updateField(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/field-semantic/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteField(@PathVariable Long id) {
        metadataAdminService.deleteField(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }

    @GetMapping("/metric")
    public ResponseEntity<ApiResponse<List<MetricDefinition>>> listMetrics() {
        return ResponseEntity.ok(ApiResponse.success(metadataAdminService.listMetrics()));
    }

    @PostMapping("/metric")
    public ResponseEntity<ApiResponse<MetricDefinition>> createMetric(@RequestBody MetricDefinition request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", metadataAdminService.createMetric(request)));
    }

    @PutMapping("/metric/{id}")
    public ResponseEntity<ApiResponse<Void>> updateMetric(@PathVariable Long id, @RequestBody MetricDefinition request) {
        metadataAdminService.updateMetric(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/metric/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteMetric(@PathVariable Long id) {
        metadataAdminService.deleteMetric(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }
}
