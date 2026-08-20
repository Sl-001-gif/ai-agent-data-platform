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

    @Test
    void shouldExtractChineseNumeralRange() {
        assertEquals("近5年", TimeRangeParser.extract("邵阳市近五年第一产业占比的变化趋势"));
        assertEquals("近3年", TimeRangeParser.extract("近三年经济变化"));
        assertEquals("近2月", TimeRangeParser.extract("过去两个月的情况"));
        assertEquals("近10年", TimeRangeParser.extract("近十年经济总量"));
    }
    @Test
    void shouldTreatFourDigitYearAsAbsolute() {
        assertEquals("2024年", TimeRangeParser.extract("2024年邵阳地区生产总值及增速"));
        assertEquals("2026年", TimeRangeParser.extract("2026年1-9月北塔区GDP"));
        assertEquals("近3年", TimeRangeParser.extract("近3年经济变化"));
    }
    @Test
    void shouldNotParseMonthEndAsDuration() {
        assertNull(TimeRangeParser.extract("邵阳市9月末存款余额是多少？"), "「9月末」是时点不是近9月窗口");
        assertEquals("2025年", TimeRangeParser.extract("2025年9月末邵阳市各项存款余额是多少？"), "带年份的月末保留绝对年份");
        assertEquals("近3月", TimeRangeParser.extract("近3个月存款余额"), "近N个月仍按窗口解析");
    }
}
