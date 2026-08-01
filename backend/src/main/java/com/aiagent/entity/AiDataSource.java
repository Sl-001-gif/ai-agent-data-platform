package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 数据源配置（v1：仅配置管理，不参与 AI 引擎动态切源）。 */
@Data
public class AiDataSource {
    private Long id;
    private String name;
    private String dbType;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;
    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}