package com.aiagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 数据浏览请求：选数据源 + 表 + 分页。 */
@Data
public class DataBrowseRequest {

    @NotNull(message = "数据源不能为空")
    private Long dataSourceId;

    private String tableName;

    @Min(value = 1, message = "页码从 1 开始")
    private Integer page = 1;

    @Min(value = 1, message = "每页最小 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 50;
}