package com.aiagent.ai.executor;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单元测试：SqlExecutor.executeQuery 的 JDBC 交互与异常包装。 */
class SqlExecutorTest {

    @Test
    void executeQuery_shouldMapColumnsAndRowsAndApplyLimits() throws Exception {
        Connection con = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(con.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT 1")).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("order_date");
        when(meta.getColumnLabel(2)).thenReturn("sales_amount");
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(java.sql.Date.valueOf("2026-07-30"), java.sql.Date.valueOf("2026-07-29"));
        when(rs.getObject(2)).thenReturn(100.0, 200.0);

        SqlExecutor.ExecutionResult result = SqlExecutor.executeQuery(con, "SELECT 1");

        assertEquals(List.of("order_date", "sales_amount"), result.columns());
        assertEquals(2, result.rowCount());
        assertEquals(2, result.rows().size());
        assertEquals(100.0, result.rows().get(0).get("sales_amount"));
        assertEquals(java.sql.Date.valueOf("2026-07-29"), result.rows().get(1).get("order_date"));
        verify(stmt).setQueryTimeout(10);
        verify(stmt).setMaxRows(500);
        verify(stmt).executeQuery("SELECT 1");
    }

    @Test
    void executeQuery_shouldThrowSqlExecutionExceptionOnSqlException() throws Exception {
        Connection con = mock(Connection.class);
        Statement stmt = mock(Statement.class);

        when(con.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT 1")).thenThrow(new SQLException("bad sql"));

        SqlExecutor.SqlExecutionException ex = assertThrows(SqlExecutor.SqlExecutionException.class,
                () -> SqlExecutor.executeQuery(con, "SELECT 1"));
        assertTrue(ex.getMessage().contains("bad sql"));
    }
}