package com.aiagent.ai.recommender;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L1 单测：推荐追问的意图模板、政务模板、去重过滤与空结果兜底。 */
class FollowupRecommenderTest {

    private final FollowupRecommender recommender = new FollowupRecommender();

    private SqlExecutor.ExecutionResult result() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("category", "手机");
        row.put("sales_amount", 100);
        return new SqlExecutor.ExecutionResult(List.of("category", "sales_amount"), List.of(row), 1);
    }

    private RecognizedIntent intent(String type) {
        return new RecognizedIntent(type, type, 0.9, List.of());
    }

    @Test
    void shouldReturnTwoToThreeDistinctFollowupsForAllIntents() {
        String[] types = {"SALES_TREND", "USER_PROFILE", "COMPARISON", "RANKING",
                "STRUCTURE", "RETENTION", "ANOMALY", "GENERAL"};
        for (String type : types) {
            List<String> list = recommender.recommend("分析" + type, intent(type), new AnalysisPlan(), result());
            assertTrue(list.size() >= 2 && list.size() <= 3, type + " 应返回 2~3 条，实际 " + list.size());
            assertEquals(list.size(), list.stream().distinct().count(), type + " 追问不应重复");
            assertTrue(list.stream().noneMatch(String::isBlank), type + " 追问不应为空");
        }
    }

    @Test
    void shouldUseGovTemplatesWhenKeywordMatched() {
        RecognizedIntent govIntent = new RecognizedIntent("SALES_TREND", "政务发布趋势", 0.9, List.of("政务公开"));
        List<String> list = recommender.recommend("政务信息发布趋势", govIntent, new AnalysisPlan(), result());
        assertTrue(list.stream().anyMatch(q -> q.contains("发文量")), "政务模板应含发文量类追问: " + list);
    }

    @Test
    void shouldUseGovTemplatesWhenTargetTableIsGov() {
        AnalysisPlan govPlan = new AnalysisPlan("GOV_INFO_RECORD", "政府信息公开记录", List.of("发文量"),
                List.of("公开单位"), "近30天", "bar", List.of());
        List<String> list = recommender.recommend("排名", intent("RANKING"), govPlan, result());
        assertTrue(list.stream().anyMatch(q -> q.contains("发文量")), "政务模板应含发文量类追问: " + list);
    }

    @Test
    void shouldFilterQuestionIdenticalToFollowup() {
        RecognizedIntent govIntent = new RecognizedIntent("SALES_TREND", "政务发布趋势", 0.9, List.of("政务公开"));
        List<String> list = recommender.recommend("按月发文量的趋势如何变化？", govIntent, new AnalysisPlan(), result());
        assertFalse(list.contains("按月发文量的趋势如何变化？"), "与原文相同的追问应被过滤");
    }

    @Test
    void shouldReturnSupplementQuestionsWhenRowsEmpty() {
        SqlExecutor.ExecutionResult empty = new SqlExecutor.ExecutionResult(List.of("a"), List.of(), 0);
        List<String> list = recommender.recommend("分析整体情况", intent("GENERAL"), new AnalysisPlan(), empty);
        assertEquals(3, list.size());
        assertTrue(list.stream().anyMatch(q -> q.contains("扩大时间范围")), "空结果应返回数据补充类追问");
    }
}