package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.entity.AnalysisPlanType;
import com.aiagent.entity.Dataset;
import com.aiagent.mapper.AnalysisConfigMapper;
import com.aiagent.mapper.MetadataAdminMapper;
import com.aiagent.service.AnalysisConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L1：stat_monthly 单期快照计划（最新期间）识别与排名指标最长匹配。 */
class AnalysisPlannerStatSnapshotTest {

    private AnalysisPlanner planner;

    @BeforeEach
    void setUp() {
        AnalysisConfigMapper mapper = mock(AnalysisConfigMapper.class);
        when(mapper.selectPlanTypes()).thenReturn(List.of(
                planType("NORMAL", "普通", "", 1),
                planType("GOV", "政务", "政务,公开,邵阳", 1),
                planType("STAT", "统计", "统计,gdp,经济,增速,外商", 1)));
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                planConfig("STAT_TREND", "STAT", "stat_monthly", "地区生产总值（GDP）,增速", "期间,区县", "line", "近3年"),
                planConfig("STAT_RANKING", "STAT", "stat_monthly", "地区生产总值（GDP）,增速", "区县", "bar", "最新期间"),
                planConfig("STRUCTURE", "STAT", "stat_monthly", "第一产业,第二产业,第三产业", "产业", "pie", "最新期间")));
        MetadataAdminMapper datasetMapper = mock(MetadataAdminMapper.class);
        Dataset dataset = new Dataset();
        dataset.setId(23L);
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        planner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);
    }

    private static AnalysisPlanType planType(String code, String name, String keywords, int status) {
        AnalysisPlanType t = new AnalysisPlanType();
        t.setTypeCode(code);
        t.setTypeName(name);
        t.setRouteKeywords(keywords);
        t.setStatus(status);
        return t;
    }

    private static AnalysisPlanConfig planConfig(String intentCode, String type, String table,
                                                 String metrics, String dims, String chart, String range) {
        AnalysisPlanConfig c = new AnalysisPlanConfig();
        c.setIntentCode(intentCode);
        c.setPlanType(type);
        c.setTableName(table);
        c.setMetrics(metrics);
        c.setDimensions(dims);
        c.setChartType(chart);
        c.setTimeRange(range);
        c.setStatus(1);
        return c;
    }

    private static RecognizedIntent statIntent() {
        return new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.9, List.of("统计"));
    }

    private AnalysisPlan planFor(String question) {
        return planner.buildPlan(statIntent(), question, 23L);
    }

    @Test
    void shouldResolveLatestAskToSnapshotPlan() {
        AnalysisPlan plan = planFor("邵阳市产业投资增速最新是多少？请说明口径与数据期间。");
        assertEquals("最新期间", plan.getTimeRange(), "「最新」应为单期快照");
        assertEquals(List.of("指标"), plan.getDimensions(), "快照维度应收敛为指标");
        assertEquals("bar", plan.getChartType());
        assertTrue(plan.getMetrics().contains("产业投资"), "指标应收敛为产业投资: " + plan.getMetrics());
    }

    @Test
    void shouldResolveMonthEndAskToSnapshotPlan() {
        AnalysisPlan plan = planFor("邵阳市9月末存款余额是多少？请说明期间口径。");
        assertEquals("最新期间", plan.getTimeRange());
        assertEquals(List.of("指标"), plan.getDimensions());
        assertTrue(plan.getMetrics().contains("各项存款"), "指标应收敛为各项存款: " + plan.getMetrics());
    }

    @Test
    void shouldResolveGdpAskToSnapshotPlan() {
        AnalysisPlan plan = planFor("邵阳市GDP是多少？请用亿元口径回答。");
        assertEquals("最新期间", plan.getTimeRange());
        assertEquals(List.of("指标"), plan.getDimensions());
        assertTrue(plan.getMetrics().contains("地区生产总值"), "指标应收敛为地区生产总值: " + plan.getMetrics());
    }

    @Test
    void shouldKeepTrendForExplicitCumulativePeriod() {
        AnalysisPlan plan = planFor("2024年前三季度邵阳市用电量同比增速是多少？");
        assertFalse("最新期间".equals(plan.getTimeRange()), "明确累计期别不应按快照处理");
        assertTrue(plan.getDimensions().contains("期间"), "趋势计划应含期间维度: " + plan.getDimensions());
    }

    @Test
    void shouldKeepTrendForStructureAsk() {
        AnalysisPlan plan = planFor("2025年9月末邵阳市住户存款中活期与定期分别是多少？");
        assertTrue(plan.getDimensions().contains("期间"), "「分别」结构问法不应按单期快照处理: " + plan.getDimensions());
    }

    @Test
    void shouldKeepNonSnapshotForRegionName() {
        AnalysisPlan plan = planFor("长株潭地区进出口数据是多少？请说明区域口径。");
        assertTrue(plan.getDimensions().contains("期间"), "区域口径问法不应按单期快照处理: " + plan.getDimensions());
    }

    @Test
    void shouldResolveFdiRankingToFdiNotFixedAssetInvestment() {
        AnalysisPlan plan = planFor("2025年前三季度邵阳市各区县外商直接投资排名如何？");
        assertEquals(List.of("外商直接投资"), plan.getMetrics(), "排名指标应最长匹配为外商直接投资: " + plan.getMetrics());
        assertEquals(List.of("区县"), plan.getDimensions());
        assertEquals("bar", plan.getChartType());
    }
}