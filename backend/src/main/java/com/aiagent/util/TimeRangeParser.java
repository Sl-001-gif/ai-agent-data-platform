package com.aiagent.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从用户问题中提取时间范围（如「近3年」「最近30天」「过去7天」），供计划器填充 timeRange。 */
public final class TimeRangeParser {

    private static final Pattern RANGE_PATTERN =
            Pattern.compile("(近|最近|过去|近一年来)?\\s*(\\d+)\\s*个?\\s*(年|个月|月|周|星期|天|日|季度)");

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
        return "近" + matcher.group(2) + unit;
    }
}