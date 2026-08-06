package com.aiagent.service;

import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisSessionMapper;
import com.aiagent.mapper.AnalysisStepMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单元测试：AnalysisTraceService 会话复用与步骤落库。 */
class AnalysisTraceServiceTest {

    private AnalysisSessionMapper sessionMapper;
    private AnalysisStepMapper stepMapper;
    private AnalysisTraceService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AnalysisSessionMapper.class);
        stepMapper = mock(AnalysisStepMapper.class);
        service = new AnalysisTraceService(sessionMapper, stepMapper);
    }

    @Test
    void startOrReuse_shouldInsertNewSessionWhenSessionIdNull() {
        when(sessionMapper.insert(any(AnalysisSession.class))).thenAnswer(invocation -> {
            AnalysisSession session = invocation.getArgument(0);
            session.setId(100L);
            return 1;
        });

        AnalysisSession session = service.startOrReuse(7L, null, "分析销售趋势");

        assertNotNull(session.getId());
        assertEquals(100L, session.getId());
        assertEquals(7L, session.getUserId());
        assertEquals("ACTIVE", session.getStatus());
        verify(sessionMapper).insert(any(AnalysisSession.class));
        verify(stepMapper, never()).deleteBySessionId(any());
    }

    @Test
    void startOrReuse_shouldReuseAndCleanOldSteps() {
        AnalysisSession existing = new AnalysisSession();
        existing.setId(5L);
        existing.setUserId(7L);
        when(sessionMapper.selectById(5L)).thenReturn(existing);

        AnalysisSession session = service.startOrReuse(7L, 5L, "新标题");

        assertEquals(5L, session.getId());
        assertEquals("新标题", existing.getTitle());
        assertEquals("ACTIVE", existing.getStatus());
        verify(stepMapper).deleteBySessionId(5L);
        verify(sessionMapper).update(existing);
    }

    @Test
    void startOrReuse_shouldThrowWhenSessionNotFound() {
        when(sessionMapper.selectById(5L)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.startOrReuse(7L, 5L, "标题"));

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

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.startOrReuse(7L, 5L, "标题"));

        assertTrue(ex.getMessage().contains("无权访问"));
        verify(stepMapper, never()).deleteBySessionId(any());
        verify(sessionMapper, never()).update(any());
    }

    @Test
    void appendStep_shouldTruncateOutputOverLimit() {
        service.appendStep(5L, 1, "INTENT", "in", "x".repeat(70000), "SUCCESS", null, 12L);

        ArgumentCaptor<AnalysisStep> captor = ArgumentCaptor.forClass(AnalysisStep.class);
        verify(stepMapper).insert(captor.capture());
        AnalysisStep step = captor.getValue();
        assertTrue(step.getOutputData().length() <= 60000);
        assertEquals(5L, step.getSessionId());
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

        service.appendStep(5L, 5, "EXECUTE", "sql", big, "SUCCESS", null, 12L);

        ArgumentCaptor<AnalysisStep> captor = ArgumentCaptor.forClass(AnalysisStep.class);
        verify(stepMapper).insert(captor.capture());
        String out = captor.getValue().getOutputData();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(out);
        assertTrue(node.get("rows").size() > 0);
        assertTrue(node.get("rows").size() < 9000);
    }
}