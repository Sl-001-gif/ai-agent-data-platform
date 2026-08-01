package com.aiagent.ai.executor;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL 受控执行器：10 秒超时、最多 500 行，供 EXECUTE 步骤使用。 */
@Component
public class SqlExecutor {

    /** 查询结果：列名 + 行数据 + 行数。 */
    public record ExecutionResult(List<String> columns, List<Map<String, Object>> rows, int rowCount) {
    }

    /** SQL 执行失败异常。 */
    public static class SqlExecutionException extends RuntimeException {
        public SqlExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public SqlExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 执行受控查询并返回列与行数据。 */
    public ExecutionResult execute(String sql) {
        return jdbcTemplate.execute((ConnectionCallback<ExecutionResult>) con -> executeQuery(con, sql));
    }

    /** 包私有静态方法：便于单元测试直接验证 JDBC 交互。 */
    static ExecutionResult executeQuery(Connection con, String sql) {
        try (Statement stmt = con.createStatement()) {
            stmt.setQueryTimeout(10);
            stmt.setMaxRows(500);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new ExecutionResult(columns, rows, rows.size());
            }
        } catch (SQLException e) {
            throw new SqlExecutionException("SQL 执行失败: " + e.getMessage(), e);
        }
    }
}