package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 数据集配置：可分析数据集的业务信息与连接配置。 */
@Data
public class Dataset {
    private Long id;
    private String name;
    private String description;
    private String businessScene;
    private String tableName;
    private Integer sort;
    private String dbType;
    private String dbHost;
    private Integer dbPort;
    private String dbName;
    private String dbUsername;
    private String dbPassword;
    private Integer status;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
