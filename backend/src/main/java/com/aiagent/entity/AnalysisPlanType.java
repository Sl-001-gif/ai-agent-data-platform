package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 分析计划类型：可自定义类型字典（编码/名称/颜色/路由关键词/启停状态）。 */
@Data
public class AnalysisPlanType {
    private Long id;
    private String typeCode;
    private String typeName;
    private String color;
    private String routeKeywords;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}