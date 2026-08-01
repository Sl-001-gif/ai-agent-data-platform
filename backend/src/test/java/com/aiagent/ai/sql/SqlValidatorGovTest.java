package com.aiagent.ai.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlValidatorGovTest {

    private final SqlValidator validator = new SqlValidator();

    @Test
    void shouldAcceptGovTableInWhitelist() {
        SqlValidator.ValidationResult result = validator.validate(
                "SELECT category, COUNT(*) AS doc_count FROM gov_info_record GROUP BY category", "GOV_INFO_RECORD");
        assertTrue(result.valid(), "gov_info_record 应在白名单内: " + result.errors());
    }

    @Test
    void shouldRejectUnsafeSqlEvenWithGovTable() {
        SqlValidator.ValidationResult result = validator.validate(
                "SELECT * FROM gov_info_record; DROP TABLE gov_info_record", "GOV_INFO_RECORD");
        assertFalse(result.valid());
    }
}