package com.aiagent.mapper;

import com.aiagent.entity.Dataset;
import com.aiagent.entity.MetricDefinition;
import com.aiagent.entity.TableField;
import com.aiagent.entity.TableSchema;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 数据元配置管理（数据集/数据表/字段语义/指标口径）CRUD。 */
@Mapper
public interface MetadataAdminMapper {

    int insertDataset(Dataset dataset);

    int updateDataset(Dataset dataset);

    int deleteDataset(Long id);

    Dataset selectDatasetById(Long id);

    List<Dataset> selectDatasetList();

    int insertTable(TableSchema table);

    int updateTable(TableSchema table);

    int deleteTable(Long id);

    TableSchema selectTableById(Long id);

    List<TableSchema> selectTableList();

    int insertField(TableField field);

    int updateField(TableField field);

    int deleteField(Long id);

    TableField selectFieldById(Long id);

    List<TableField> selectFieldList();

    int insertMetric(MetricDefinition metric);

    int updateMetric(MetricDefinition metric);

    int deleteMetric(Long id);

    MetricDefinition selectMetricById(Long id);

    List<MetricDefinition> selectMetricList();
}
