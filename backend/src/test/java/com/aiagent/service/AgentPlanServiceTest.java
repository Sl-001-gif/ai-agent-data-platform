package com.aiagent.service;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.IntentRecognizer;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlanner;
import com.aiagent.ai.interpreter.DataInterpreter;
import com.aiagent.ai.sql.SqlGenerator;
import com.aiagent.ai.sql.SqlValidator;
import com.aiagent.entity.AgentPlan;
import com.aiagent.mapper.AgentPlanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1：Agent 多步计划服务——规则拆解兜底 / 步骤 JSON 往返 / 越权与未执行校验。 */
class AgentPlanServiceTest {

    private AgentPlanMapper mapper;
    private LlmClient llmClient;
    private AgentPlanService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentPlanMapper.class);
        llmClient = mock(LlmClient.class);
        when(mapper.insert(any(AgentPlan.class))).thenAnswer(inv -> {
            AgentPlan p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        });
        service = new AgentPlanService(mapper, new ObjectMapper(), llmClient,
                mock(ModelRouter.class), mock(MetadataService.class),
                mock(IntentRecognizer.class), mock(AnalysisPlanner.class),
                mock(SqlGenerator.class), mock(SqlValidator.class),
                mock(SqlExecutor.class), mock(DataInterpreter.class));
    }

    @Test
    void decompose_shouldFallbackToRuleStepsWhenNoLlm() {
        when(llmClient.isConfigured()).thenReturn(false);
        AgentPlan plan = service.decompose(1L, "邵阳市经济复盘分析", 23L, null, "经济复盘计划");
        assertEquals("经济复盘计划", plan.getTitle());
        assertEquals("GENERATED", plan.getStatus());
        List<AgentPlanService.PlanStep> steps = service.parseSteps(plan.getStepsJson());
        assertEquals(3, steps.size());
        assertEquals("总量趋势", steps.get(0).name);
        assertEquals("line", steps.get(0).chartType);
        assertEquals("PENDING", steps.get(0).status);
        verify(mapper).insert(any(AgentPlan.class));
    }

    @Test
    void decompose_shouldRejectBlankGoal() {
        assertThrows(IllegalArgumentException.class, () -> service.decompose(1L, "  ", null, null, null));
    }

    @Test
    void parseSteps_shouldRoundTripJson() {
        when(llmClient.isConfigured()).thenReturn(false);
        AgentPlan plan = service.decompose(1L, "政务公开情况", null, null, "政务复盘");
        List<AgentPlanService.PlanStep> steps = service.parseSteps(plan.getStepsJson());
        assertEquals(3, steps.size());
        assertEquals("发文趋势", steps.get(0).name);
        assertTrue(plan.getStepsJson().contains("发文趋势"));
    }

    @Test
    void loadOwned_shouldRejectForeignUser() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.loadOwned(1L, 9L));
    }

    @Test
    void generateReport_shouldRejectWithoutSuccessfulExecution() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.generateReport(1L, 9L, null));
    }

    @Test
    void execute_shouldRejectForeignPlan() {
        when(mapper.selectById(anyLong())).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.execute(1L, 9L));
    }

    @Test
    void generateReport_shouldBuildNormalChartsAndBlockNoDataSteps() {
        String stepsJson = "[{\"stepNo\":1,\"name\":\"总量趋势\",\"status\":\"SUCCESS\",\"chartType\":\"line\",\"columns\":[\"period\",\"value\"],\"rows\":[{\"period\":\"2021\",\"value\":2461.5}]},"
                + "{\"stepNo\":2,\"name\":\"空结果\",\"status\":\"SUCCESS\",\"chartType\":\"bar\",\"columns\":[\"period\",\"value\"],\"rows\":[]}]";
        AgentPlan plan = new AgentPlan();
        plan.setId(1L);
        plan.setUserId(1L);
        plan.setStepsJson(stepsJson);
        when(mapper.selectById(1L)).thenReturn(plan);
        when(llmClient.isConfigured()).thenReturn(false);
        AgentPlan updated = service.generateReport(1L, 1L, null);
        List<Map<String, Object>> charts = service.parseCharts(updated.getReportChartsJson());
        assertEquals(2, charts.size());
        assertEquals("line", charts.get(0).get("chartType"));
        assertEquals(1, charts.get(0).get("stepNo"));
        assertEquals("总量趋势", charts.get(0).get("stepName"));
        assertEquals(2, charts.get(1).get("stepNo"));
        assertEquals("blocked", charts.get(1).get("dataStatus"));
        assertEquals("NO_DATA", charts.get(1).get("blockedReason"));
        assertTrue(((List<?>) charts.get(1).get("rows")).isEmpty());
    }

    @Test
    void buildCharts_shouldCarryStepNoAndNameOnReadPath() {
        String stepsJson = "[{\"stepNo\":1,\"name\":\"总量趋势\",\"status\":\"SUCCESS\",\"chartType\":\"line\",\"columns\":[\"period\",\"value\"],\"rows\":[{\"period\":\"2021\",\"value\":2461.5}]},"
                + "{\"stepNo\":2,\"name\":\"空结果\",\"status\":\"SUCCESS\",\"chartType\":\"bar\",\"columns\":[\"period\",\"value\"],\"rows\":[]}]";
        List<Map<String, Object>> charts = service.buildCharts(service.parseSteps(stepsJson));
        assertEquals(2, charts.size());
        assertEquals(1, charts.get(0).get("stepNo"));
        assertEquals("总量趋势", charts.get(0).get("stepName"));
        assertEquals(2, charts.get(1).get("stepNo"));
        assertEquals("blocked", charts.get(1).get("dataStatus"));
        assertEquals("NO_DATA", charts.get(1).get("blockedReason"));
    }

    @Test
    void buildCharts_shouldBlockWrongTableAndMetricMismatch() {
        String stepsJson = "[{\"stepNo\":1,\"name\":\"财政收支分析\",\"question\":\"查询各区县地方一般公共预算收入与金融机构存贷款余额\",\"status\":\"SUCCESS\",\"chartType\":\"bar\",\"targetTable\":\"order_info\",\"sql\":\"SELECT DATE(order_date) AS 日期, SUM(order_count) AS 订单量 FROM order_info\",\"rowCount\":3,\"columns\":[\"日期\",\"订单量\"],\"rows\":[{\"日期\":\"2026-08-16\",\"订单量\":60}]},"
                + "{\"stepNo\":2,\"name\":\"消费外贸分析\",\"question\":\"查询各区县社会消费品零售总额与进出口总额\",\"status\":\"SUCCESS\",\"chartType\":\"bar\",\"targetTable\":\"stat_monthly\",\"sql\":\"SELECT period, region, value FROM stat_monthly WHERE indicator_name = '分县（市、区）GDP'\",\"rowCount\":104,\"columns\":[\"period\",\"region\",\"value\"],\"rows\":[{\"period\":\"2023年\",\"region\":\"全市\",\"value\":2731.4}]},"
                + "{\"stepNo\":3,\"name\":\"总量趋势\",\"question\":\"查询全市GDP总量与增速\",\"status\":\"SUCCESS\",\"chartType\":\"line\",\"targetTable\":\"stat_monthly\",\"sql\":\"SELECT period, region, value, growth_rate FROM stat_monthly WHERE indicator_name = '分县（市、区）GDP'\",\"rowCount\":204,\"columns\":[\"period\",\"region\",\"value\",\"growth_rate\"],\"rows\":[{\"period\":\"2021年\",\"region\":\"全市\",\"value\":2461.5}]}]";
        List<Map<String, Object>> charts = service.buildCharts(service.parseSteps(stepsJson));
        assertEquals(3, charts.size());
        assertEquals("blocked", charts.get(0).get("dataStatus"));
        assertEquals("WRONG_TABLE", charts.get(0).get("blockedReason"));
        assertEquals(3, charts.get(0).get("count"));
        assertTrue(((List<?>) charts.get(0).get("rows")).isEmpty());
        assertEquals("blocked", charts.get(1).get("dataStatus"));
        assertEquals("QUERY_MISMATCH", charts.get(1).get("blockedReason"));
        assertEquals(104, charts.get(1).get("count"));
        assertFalse(charts.get(2).containsKey("dataStatus"));
        assertEquals(1, ((List<?>) charts.get(2).get("rows")).size());
    }

    @Test
    void parseCharts_shouldParseChartJson() {
        String json = "[{\"chartType\":\"line\",\"title\":\"总量趋势\",\"columns\":[\"period\",\"value\"],\"rows\":[{\"period\":\"2021\",\"value\":2461.5}]}]";
        List<Map<String, Object>> charts = service.parseCharts(json);
        assertEquals(1, charts.size());
        assertEquals("line", charts.get(0).get("chartType"));
        assertEquals("总量趋势", charts.get(0).get("title"));
        assertEquals(2, ((List<?>) charts.get(0).get("columns")).size());
        assertEquals(2461.5, ((Map<?, ?>) ((List<?>) charts.get(0).get("rows")).get(0)).get("value"));
    }

    @Test
    void parseCharts_shouldReturnEmptyForBlankOrMalformed() {
        assertTrue(service.parseCharts(null).isEmpty());
        assertTrue(service.parseCharts("   ").isEmpty());
        assertTrue(service.parseCharts("not-json").isEmpty());
    }
}
