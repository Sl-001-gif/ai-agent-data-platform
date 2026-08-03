package com.aiagent.service;

import com.aiagent.entity.Dataset;
import com.aiagent.entity.MetricDefinition;
import com.aiagent.entity.TableField;
import com.aiagent.entity.TableSchema;
import com.aiagent.mapper.MetadataAdminMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/** 数据元配置管理：数据集/数据表/字段语义/指标口径 CRUD 委托。 */
@Service
public class MetadataAdminService {

    private final MetadataAdminMapper metadataAdminMapper;

    public MetadataAdminService(MetadataAdminMapper metadataAdminMapper) {
        this.metadataAdminMapper = metadataAdminMapper;
    }

    public List<Dataset> listDatasets() {
        return metadataAdminMapper.selectDatasetList();
    }

    public Dataset createDataset(Dataset dataset) {
        requireNotBlank(dataset.getName(), "数据集名称不能为空");
        defaultStatusSort(dataset.getStatus(), dataset.getSort(), dataset::setStatus, dataset::setSort);
        metadataAdminMapper.insertDataset(dataset);
        return dataset;
    }

    public void updateDataset(Long id, Dataset dataset) {
        requireExisting(metadataAdminMapper.selectDatasetById(id), "数据集不存在");
        dataset.setId(id);
        metadataAdminMapper.updateDataset(dataset);
    }

    public void deleteDataset(Long id) {
        requireRows(metadataAdminMapper.deleteDataset(id), "数据集不存在");
    }

    public List<TableSchema> listTables() {
        return metadataAdminMapper.selectTableList();
    }

    public TableSchema createTable(TableSchema table) {
        requireNotNull(table.getDatasetId(), "请选择所属数据集");
        requireNotBlank(table.getTableName(), "表名不能为空");
        defaultStatusSort(table.getStatus(), table.getSort(), table::setStatus, table::setSort);
        metadataAdminMapper.insertTable(table);
        return table;
    }

    public void updateTable(Long id, TableSchema table) {
        requireExisting(metadataAdminMapper.selectTableById(id), "数据表不存在");
        table.setId(id);
        metadataAdminMapper.updateTable(table);
    }

    public void deleteTable(Long id) {
        requireRows(metadataAdminMapper.deleteTable(id), "数据表不存在");
    }

    public List<TableField> listFields() {
        return metadataAdminMapper.selectFieldList();
    }

    public TableField createField(TableField field) {
        requireNotNull(field.getTableId(), "请选择所属表");
        requireNotBlank(field.getFieldName(), "字段名不能为空");
        defaultInt(field.getSort(), field::setSort);
        if (field.getCanQuery() == null) {
            field.setCanQuery(1);
        }
        defaultInt(field.getCanAgg(), field::setCanAgg);
        metadataAdminMapper.insertField(field);
        return field;
    }

    public void updateField(Long id, TableField field) {
        requireExisting(metadataAdminMapper.selectFieldById(id), "字段不存在");
        field.setId(id);
        metadataAdminMapper.updateField(field);
    }

    public void deleteField(Long id) {
        requireRows(metadataAdminMapper.deleteField(id), "字段不存在");
    }

    public List<MetricDefinition> listMetrics() {
        return metadataAdminMapper.selectMetricList();
    }

    public MetricDefinition createMetric(MetricDefinition metric) {
        requireNotBlank(metric.getName(), "指标名称不能为空");
        defaultStatusSort(metric.getStatus(), metric.getSort(), metric::setStatus, metric::setSort);
        metadataAdminMapper.insertMetric(metric);
        return metric;
    }

    public void updateMetric(Long id, MetricDefinition metric) {
        requireExisting(metadataAdminMapper.selectMetricById(id), "指标不存在");
        metric.setId(id);
        metadataAdminMapper.updateMetric(metric);
    }

    public void deleteMetric(Long id) {
        requireRows(metadataAdminMapper.deleteMetric(id), "指标不存在");
    }

    private static void requireExisting(Object existing, String message) {
        if (existing == null) {
            throw new RuntimeException(message);
        }
    }

    private static void requireRows(int rows, String message) {
        if (rows == 0) {
            throw new RuntimeException(message);
        }
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException(message);
        }
    }

    private static void requireNotNull(Object value, String message) {
        if (value == null) {
            throw new RuntimeException(message);
        }
    }

    private static void defaultInt(Integer value, Consumer<Integer> setter) {
        if (value == null) {
            setter.accept(0);
        }
    }

    private static void defaultStatusSort(Integer status, Integer sort,
                                          Consumer<Integer> setStatus, Consumer<Integer> setSort) {
        if (status == null) {
            setStatus.accept(1);
        }
        defaultInt(sort, setSort);
    }
}
