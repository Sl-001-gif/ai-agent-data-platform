package com.aiagent.ai.metadata;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内置演示元数据目录：订单/用户/商品三张演示表的字段与指标口径，后续由 DB 版实现替换。 */
@Component
public class DemoMetadataCatalog {

    /** 演示表结构。 */
    public record DemoTable(String name, String comment, List<String> metrics, List<String> dimensions) {
    }

    private static final Map<String, DemoTable> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("order_info", new DemoTable("order_info", "订单表",
                List.of("订单量", "销售额", "客单价"), List.of("日期", "区域", "品类", "渠道")));
        TABLES.put("user_info", new DemoTable("user_info", "用户表",
                List.of("新增用户数", "活跃用户数", "留存率"), List.of("日期", "年龄段", "性别", "城市")));
        TABLES.put("product_info", new DemoTable("product_info", "商品表",
                List.of("销量", "销售额", "毛利率"), List.of("品类", "品牌", "价格带")));
        TABLES.put("stat_indicator", new DemoTable("stat_indicator", "统计指标库",
                List.of("地区生产总值（GDP）", "地方一般公共预算收入", "规模以上工业增加值", "居民人均可支配收入"), List.of("期间", "区县", "指标")));
    }

    public DemoTable getTable(String name) {
        return TABLES.get(name);
    }

    public List<DemoTable> listTables() {
        return new ArrayList<>(TABLES.values());
    }
}