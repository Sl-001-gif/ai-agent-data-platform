package com.aiagent.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 统计月报指标分类树节点（stat_indicator_category：一级大类 × 二级中类 × 三级叶子指标，level 1-3）。 */
@Data
public class StatCategory {
    private Long id;
    private Long parentId;
    private String name;
    private String code;
    private Integer level;
    private Integer sort;
    private String color;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 子节点（树形接口返回用，不入库）。 */
    private List<StatCategory> children = new ArrayList<>();
}
