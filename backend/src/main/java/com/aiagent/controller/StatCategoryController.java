package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.entity.StatCategory;
import com.aiagent.service.StatCategoryService;
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

/** 统计月报指标分类树管理端：树查询 + 增删改（仅 ADMIN，由 SecurityConfig 统一控制）。 */
@RestController
@RequestMapping("/api/admin/stat-category")
public class StatCategoryController {

    private final StatCategoryService statCategoryService;

    public StatCategoryController(StatCategoryService statCategoryService) {
        this.statCategoryService = statCategoryService;
    }

    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<StatCategory>>> tree() {
        return ResponseEntity.ok(ApiResponse.success(statCategoryService.tree()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StatCategory>> create(@RequestBody StatCategory node) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", statCategoryService.create(node)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> update(@PathVariable Long id, @RequestBody StatCategory node) {
        statCategoryService.update(id, node);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        statCategoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }
}
