package com.aiagent.service;

import com.aiagent.mapper.AiDataSourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** L1：数据浏览边界——非法表名/数据源缺失在连库前拦截。 */
class DataBrowseServiceTest {

    private AiDataSourceMapper mapper;
    private DataBrowseService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(AiDataSourceMapper.class);
        service = new DataBrowseService(mapper);
    }

    @Test
    void queryData_illegalTableName_shouldReject() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.queryData(1L, "user; DROP TABLE", 1, 10));
        assertEquals("非法表名", ex.getMessage());
    }

    @Test
    void queryData_nullTableName_shouldReject() {
        assertThrows(RuntimeException.class, () -> service.queryData(1L, null, 1, 10));
    }

    @Test
    void queryData_missingDataSource_shouldReject() {
        Mockito.when(mapper.selectById(999L)).thenReturn(null);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.queryData(999L, "gov_info_record", 1, 10));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void listTables_missingDataSource_shouldReject() {
        Mockito.when(mapper.selectById(999L)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.listTables(999L));
    }
}