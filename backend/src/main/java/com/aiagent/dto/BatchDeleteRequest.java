package com.aiagent.dto;

import lombok.Data;

import java.util.List;

/** 批量删除请求。 */
@Data
public class BatchDeleteRequest {

    private List<Long> ids;
}