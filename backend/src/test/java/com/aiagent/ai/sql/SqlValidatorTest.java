package com.aiagent.ai.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlValidatorTest {

    private final SqlValidator validator = new SqlValidator();

    private void assertPassed(String sql) {
        assertTrue(validator.validate(sql, null).valid(), "应通过: " + sql);
    }

    private void assertRejected(String sql) {
        assertFalse(validator.validate(sql, null).valid(), "应拒绝: " + sql);
    }

    @Test
    void shouldAcceptValidSelect() {
        assertPassed("SELECT order_date, SUM(order_count) FROM order_info GROUP BY order_date");
        assertPassed("SELECT * FROM user_info WHERE age_group = '18-24'");
    }

    @Test
    void shouldNotFlagCommentLikeTextInsideStringLiteral() {
        assertPassed("SELECT * FROM order_info WHERE category = '--drop' AND region = '华东'");
        assertPassed("SELECT * FROM user_info WHERE remark = \"a--b\" AND city = '上海'");
    }

    @Test
    void shouldRejectDangerousStatements() {
        for (String sql : List.of(
                "DROP TABLE order_info",
                "DELETE FROM order_info",
                "UPDATE order_info SET sales_amount = 0",
                "INSERT INTO order_info VALUES (1)",
                "ALTER TABLE order_info ADD COLUMN x INT",
                "TRUNCATE TABLE order_info",
                "CREATE TABLE evil (id INT)",
                "SELECT * FROM order_info INTO OUTFILE '/tmp/evil'")) {
            assertRejected(sql);
        }
    }

    @Test
    void shouldRejectSemicolonMultiStatementAndTrailing() {
        assertRejected("SELECT * FROM order_info; SELECT * FROM user_info");
        assertRejected("SELECT * FROM order_info;");
    }

    @Test
    void shouldRejectComments() {
        assertRejected("SELECT 1 -- comment");
        assertRejected("SELECT 1 # comment");
        assertRejected("SELECT 1 /* comment */");
        assertRejected("SELECT * FROM order_info /* hidden */");
    }

    @Test
    void shouldRejectSystemDatabases() {
        assertRejected("SELECT * FROM INFORMATION_SCHEMA.TABLES");
        assertRejected("SELECT * FROM mysql.user");
        assertRejected("SELECT * FROM performance_schema.events_statements_summary_by_digest");
        assertRejected("SELECT * FROM sys.schema_table_statistics");
    }

    @Test
    void shouldRejectDangerousFunctions() {
        for (String sql : List.of(
                "SELECT SLEEP(5)",
                "SELECT BENCHMARK(1000000, MD5('x'))",
                "SELECT LOAD_FILE('/etc/passwd')",
                "SELECT UPDATEXML(1, CONCAT(0x7e, 'x'), 1)",
                "SELECT EXTRACTVALUE(1, CONCAT(0x7e, 'x'))")) {
            assertRejected(sql);
        }
    }

    @Test
    void shouldRejectNonWhitelistedTablesAndJoinEvil() {
        assertRejected("SELECT * FROM evil_table");
        assertRejected("SELECT a.* FROM order_info o JOIN evil e ON o.id = e.id");
    }

    @Test
    void shouldRejectUnion() {
        assertRejected("SELECT * FROM order_info UNION SELECT * FROM user_info");
    }

    @Test
    void shouldRejectCaseAndCommentBypass() {
        assertRejected("sElEcT * FROM order_info UNION SELECT 1");
        assertRejected("UpDaTe order_info SET sales_amount = 1");
        assertRejected("select/**/ * from order_info");
    }

    @Test
    void shouldRejectNullBlankAndMissingFrom() {
        assertFalse(validator.validate(null, null).valid());
        assertRejected("");
        assertRejected("   ");
        assertRejected("SELECT 1");
    }
}