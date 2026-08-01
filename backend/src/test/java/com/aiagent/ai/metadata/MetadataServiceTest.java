package com.aiagent.ai.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DemoMetadataCatalog demoCatalog = new DemoMetadataCatalog();
    private final MetadataService service = new MetadataService(jdbcTemplate, demoCatalog);

    @Test
    void shouldFallbackToDemoCatalogWhenTablesEmpty() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        String text = service.buildMetadataText();

        assertTrue(text.contains("订单表"), "回退文本应含演示表注释");
        assertTrue(text.contains("order_info"), "回退文本应含演示表名");
        assertTrue(text.contains("销售额"), "回退文本应含演示指标");
    }

    @Test
    void shouldBuildTextFromRowsWhenTablesExist() {
        when(jdbcTemplate.queryForList(MetadataService.DATASET_SQL))
                .thenReturn(List.of(row("name", "政务公开数据集", "description", "邵阳政务公开信息")));
        when(jdbcTemplate.queryForList(MetadataService.TABLE_SQL))
                .thenReturn(List.of(row("table_name", "gov_info_record", "comment", "政府公开信息记录")));
        when(jdbcTemplate.queryForList(MetadataService.FIELD_SQL))
                .thenReturn(List.of(
                        row("table_name", "gov_info_record", "field_name", "category",
                                "field_type", "varchar", "business_meaning", "信息分类", "is_metric", 0),
                        row("table_name", "gov_info_record", "field_name", "doc_count",
                                "field_type", "int", "business_meaning", "文档数量", "is_metric", 1)));
        when(jdbcTemplate.queryForList(MetadataService.METRIC_SQL))
                .thenReturn(List.of(row("metric_name", "文档数", "formula", "COUNT(*)", "description", "信息文档总数")));

        String text = service.buildMetadataText();

        assertTrue(text.contains("gov_info_record"), "文本应含表名");
        assertTrue(text.contains("信息分类"), "文本应含字段业务含义");
        assertTrue(text.contains("COUNT(*)"), "文本应含指标 formula");
        assertTrue(text.contains("是否指标: 是"), "应标记指标字段");
        assertTrue(text.contains("政务公开数据集"), "文本应含数据集名");
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}