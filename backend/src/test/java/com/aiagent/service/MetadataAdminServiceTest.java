package com.aiagent.service;

import com.aiagent.entity.Dataset;
import com.aiagent.entity.DataCategory;
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

    // ---------- category ----------
    @Test
    void listCategories_shouldDelegate() {
        when(mapper.selectCategoryList()).thenReturn(List.of(new DataCategory()));
        assertEquals(1, service.listCategories().size());
        verify(mapper).selectCategoryList();
    }

    @Test
    void createCategory_shouldFillDefaultsAndInsert() {
        DataCategory category = new DataCategory();
        category.setName("测试分类");
        DataCategory saved = service.createCategory(category);
        assertEquals("#409eff", saved.getColor(), "color 默认 #409eff");
        assertEquals(0, saved.getSort(), "sort 默认 0");
        verify(mapper).insertCategory(category);
    }

    @Test
    void createCategory_shouldRejectBlankName() {
        assertThrows(RuntimeException.class, () -> service.createCategory(new DataCategory()));
        verify(mapper, never()).insertCategory(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createCategory_shouldRejectDuplicateName() {
        when(mapper.selectCategoryByName("政务数据")).thenReturn(new DataCategory());
        DataCategory category = new DataCategory();
        category.setName("政务数据");
        assertThrows(RuntimeException.class, () -> service.createCategory(category));
        verify(mapper, never()).insertCategory(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateCategory_shouldRejectWhenNotExists() {
        when(mapper.selectCategoryById(9L)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.updateCategory(9L, new DataCategory()));
    }

    @Test
    void updateCategory_shouldRejectDuplicateNameOfOtherCategory() {
        DataCategory other = new DataCategory();
        other.setId(2L);
        when(mapper.selectCategoryById(1L)).thenReturn(new DataCategory());
        when(mapper.selectCategoryByName("政务数据")).thenReturn(other);
        DataCategory category = new DataCategory();
        category.setName("政务数据");
        assertThrows(RuntimeException.class, () -> service.updateCategory(1L, category));
    }

    @Test
    void deleteCategory_shouldClearRefsAndDelete() {
        when(mapper.selectCategoryById(1L)).thenReturn(new DataCategory());
        when(mapper.deleteCategory(1L)).thenReturn(1);
        service.deleteCategory(1L);
        verify(mapper).clearCategoryRefs(1L);
        verify(mapper).deleteCategory(1L);
    }
}
