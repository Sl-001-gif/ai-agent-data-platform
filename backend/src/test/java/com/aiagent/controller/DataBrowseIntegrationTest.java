package com.aiagent.controller;

import com.aiagent.entity.AiDataSource;
import com.aiagent.mapper.AiDataSourceMapper;
import com.aiagent.service.DataBrowseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2：数据浏览真实库集成——自建数据源后查询政务表，结束后清理。 */
@SpringBootTest
class DataBrowseIntegrationTest {

    @Autowired
    private DataBrowseService dataBrowseService;

    @Autowired
    private AiDataSourceMapper dataSourceMapper;

    private Long dsId;

    @BeforeEach
    void setUp() {
        AiDataSource ds = new AiDataSource();
        ds.setName("data-browse-test");
        ds.setDbType("MYSQL");
        ds.setHost("localhost");
        ds.setPort(3306);
        ds.setDatabaseName("ai_agent_data");
        ds.setUsername("root");
        ds.setPassword("Admin@123456");
        dataSourceMapper.insert(ds);
        dsId = ds.getId();
    }

    @AfterEach
    void tearDown() {
        if (dsId != null) {
            dataSourceMapper.deleteById(dsId);
        }
    }

    @Test
    void listTables_shouldContainGovTable() {
        List<Map<String, String>> tables = dataBrowseService.listTables(dsId);
        assertTrue(tables.stream().anyMatch(t -> "gov_info_record".equals(t.get("tableName"))));
    }

    @Test
    void queryData_govTable_shouldReturnRows() {
        Map<String, Object> result = dataBrowseService.queryData(dsId, "gov_info_record", 1, 5);
        List<String> columns = (List<String>) result.get("columns");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        long total = (long) result.get("total");
        assertTrue(columns.contains("publish_date"));
        assertEquals(5, rows.size());
        assertTrue(total >= 3601); // ????????????????
    }

    @Test
    void queryData_illegalTableName_shouldThrow() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataBrowseService.queryData(dsId, "user; DROP", 1, 10));
        assertEquals("非法表名", ex.getMessage());
    }

    @Test
    void queryData_missingTable_shouldThrow() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataBrowseService.queryData(dsId, "not_exist_table_xyz", 1, 10));
        assertTrue(ex.getMessage().contains("表不存在"));
    }
}