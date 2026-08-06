package com.aiagent.service;

import com.aiagent.entity.AiDataSource;
import com.aiagent.mapper.AiDataSourceMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** 数据浏览：选中数据源后只读查询表列表与分页数据（表名白名单 + LIMIT 封顶，防注入）。 */
@Service
public class DataBrowseService {

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ERROR_LENGTH = 200;

    private final AiDataSourceMapper dataSourceMapper;

    public DataBrowseService(AiDataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    public List<Map<String, String>> listTables(Long dataSourceId) {
        AiDataSource ds = requireDataSource(dataSourceId);
        List<Map<String, String>> tables = new ArrayList<>();
        try (Connection conn = openConnection(ds);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name")) {
            ps.setString(1, ds.getDatabaseName());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("tableName", rs.getString("table_name"));
                    item.put("tableComment", rs.getString("table_comment"));
                    tables.add(item);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询表列表失败: " + safeError(e));
        }
        return tables;
    }

    public Map<String, Object> queryData(Long dataSourceId, String tableName, int page, int pageSize) {
        String safe = validateTableName(tableName);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = Math.max(page - 1, 0) * size;
        AiDataSource ds = requireDataSource(dataSourceId);

        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        try (Connection conn = openConnection(ds)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
                ps.setString(1, ds.getDatabaseName());
                ps.setString(2, safe);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        columns.add(rs.getString(1));
                    }
                }
            }
            if (columns.isEmpty()) {
                throw new RuntimeException("表不存在或没有可读列: " + safe);
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `" + safe + "`")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM `" + safe + "` LIMIT ? OFFSET ?")) {
                ps.setInt(1, size);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询数据失败: " + safeError(e));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("total", total);
        return result;
    }

    private String validateTableName(String tableName) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new RuntimeException("非法表名");
        }
        return tableName;
    }

    private AiDataSource requireDataSource(Long id) {
        AiDataSource ds = dataSourceMapper.selectById(id);
        if (ds == null) {
            throw new RuntimeException("数据源不存在");
        }
        return ds;
    }

    private Connection openConnection(AiDataSource ds) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", ds.getUsername());
        props.setProperty("password", ds.getPassword());
        return DriverManager.getConnection(buildJdbcUrl(ds), props);
    }

    private String buildJdbcUrl(AiDataSource ds) {
        return "jdbc:mysql://" + ds.getHost() + ":" + ds.getPort() + "/" + ds.getDatabaseName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
                + "&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true";
    }

    private String safeError(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.toString() : (m.length() > MAX_ERROR_LENGTH ? m.substring(0, MAX_ERROR_LENGTH) : m);
    }
}