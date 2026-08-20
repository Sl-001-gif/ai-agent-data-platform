package com.aiagent.ai.recommender;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.prompt.PromptLoader;
import com.aiagent.mapper.AiConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void shouldUseNormalTemplatesWhenKeywordOnlyNoGovTable() {
        RecognizedIntent govIntent = new RecognizedIntent("SALES_TREND", "政务发布趋势", 0.9, List.of("政务公开"));
        AnalysisPlan statPlan = new AnalysisPlan("stat_monthly", "统计月报", List.of("地区生产总值（GDP）"),
                List.of("期间", "区县"), "近3年", "line", List.of());
        List<String> list = recommender.recommend("邵阳经济趋势", govIntent, statPlan, result());
        assertFalse(list.stream().anyMatch(q -> q.contains("发文量")), "非政务表不应推发文量类追问: " + list);
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
    @Test
    void shouldUseLlmFollowupsWhenConfigured() {
        LlmClient llmClient = mock(LlmClient.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn(
                "[\"各区县 GDP 的排名变化趋势如何？\",\"近三年哪类产业增长最快？\"]");
        FollowupRecommender llmRecommender = new FollowupRecommender(llmClient, modelRouter, new PromptLoader(mock(AiConfigMapper.class)));

        List<String> list = llmRecommender.recommend("邵阳GDP趋势", intent("STAT_TREND"), new AnalysisPlan(), result());

        assertTrue(list.contains("各区县 GDP 的排名变化趋势如何？"), list.toString());
        assertTrue(list.contains("近三年哪类产业增长最快？"), list.toString());
    }

    @Test
    void shouldFallbackToRuleWhenLlmOutputInvalid() {
        LlmClient llmClient = mock(LlmClient.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenReturn("不是 JSON");
        FollowupRecommender llmRecommender = new FollowupRecommender(llmClient, modelRouter, new PromptLoader(mock(AiConfigMapper.class)));

        List<String> list = llmRecommender.recommend("分析", intent("SALES_TREND"), new AnalysisPlan(), result());

        assertTrue(list.size() >= 2, "LLM 输出非法应回退规则: " + list);
    }

    @Test
    void shouldFallbackToRuleWhenLlmThrows() {
        LlmClient llmClient = mock(LlmClient.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), any())).thenThrow(new RuntimeException("LLM 失败"));
        FollowupRecommender llmRecommender = new FollowupRecommender(llmClient, modelRouter, new PromptLoader(mock(AiConfigMapper.class)));

        List<String> list = llmRecommender.recommend("分析", intent("RANKING"), new AnalysisPlan(), result());

        assertTrue(list.size() >= 2, "LLM 异常应回退规则: " + list);
    }
}