package com.aiagent.service;

import com.aiagent.dto.ConnectionTestResult;
import com.aiagent.dto.DataSourceRequest;
import com.aiagent.entity.AiDataSource;
import com.aiagent.mapper.AiDataSourceMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

/** 数据源管理：CRUD 委托 + JDBC 连接测试（3 秒超时，失败返回原因，绝不抛错）。 */
@Service
public class DataSourceAdminService {

    private static final int MAX_ERROR_LENGTH = 200;

    private final AiDataSourceMapper dataSourceMapper;

    public DataSourceAdminService(AiDataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    public List<AiDataSource> list() {
        return dataSourceMapper.selectList();
    }

    public AiDataSource create(DataSourceRequest request, Long userId) {
        AiDataSource entity = new AiDataSource();
        apply(entity, request);
        entity.setCreateBy(userId);
        dataSourceMapper.insert(entity);
        return entity;
    }

    public void update(Long id, DataSourceRequest request) {
        AiDataSource existing = dataSourceMapper.selectById(id);
        if (existing == null) {
            throw new RuntimeException("数据源不存在");
        }
        apply(existing, request);
        dataSourceMapper.update(existing);
    }

    public void delete(Long id) {
        int rows = dataSourceMapper.deleteById(id);
        if (rows == 0) {
            throw new RuntimeException("数据源不存在");
        }
    }

    /** 连接测试：成功返回延迟，失败返回平台原因，绝不抛错。 */
    public ConnectionTestResult testConnection(DataSourceRequest request) {
        long start = System.currentTimeMillis();
        String url = buildJdbcUrl(request);
        try (Connection connection = openConnection(url, request.getUsername(), request.getPassword())) {
            return new ConnectionTestResult(true, "连接成功", System.currentTimeMillis() - start);
        } catch (SQLException e) {
            String message = e.getMessage();
            if (message != null && message.length() > MAX_ERROR_LENGTH) {
                message = message.substring(0, MAX_ERROR_LENGTH);
            }
            return new ConnectionTestResult(false, message, System.currentTimeMillis() - start);
        }
    }

    String buildJdbcUrl(DataSourceRequest request) {
        return "jdbc:mysql://" + request.getHost() + ":" + request.getPort() + "/" + request.getDatabaseName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
                + "&connectTimeout=3000&socketTimeout=3000";
    }

    Connection openConnection(String url, String username, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        return DriverManager.getConnection(url, props);
    }

    private static void apply(AiDataSource entity, DataSourceRequest request) {
        entity.setName(request.getName());
        entity.setDbType(request.getDbType() != null ? request.getDbType() : "MYSQL");
        entity.setHost(request.getHost());
        entity.setPort(request.getPort());
        entity.setDatabaseName(request.getDatabaseName());
        entity.setUsername(request.getUsername());
        entity.setPassword(request.getPassword());
        entity.setRemark(request.getRemark());
    }
}