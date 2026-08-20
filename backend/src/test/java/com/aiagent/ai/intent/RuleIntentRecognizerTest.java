package com.aiagent.ai.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleIntentRecognizerTest {

    private final RuleIntentRecognizer recognizer = new RuleIntentRecognizer();

    @Test
    void recognize_shouldReturnSalesTrend() {
        RecognizedIntent intent = recognizer.recognize("分析最近30天的销售趋势");
        assertEquals("SALES_TREND", intent.getIntentType());
        assertEquals("销售趋势", intent.getIntentName());
        assertTrue(intent.getConfidence() >= 0.5);
        assertFalse(intent.getMatchedKeywords().isEmpty());
    }

    @Test
    void recognize_shouldReturnUserProfile() {
        RecognizedIntent intent = recognizer.recognize("看看我们的用户画像");
        assertEquals("USER_PROFILE", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldReturnComparison() {
        RecognizedIntent intent = recognizer.recognize("对比华东和华南的销售额");
        assertEquals("COMPARISON", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldReturnRanking() {
        RecognizedIntent intent = recognizer.recognize("哪个商品卖得最好，排个Top10");
        assertEquals("RANKING", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldReturnStructure() {
        RecognizedIntent intent = recognizer.recognize("各品类销售占比");
        assertEquals("STRUCTURE", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldReturnRetention() {
        RecognizedIntent intent = recognizer.recognize("用户留存率怎么样");
        assertEquals("RETENTION", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldReturnAnomaly() {
        RecognizedIntent intent = recognizer.recognize("最近订单量下降的原因是什么");
        assertEquals("ANOMALY", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldFallbackToGeneralWhenNoKeyword() {
        RecognizedIntent intent = recognizer.recognize("随便看看数据");
        assertEquals("GENERAL", intent.getIntentType());
        assertEquals("通用探索", intent.getIntentName());
        assertTrue(intent.getConfidence() < 0.5);
        assertTrue(intent.getMatchedKeywords().isEmpty());
    }

    @Test
    void recognize_shouldFallbackToGeneralWhenBlank() {
        RecognizedIntent intent = recognizer.recognize("   ");
        assertEquals("GENERAL", intent.getIntentType());
        assertTrue(intent.getConfidence() < 0.5);
    }

    @Test
    void recognize_shouldFallbackToGeneralWhenNull() {
        RecognizedIntent intent = recognizer.recognize(null);
        assertEquals("GENERAL", intent.getIntentType());
    }

    @Test
    void recognize_shouldReturnRankingForTop10CaseInsensitive() {
        RecognizedIntent intent = recognizer.recognize("Top10 商品");
        assertEquals("RANKING", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldReturnAnomalyForUserChurnReason() {
        RecognizedIntent intent = recognizer.recognize("用户流失的原因是什么");
        assertEquals("ANOMALY", intent.getIntentType());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldNotReturnRankingForStop() {
        RecognizedIntent intent = recognizer.recognize("stop 分析");
        assertNotEquals("RANKING", intent.getIntentType());
    }
    @Test
    void recognize_shouldReturnStructureForRegionShare() {
        RecognizedIntent intent = recognizer.recognize("邵阳市不同地区经济占比");
        assertEquals("STRUCTURE", intent.getIntentType());
        assertEquals("占比结构", intent.getIntentName());
        assertTrue(intent.getConfidence() >= 0.5);
    }

    @Test
    void recognize_shouldReturnLowConfidenceForGarbageInput() {
        RecognizedIntent intent = recognizer.recognize("12saffg");
        assertEquals("GENERAL", intent.getIntentType());
        assertTrue(intent.getConfidence() < 0.35, "pure garbage low confidence");
    }
}