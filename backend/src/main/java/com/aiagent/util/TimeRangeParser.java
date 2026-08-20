package com.aiagent.util;

import java.util.regex.Matcher;
import java.util.Map;
import java.util.regex.Pattern;

/** 从用户问题中提取时间范围（如「近3年」「最近30天」「过去7天」），供计划器填充 timeRange。 */
public final class TimeRangeParser {

    private static final Map<String, Integer> CN_NUM = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("两", 2), Map.entry("三", 3),
            Map.entry("四", 4), Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7),
            Map.entry("八", 8), Map.entry("九", 9), Map.entry("十", 10));

    private static final Pattern RANGE_PATTERN =
            Pattern.compile("(近|最近|过去|近一年来)?\\s*([0-9一二三四五六七八九十两]+)\\s*个?\\s*(年|个月(?!末)|月(?!末)|周|星期|天|日|季度)");

    private TimeRangeParser() {
    }

    /** 提取首个时间范围并规范为「近N单位」；无匹配返回 null。 */
    public static String extract(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = RANGE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String unit = switch (matcher.group(3)) {
            case "年" -> "年";
            case "个月", "月" -> "月";
            case "周", "星期" -> "周";
            case "天", "日" -> "天";
            case "季度" -> "季度";
            default -> "天";
        };
        int number = toNumber(matcher.group(2));
        if (number <= 0) {
            return null;
        }
        // 4 位年份（如「2024年」）视为绝对年份，不按「近N年」窗口解析，避免生成 year >= MAX-2024+1 这类错乱过滤
        if ("年".equals(unit) && number >= 1000 && number <= 2999) {
            return number + "年";
        }
        return "近" + number + unit;
    }

    /** 中文数字（一~十/两）转阿拉伯数字；无法识别返回 0。 */
    private static int toNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw);
        }
        Integer direct = CN_NUM.get(raw);
        if (direct != null) {
            return direct;
        }
        if (raw.length() == 2 && raw.charAt(1) == '十') {
            Integer tens = CN_NUM.get(String.valueOf(raw.charAt(0)));
            return tens == null ? 0 : tens * 10;
        }
        if (raw.length() == 2 && raw.charAt(0) == '十') {
            Integer ones = CN_NUM.get(String.valueOf(raw.charAt(1)));
            return ones == null ? 10 : 10 + ones;
        }
        return 0;
    }
}
