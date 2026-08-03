package com.aiagent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** L1 单测：时间范围提取。 */
class TimeRangeParserTest {

    @Test
    void shouldExtractYearRange() {
        assertEquals("近3年", TimeRangeParser.extract("近3年按月发文趋势"));
    }

    @Test
    void shouldExtractDayAndMonthRange() {
        assertEquals("近30天", TimeRangeParser.extract("分析最近30天的销售趋势"));
        assertEquals("近3月", TimeRangeParser.extract("过去3个月的订单量"));
        assertEquals("近7天", TimeRangeParser.extract("近7天数据"));
    }

    @Test
    void shouldReturnNullWhenNoRange() {
        assertNull(TimeRangeParser.extract("各单位发文量排名"));
        assertNull(TimeRangeParser.extract(null));
        assertNull(TimeRangeParser.extract("  "));
    }

    @Test
    void shouldHandleWeekAndQuarter() {
        assertEquals("近2周", TimeRangeParser.extract("近2周走势"));
        assertEquals("近1季度", TimeRangeParser.extract("最近1个季度的表现"));
    }
}