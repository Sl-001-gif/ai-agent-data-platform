package com.aiagent.service;

import com.aiagent.entity.Dataset;
import com.aiagent.entity.MetricDefinition;
import com.aiagent.entity.TableField;
import com.aiagent.entity.TableSchema;
import com.aiagent.mapper.MetadataAdminMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单测：数据元配置 CRUD 委托、必填校验与默认值填充。 */
class MetadataAdminServiceTest {

    private final MetadataAdminMapper mapper = mock(MetadataAdminMapper.class);
    private final MetadataAdminService service = new MetadataAdminService(mapper);

    // ---------- dataset ----------
    @Test
    void listDatasets_shouldDelegate() {
        when(mapper.selectDatasetList()).thenReturn(List.of(new Dataset()));
        assertEquals(1, service.listDatasets().size());
        verify(mapper).selectDatasetList();
    }

    @Test
    void createDataset_shouldFillDefaultsAndInsert() {
        Dataset dataset = new Dataset();
        dataset.setName("测试数据集");
        Dataset saved = service.createDataset(dataset);
        assertEquals(1, saved.getStatus(), "status 默认应启用");
        assertEquals(0, saved.getSort(), "sort 默认应为 0");
        verify(mapper).insertDataset(dataset);
    }

    @Test
    void createDataset_shouldRejectBlankName() {
        Dataset dataset = new Dataset();
        assertThrows(RuntimeException.class, () -> service.createDataset(dataset));
        verify(mapper, never()).insertDataset(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateDataset_shouldSetIdAndUpdate() {
        when(mapper.selectDatasetById(1L)).thenReturn(new Dataset());
        Dataset dataset = new Dataset();
        service.updateDataset(1L, dataset);
        assertEquals(1L, dataset.getId());
        verify(mapper).updateDataset(dataset);
    }

    @Test
    void updateDataset_shouldRejectWhenNotExists() {
        when(mapper.selectDatasetById(1L)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.updateDataset(1L, new Dataset()));
    }

    @Test
    void deleteDataset_shouldThrowWhenNotExists() {
        when(mapper.deleteDataset(9L)).thenReturn(0);
        assertThrows(RuntimeException.class, () -> service.deleteDataset(9L));
    }

    // ---------- table ----------
    @Test
    void listTables_shouldDelegate() {
        when(mapper.selectTableList()).thenReturn(List.of(new TableSchema()));
        assertEquals(1, service.listTables().size());
    }

    @Test
    void createTable_shouldRejectMissingDataset() {
        assertThrows(RuntimeException.class, () -> service.createTable(new TableSchema()));
    }

    @Test
    void createTable_shouldRejectBlankTableName() {
        TableSchema table = new TableSchema();
        table.setDatasetId(1L);
        assertThrows(RuntimeException.class, () -> service.createTable(table));
    }

    @Test
    void createTable_shouldFillDefaultsAndInsert() {
        TableSchema table = new TableSchema();
        table.setDatasetId(1L);
        table.setTableName("gov_info_record");
        TableSchema saved = service.createTable(table);
        assertEquals(1, saved.getStatus(), "status 默认应启用");
        verify(mapper).insertTable(table);
    }

    @Test
    void deleteTable_shouldThrowWhenNotExists() {
        when(mapper.deleteTable(9L)).thenReturn(0);
        assertThrows(RuntimeException.class, () -> service.deleteTable(9L));
    }

    // ---------- field ----------
    @Test
    void createField_shouldRejectMissingTable() {
        assertThrows(RuntimeException.class, () -> service.createField(new TableField()));
    }

    @Test
    void createField_shouldFillDefaultsAndInsert() {
        TableField field = new TableField();
        field.setTableId(1L);
        field.setFieldName("publish_date");
        TableField saved = service.createField(field);
        assertEquals(1, saved.getCanQuery(), "canQuery 默认应为 1");
        assertEquals(0, saved.getCanAgg(), "canAgg 默认应为 0");
        assertEquals(0, saved.getSort(), "sort 默认应为 0");
        verify(mapper).insertField(field);
    }

    @Test
    void updateField_shouldRejectWhenNotExists() {
        when(mapper.selectFieldById(2L)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.updateField(2L, new TableField()));
    }

    // ---------- metric ----------
    @Test
    void createMetric_shouldRejectBlankName() {
        assertThrows(RuntimeException.class, () -> service.createMetric(new MetricDefinition()));
    }

    @Test
    void createMetric_shouldFillDefaultsAndInsert() {
        MetricDefinition metric = new MetricDefinition();
        metric.setName("发文量");
        MetricDefinition saved = service.createMetric(metric);
        assertEquals(1, saved.getStatus(), "status 默认应启用");
        assertEquals(0, saved.getSort(), "sort 默认应为 0");
        verify(mapper).insertMetric(metric);
    }

    @Test
    void deleteMetric_shouldThrowWhenNotExists() {
        when(mapper.deleteMetric(9L)).thenReturn(0);
        assertThrows(RuntimeException.class, () -> service.deleteMetric(9L));
    }

}
