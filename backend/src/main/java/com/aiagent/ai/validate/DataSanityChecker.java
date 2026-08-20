package com.aiagent.ai.validate;

import com.aiagent.ai.executor.SqlExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据合理性校验：对执行结果做重复键行 / 时间维度空值 / 数值量级异常 / 结果过大检查，
 * 输出告警文案列表（空 = 通过），供 EXECUTE 后 DATA_CHECK 步骤与前端提示使用。
 * 告警不阻断执行，仅提示业务口径风险（如指标单位/排名混用）。
 */
@Component
public class DataSanityChecker {

    private static final int MAX_ROWS_WARN = 2000;
    /** 数值列量级差异超过该倍数即告警（如 亿元 vs 万元 = 10000 倍）。 */
    private static final long MAGNITUDE_RATIO_WARN = 5000L;
    private static final Pattern TIME_COLUMN = Pattern.compile("(?i)(date|time|period|月份|期间|年份|日期|时间)");
    private static final Pattern METRIC_COLUMN = Pattern.compile(
            "(?i)(value|数值|金额|总额|amount|count|cnt|数量|qty|sales|ratio|占比|rate|增速|growth|排名|名次)");

    /** 数据合理性检查：返回告警列表（空 = 通过）。 */
    public List<String> check(SqlExecutor.ExecutionResult result) {
        List<String> warnings = new ArrayList<>();
        if (result == null) {
            return warnings;
        }
        if (result.rows() == null || result.rows().isEmpty()) {
            warnings.add("查询结果为空（0 行）：当前指标/时间/区域条件无数据，可能统计期间尚未发布或指标口径不匹配");
            return warnings;
        }
        List<String> columns = result.columns() == null ? List.of() : result.columns();
        List<Map<String, Object>> rows = result.rows();

        String duplicateKey = findDuplicateKeyWarning(columns, rows);
        if (duplicateKey != null) {
            warnings.add(duplicateKey);
        }
        String timeColumn = firstTimeColumn(columns);
        if (timeColumn != null) {
            int blank = countBlank(rows, timeColumn);
            if (blank > 0) {
                warnings.add("时间维度「" + timeColumn + "」存在 " + blank + " 行空值，可能缺失部分期间");
            }
        }
        String magnitude = findMagnitudeWarning(columns, rows);
        if (magnitude != null) {
            warnings.add(magnitude);
        }
        // 增速列全空：提示该指标无增速数据（图中为规模值），避免「增速排名」被误读为增速
        if (columns.contains("growth_rate")) {
            boolean anyGrowth = rows.stream()
                    .anyMatch(r -> r.get("growth_rate") != null && !String.valueOf(r.get("growth_rate")).isBlank());
            if (!anyGrowth) {
                warnings.add("该指标暂无增速数据（growth_rate 全空），图中展示的为规模绝对值而非增速");
            }
        }
        if (rows.size() > MAX_ROWS_WARN) {
            warnings.add("结果共 " + rows.size() + " 行，建议缩小时间范围或增加筛选条件");
        }
        return warnings;
    }

    /**
     * 重复键检测：以非数值列为键分组，仅当同键行中数值列完全相同时判定为真重复
     * （未 SELECT region 的多区县行数值不同，不误报）。返回告警文案，无则返回 null。
     */
    static String findDuplicateKeyWarning(List<String> columns, List<Map<String, Object>> rows) {
        List<String> keyColumns = new ArrayList<>();
        List<String> metricColumns = new ArrayList<>();
        for (String column : columns) {
            if (isMetricColumnName(column)) {
                metricColumns.add(column);
            } else {
                keyColumns.add(column);
            }
        }
        if (keyColumns.isEmpty()) {
            return null;
        }
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            groups.computeIfAbsent(keyOf(row, keyColumns), k -> new ArrayList<>()).add(row);
        }
        int extra = 0;
        String example = null;
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            if (group.size() <= 1) {
                continue;
            }
            int dupCount = 0;
            for (int i = 0; i < group.size(); i++) {
                boolean dup = false;
                for (int j = 0; j < i; j++) {
                    if (sameMetricValues(group.get(i), group.get(j), metricColumns)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    dupCount++;
                }
            }
            if (dupCount > 0) {
                extra += dupCount;
                if (example == null) {
                    example = entry.getKey().trim().replace("\u0001", ", ");
                }
            }
        }
        if (extra == 0) {
            return null;
        }
        return "同一维度组合（" + String.join(", ", keyColumns) + "）存在 " + extra + " 行完全重复数据（如 " + example
                + "），疑似重复录入或口径混用，建议精确过滤指标后重查";
    }

    /** 两行在全部数值列上的取值是否完全一致（字符串级比较）。 */
    private static boolean sameMetricValues(Map<String, Object> a, Map<String, Object> b, List<String> metricColumns) {
        for (String column : metricColumns) {
            Object va = a.get(column);
            Object vb = b.get(column);
            if (va == null && vb == null) {
                continue;
            }
            if (va == null || vb == null || !String.valueOf(va).equals(String.valueOf(vb))) {
                return false;
            }
        }
        return true;
    }

    /** 数值量级异常检测：某数值列最大值/最小值（正值）差异超过阈值即告警。 */
    static String findMagnitudeWarning(List<String> columns, List<Map<String, Object>> rows) {
        for (String column : columns) {
            if (!isMetricColumnName(column)) {
                continue;
            }
            double min = Double.MAX_VALUE;
            double max = 0;
            for (Map<String, Object> row : rows) {
                Double numeric = toNumeric(row.get(column));
                if (numeric == null) {
                    continue;
                }
                if (numeric > 0 && numeric < min) {
                    min = numeric;
                }
                if (numeric > max) {
                    max = numeric;
                }
            }
            if (min == Double.MAX_VALUE || max <= 0 || max / min < MAGNITUDE_RATIO_WARN) {
                continue;
            }
            long ratio = Math.round(max / min);
            return "数值列「" + column + "」最大值与最小值相差约 " + ratio
                    + " 倍，疑似亿元/万元或排名与绝对值混用，建议统一口径后查看";
        }
        return null;
    }

    /** 按非数值列拼接键值。 */
    private static String keyOf(Map<String, Object> row, List<String> keyColumns) {
        StringBuilder sb = new StringBuilder();
        for (String column : keyColumns) {
            sb.append(row.get(column) == null ? "\u0000" : row.get(column)).append('\u0001');
        }
        return sb.toString();
    }

    private static Double toNumeric(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 首个时间类列名（date/time/period/期间/月份…）。 */
    static String firstTimeColumn(List<String> columns) {
        for (String column : columns) {
            if (isTimeColumnName(column)) {
                return column;
            }
        }
        return null;
    }

    static int countBlank(List<Map<String, Object>> rows, String column) {
        int blank = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            if (value == null || String.valueOf(value).isBlank()) {
                blank++;
            }
        }
        return blank;
    }

    static boolean isTimeColumnName(String name) {
        return name != null && TIME_COLUMN.matcher(name.toLowerCase(Locale.ROOT)).find();
    }

    static boolean isMetricColumnName(String name) {
        return name != null && METRIC_COLUMN.matcher(name.toLowerCase(Locale.ROOT)).find();
    }
}
