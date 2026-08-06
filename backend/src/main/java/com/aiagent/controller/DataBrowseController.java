package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.dto.DataBrowseRequest;
import com.aiagent.service.DataBrowseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 数据浏览（仅 ADMIN）：表列表 + 分页数据查询，全部只读。 */
@RestController
@RequestMapping("/api/admin/data-browse")
public class DataBrowseController {

    private final DataBrowseService dataBrowseService;

    public DataBrowseController(DataBrowseService dataBrowseService) {
        this.dataBrowseService = dataBrowseService;
    }

    @PostMapping("/tables")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> tables(@Valid @RequestBody DataBrowseRequest request) {
        return ResponseEntity.ok(ApiResponse.success(dataBrowseService.listTables(request.getDataSourceId())));
    }

    @PostMapping("/query")
    public ResponseEntity<ApiResponse<Map<String, Object>>> query(@Valid @RequestBody DataBrowseRequest request) {
        return ResponseEntity.ok(ApiResponse.success(dataBrowseService.queryData(
                request.getDataSourceId(), request.getTableName(), request.getPage(), request.getPageSize())));
    }
}