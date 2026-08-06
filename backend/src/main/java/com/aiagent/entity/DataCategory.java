package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 数据分类：用于管理后台「数据元配置」Tab 页签分类，以数据集（dataset）为单位打分类。 */
@Data
public class DataCategory {
    private Long id;
    private String name;
    private String color;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}