package com.aiagent.ai.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleIntentRecognizerGovTest {

    private final RuleIntentRecognizer recognizer = new RuleIntentRecognizer();

    @Test
    void shouldMarkGovKeywordForGovRankingText() {
        RecognizedIntent intent = recognizer.recognize("政务信息公开类目排名");
        assertEquals("RANKING", intent.getIntentType());
        assertTrue(intent.getMatchedKeywords().contains("政务公开"), "matchedKeywords 应含政务公开标记");
    }

    @Test
    void shouldNotMarkGovForNormalText() {
        RecognizedIntent intent = recognizer.recognize("商品类别排名");
        assertEquals("RANKING", intent.getIntentType());
        assertFalse(intent.getMatchedKeywords().contains("政务公开"));
    }

    @Test
    void shouldMarkGovForGeneralFallback() {
        RecognizedIntent intent = recognizer.recognize("看一下政务公开数据");
        assertEquals("GENERAL", intent.getIntentType());
        assertTrue(intent.getMatchedKeywords().contains("政务公开"));
    }
}