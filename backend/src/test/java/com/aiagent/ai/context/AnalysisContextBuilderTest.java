package com.aiagent.ai.context;

import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisStepMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L1 单元测试：AnalysisContextBuilder 会话历史上下文（最近 N 轮问题+结论）。 */
class AnalysisContextBuilderTest {

    private AnalysisStepMapper stepMapper;
    private AnalysisContextBuilder builder;

    @BeforeEach
    void setUp() {
        stepMapper = mock(AnalysisStepMapper.class);
        builder = new AnalysisContextBuilder(stepMapper, new ObjectMapper());
    }

    @Test
    void shouldReturnEmptyWhenNoSteps() {
        when(stepMapper.selectBySessionId(5L)).thenReturn(List.of());
        assertEquals("", builder.buildHistoryContext(5L, 1, 3));
    }

    @Test
    void shouldSkipCurrentRoundAndIncludePreviousRounds() {
        when(stepMapper.selectBySessionId(5L)).thenReturn(List.of(
                step(1, "INTENT", "\"2024年GDP\"", null),
                step(1, "INTERPRET", null, "{\"interpretation\":{\"text\":\"GDP稳步增长\"}}"),
                step(2, "INTENT", "\"各区县排名\"", null),
                step(2, "INTERPRET", null, "{\"interpretation\":{\"text\":\"邵东领先\"}}"),
                step(3, "INTENT", "\"增速对比\"", null)));

        String ctx = builder.buildHistoryContext(5L, 3, 3);

        assertTrue(ctx.contains("第1轮分析"));
        assertTrue(ctx.contains("2024年GDP"));
        assertTrue(ctx.contains("GDP稳步增长"));
        assertTrue(ctx.contains("第2轮分析"));
        assertTrue(ctx.contains("各区县排名"));
        assertTrue(ctx.contains("邵东领先"));
        assertFalse(ctx.contains("第3轮分析"));
        assertFalse(ctx.contains("增速对比"));
    }

    @Test
    void shouldLimitToMostRecentRounds() {
        when(stepMapper.selectBySessionId(5L)).thenReturn(List.of(
                step(1, "INTENT", "\"问题一\"", null),
                step(2, "INTENT", "\"问题二\"", null),
                step(3, "INTENT", "\"问题三\"", null)));

        String ctx = builder.buildHistoryContext(5L, 4, 1);

        assertFalse(ctx.contains("问题一"));
        assertFalse(ctx.contains("问题二"));
        assertTrue(ctx.contains("问题三"));
        assertTrue(ctx.contains("【会话历史分析"));
    }

    private static AnalysisStep step(Integer roundNo, String type, String input, String output) {
        AnalysisStep s = new AnalysisStep();
        s.setRoundNo(roundNo);
        s.setStepType(type);
        s.setInputData(input);
        s.setOutputData(output);
        return s;
    }
}