package com.aiagent.ai.validate;

import com.aiagent.ai.executor.SqlExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L1 单测：数据合理性校验（重复键行 / 时间空值 / 数值量级异常 / 结果过大）。 */
class DataSanityCheckerTest {

    private final DataSanityChecker checker = new DataSanityChecker();

    private SqlExecutor.ExecutionResult result(List<Map<String, Object>> rows) {
        List<String> columns = rows.isEmpty() ? List.of()
                : new ArrayList<>(rows.get(0).keySet());
        return new SqlExecutor.ExecutionResult(columns, rows, rows.size());
    }

    private Map<String, Object> row(String period, int value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("period", period);
        map.put("value", value);
        return map;
    }

    private Map<String, Object> statRow(String period, String region, Object value, Object growth) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("period", period);
        map.put("region", region);
        map.put("value", value);
        map.put("growth_rate", growth);
        return map;
    }

    @Test
    void shouldPassOnCleanResult() {
        List<Map<String, Object>> rows = List.of(row("2024-01", 1), row("2024-02", 2));

        assertTrue(checker.check(result(rows)).isEmpty(), "干净结果不应有告警");
    }

    @Test
    void shouldWarnOnDuplicateRows() {
        List<Map<String, Object>> rows = List.of(row("2024-01", 1), row("2024-01", 1));

        List<String> warnings = checker.check(result(rows));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("重复"), warnings.toString());
    }

    @Test
    void shouldWarnOnDuplicateKeyMixedUnitsByMagnitude() {
        // 同键不同值（亿元 vs 万元 混用）：由量级检查兜底，不再误报「重复」（未筛 region 的多区县行数值不同属正常）
        List<Map<String, Object>> rows = List.of(
                statRow("2024年1-12月", "全市", 2926.2473, 5.08),
                statRow("2024年1-12月", "全市", 29262473.0, 5.08),
                statRow("2024年1-12月", "北塔区", 9.0, null),
                statRow("2024年1-12月", "北塔区", 712288.6955, 4.1));

        List<String> warnings = checker.check(result(rows));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("倍")), warnings.toString());
        assertFalse(warnings.stream().anyMatch(w -> w.contains("重复")), "不同值不应误报重复: " + warnings.toString());
    }

    @Test
    void shouldWarnOnlyOnExactDuplicateRows() {
        // 同键同值才是真重复；同名不同区县（值不同）不告警
        List<Map<String, Object>> rows = List.of(
                statRow("2025年1-9月", "全市", 1974.6101, 4.96),
                statRow("2025年1-9月", "全市", 1974.6101, 4.96),
                statRow("2025年1-9月", "北塔区", 48.5402, 5.2),
                statRow("2025年1-9月", "双清区", 46.7330, 5.5));

        List<String> warnings = checker.check(result(rows));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("重复")), warnings.toString());
    }

    @Test
    void shouldWarnOnMagnitudeRatio() {
        List<Map<String, Object>> rows = List.of(
                statRow("2024年1-12月", "全市", 2926.2473, 5.08),
                statRow("2024年1-12月", "北塔区", 29262473.0, 4.1));

        List<String> warnings = checker.check(result(rows));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("倍")), warnings.toString());
    }

    @Test
    void shouldWarnOnBlankTimeColumn() {
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("period", null);
        blank.put("value", 1);

        List<String> warnings = checker.check(result(List.of(blank)));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("空值"), warnings.toString());
    }

    @Test
    void shouldWarnOnLargeResult() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            rows.add(row("P" + i, i));
        }

        List<String> warnings = checker.check(result(rows));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("2001")), warnings.toString());
    }

    @Test
    void shouldIgnoreNullResult() {
        assertTrue(checker.check(null).isEmpty());
    }

    @Test
    void shouldDetectTimeColumns() {
        assertTrue(DataSanityChecker.isTimeColumnName("period"));
        assertTrue(DataSanityChecker.isTimeColumnName("publish_date"));
        assertTrue(DataSanityChecker.isTimeColumnName("期间"));
        assertFalse(DataSanityChecker.isTimeColumnName("region"));
    }
    @Test
    void shouldWarnOnEmptyRows() {
        List<String> warnings = checker.check(result(List.of()));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("0 行"), "空结果应输出数据质量告警: " + warnings.toString());
    }
}
