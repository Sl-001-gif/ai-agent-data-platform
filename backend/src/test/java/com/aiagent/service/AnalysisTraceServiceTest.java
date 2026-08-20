package com.aiagent.service;

import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisReportMapper;
import com.aiagent.mapper.AnalysisSessionMapper;
import com.aiagent.mapper.AnalysisStepMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单元测试：AnalysisTraceService 会话复用、多轮次判定与步骤落库。 */
class AnalysisTraceServiceTest {

    private AnalysisSessionMapper sessionMapper;
    private AnalysisStepMapper stepMapper;
    private AnalysisReportMapper reportMapper;
    private AnalysisTraceService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AnalysisSessionMapper.class);
        stepMapper = mock(AnalysisStepMapper.class);
        reportMapper = mock(AnalysisReportMapper.class);
        service = new AnalysisTraceService(sessionMapper, stepMapper, reportMapper, new ObjectMapper());
    }

    @Test
    void startOrReuse_shouldInsertNewSessionWhenSessionIdNull() {
        when(sessionMapper.insert(any(AnalysisSession.class))).thenAnswer(invocation -> {
            AnalysisSession session = invocation.getArgument(0);
            session.setId(100L);
            return 1;
        });

        AnalysisTraceService.StartResult result = service.startOrReuse(7L, null, "邵阳经济分析", "邵阳近3年经济变化", 3L, "邵阳近3年经济变化");

        assertNotNull(result.session().getId());
        assertEquals(100L, result.session().getId());
        assertEquals(1, result.roundNo());
        assertEquals(7L, result.session().getUserId());
        assertEquals(3L, result.session().getDatasetId());
        assertEquals("邵阳近3年经济变化", result.session().getAnalysisGoal());
        assertEquals("ACTIVE", result.session().getStatus());
        verify(sessionMapper).insert(any(AnalysisSession.class));
        verify(stepMapper, never()).deleteBySessionId(any());
    }

    @Test
    void startOrReuse_shouldAppendNewRoundWhenQuestionDiffers() {
        AnalysisSession existing = new AnalysisSession();
        existing.setId(5L);
        existing.setUserId(7L);
        existing.setAnalysisGoal("旧目标");
        when(sessionMapper.selectById(5L)).thenReturn(existing);
        when(stepMapper.selectBySessionId(5L)).thenReturn(List.of(roundStep(5L, 1, "INTENT", "\"邵阳GDP\"", "{}")));

        AnalysisTraceService.StartResult result = service.startOrReuse(7L, 5L, "新问题", "邵阳GDP增速", null, "邵阳GDP增速");

        assertEquals(5L, result.session().getId());
        assertEquals(2, result.roundNo());
        verify(stepMapper, never()).deleteBySessionIdAndRound(any(), any());
        verify(reportMapper, never()).deleteBySessionIdAndRound(any(), any());
        verify(sessionMapper).update(existing);
    }

    @Test
    void startOrReuse_shouldOverwriteRoundWhenSameQuestion() {
        AnalysisSession existing = new AnalysisSession();
        existing.setId(5L);
        existing.setUserId(7L);
        when(sessionMapper.selectById(5L)).thenReturn(existing);
        when(stepMapper.selectBySessionId(5L)).thenReturn(List.of(roundStep(5L, 2, "INTENT", "\"邵阳GDP\"", "{}")));

        AnalysisTraceService.StartResult result = service.startOrReuse(7L, 5L, "邵阳GDP", "邵阳GDP", null, " 邵阳GDP ");

        assertEquals(5L, result.session().getId());
        assertEquals(2, result.roundNo());
        verify(stepMapper).deleteBySessionIdAndRound(5L, 2);
        verify(reportMapper).deleteBySessionIdAndRound(5L, 2);
        verify(sessionMapper).update(existing);
    }

    @Test
    void startOrReuse_shouldThrowWhenSessionNotFound() {
        when(sessionMapper.selectById(5L)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.startOrReuse(7L, 5L, "标题", "目标", null, "问题"));

        assertTrue(ex.getMessage().contains("会话不存在"));
        verify(stepMapper, never()).deleteBySessionId(any());
        verify(sessionMapper, never()).update(any());
    }

    @Test
    void startOrReuse_shouldThrowWhenUserIdMismatch() {
        AnalysisSession existing = new AnalysisSession();
        existing.setId(5L);
        existing.setUserId(99L);
        when(sessionMapper.selectById(5L)).thenReturn(existing);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.startOrReuse(7L, 5L, "标题", "目标", null, "问题"));

        assertTrue(ex.getMessage().contains("无权访问"));
        verify(stepMapper, never()).deleteBySessionId(any());
        verify(sessionMapper, never()).update(any());
    }

    @Test
    void latestRound_shouldReturnMaxRound() {
        assertNull(service.latestRound(5L));
        when(stepMapper.selectBySessionId(5L)).thenReturn(List.of(
                roundStep(5L, 1, "INTENT", "{}", "{}"),
                roundStep(5L, 3, "EXECUTE", "{}", "{}")));
        assertEquals(3, service.latestRound(5L));
    }

    @Test
    void appendStep_shouldTruncateOutputOverLimit() {
        service.appendStep(5L, 1, 1, "INTENT", "in", "x".repeat(70000), "SUCCESS", null, 12L);

        ArgumentCaptor<AnalysisStep> captor = ArgumentCaptor.forClass(AnalysisStep.class);
        verify(stepMapper).insert(captor.capture());
        AnalysisStep step = captor.getValue();
        assertTrue(step.getOutputData().length() <= 60000);
        assertEquals(5L, step.getSessionId());
        assertEquals(1, step.getRoundNo());
        assertEquals("INTENT", step.getStepType());
        assertEquals("SUCCESS", step.getStatus());
        assertEquals(12L, step.getDurationMs());
    }

    @Test
    void appendStep_shouldKeepJsonValidWhenTruncatingLargeOutput() throws Exception {
        StringBuilder sb = new StringBuilder("{\"columns\":[\"d\"],\"rows\":[");
        for (int i = 0; i < 9000; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"d\":").append(i).append("}");
        }
        sb.append("],\"rowCount\":9000}");
        String big = sb.toString();
        assertTrue(big.length() > 60000);

        service.appendStep(5L, 2, 5, "EXECUTE", "sql", big, "SUCCESS", null, 12L);

        ArgumentCaptor<AnalysisStep> captor = ArgumentCaptor.forClass(AnalysisStep.class);
        verify(stepMapper).insert(captor.capture());
        String out = captor.getValue().getOutputData();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(out);
        assertTrue(node.get("rows").size() > 0);
        assertTrue(node.get("rows").size() < 9000);
    }

    private static AnalysisStep roundStep(Long sessionId, Integer roundNo, String type, String input, String output) {
        AnalysisStep step = new AnalysisStep();
        step.setId(sessionId * 100 + roundNo);
        step.setSessionId(sessionId);
        step.setRoundNo(roundNo);
        step.setStepType(type);
        step.setInputData(input);
        step.setOutputData(output);
        return step;
    }
}