package com.aiagent.ai.planner;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.metadata.DemoMetadataCatalog;
import com.aiagent.entity.AnalysisPlanConfig;
import com.aiagent.entity.AnalysisPlanType;
import com.aiagent.mapper.AnalysisConfigMapper;
import com.aiagent.service.AnalysisConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L1：计划类型自定义路由（GOV 保留原行为 / 自定义类型关键词路由 / 停用类型不生效）。 */
class AnalysisPlannerTypeRouteTest {

    private AnalysisPlanner planner;
    private AnalysisConfigMapper mapper;

    private AnalysisPlanType type(String code, String name, String keywords, int status) {
        AnalysisPlanType t = new AnalysisPlanType();
        t.setTypeCode(code);
        t.setTypeName(name);
        t.setRouteKeywords(keywords);
        t.setStatus(status);
        return t;
    }

    private AnalysisPlanConfig config(String intentCode, String typeCode, String table, String chart) {
        AnalysisPlanConfig c = new AnalysisPlanConfig();
        c.setIntentCode(intentCode);
        c.setPlanType(typeCode);
        c.setTableName(table);
        c.setMetrics("指标");
        c.setDimensions("维度");
        c.setChartType(chart);
        c.setTimeRange("近30天");
        c.setStatus(1);
        return c;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AnalysisConfigMapper.class);
        when(mapper.selectPlanTypes()).thenReturn(List.of(
                type("NORMAL", "普通", "", 1),
                type("GOV", "政务", "政务,公开,邵阳", 1),
                type("STAT", "统计", "统计,gdp,经济,增速", 1)));
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("SALES_TREND", "NORMAL", "order_info", "line"),
                config("STAT_TREND", "NORMAL", "stat_indicator", "line"),
                config("STAT_TREND", "GOV", "stat_indicator", "line"),
                config("GENERAL", "GOV", "GOV_INFO_RECORD", "table"),
                config("GENERAL", "NORMAL", "order_info", "table")));
        planner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper));
    }

    private RecognizedIntent intent(String type, List<String> keywords) {
        return new RecognizedIntent(type, "测试意图", 0.8, keywords);
    }

    @Test
    void shouldRouteGovKeywordToGovTypeWhenNoStatKeyword() {
        AnalysisPlan plan = planner.buildPlan(intent("GENERAL", List.of("政务公开")), "邵阳政务公开发文情况");
        assertEquals("GOV", plan.getPlanType());
        assertEquals("GOV_INFO_RECORD", plan.getTargetTable());
    }

    @Test
    void shouldRouteStatKeywordOverGovMarker() {
        AnalysisPlan plan = planner.buildPlan(intent("STAT_TREND", List.of("经济", "政务公开")), "邵阳经济趋势");
        assertEquals("STAT", plan.getPlanType());
        assertEquals("stat_indicator", plan.getTargetTable());
    }

    @Test
    void shouldRouteCustomTypeByRouteKeywords() {
        AnalysisPlan plan = planner.buildPlan(intent("STAT_TREND", List.of("gdp")), "2025年GDP趋势");
        assertEquals("STAT", plan.getPlanType());
        assertEquals("stat_indicator", plan.getTargetTable());
    }

    @Test
    void shouldFallbackToNormalWhenNoTypeKeywordHit() {
        AnalysisPlan plan = planner.buildPlan(intent("SALES_TREND", List.of("趋势")), "分析销售趋势");
        assertEquals("NORMAL", plan.getPlanType());
        assertEquals("order_info", plan.getTargetTable());
    }

    @Test
    void disabledTypeShouldNotRoute() {
        when(mapper.selectPlanTypes()).thenReturn(List.of(
                type("NORMAL", "普通", "", 1),
                type("GOV", "政务", "政务,公开,邵阳", 1),
                type("STAT", "统计", "统计,gdp,经济,增速", 0)));
        AnalysisPlan plan = planner.buildPlan(intent("STAT_TREND", List.of("gdp")), "2025年GDP趋势");
        assertEquals("NORMAL", plan.getPlanType(), "停用类型不应参与路由");
        assertEquals("stat_indicator", plan.getTargetTable(), "回退普通类型同意图配置");
    }
    @Test
    void shouldRefineStructureToRegionOrIndustry() {
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                structureConfig("STRUCTURE", "STAT", "stat_monthly", "第一产业,第二产业,第三产业", "产业", "pie"),
                structureConfig("STRUCTURE", "STAT", "stat_monthly", "地区生产总值（万元）", "区县", "pie")));
        AnalysisPlan region = planner.buildPlan(
                new RecognizedIntent("STRUCTURE", "占比结构", 0.8, List.of("占比")), "邵阳市不同地区经济占比");
        assertEquals("STAT", region.getPlanType());
        assertEquals("stat_monthly", region.getTargetTable());
        assertEquals("pie", region.getChartType());
        assertEquals("区县", region.getDimensions().get(0));

        AnalysisPlan industry = planner.buildPlan(
                new RecognizedIntent("STRUCTURE", "占比结构", 0.8, List.of("占比")), "邵阳市各个产业结构占比");
        assertEquals("pie", industry.getChartType());
        assertEquals("产业", industry.getDimensions().get(0));
        assertTrue(industry.getMetrics().contains("第一产业"));
    }

    @Test
    void shouldRefineIndustryTrendToLineWithSingleMetric() {
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                structureConfig("STRUCTURE", "STAT", "stat_monthly", "第一产业,第二产业,第三产业", "产业", "pie"),
                structureConfig("STRUCTURE", "STAT", "stat_monthly", "地区生产总值（万元）", "区县", "pie")));
        AnalysisPlan trend = planner.buildPlan(
                new RecognizedIntent("STRUCTURE", "占比结构", 0.65, List.of("占比")),
                "邵阳市近五年第一产业占比呈现怎样的变化趋势");
        assertEquals("line", trend.getChartType(), "占比趋势应识别为折线");
        assertEquals(List.of("第一产业"), trend.getMetrics(), "只提第一产业时指标应收敛");
        assertEquals("期间", trend.getDimensions().get(0), "趋势应含期间维度");
        assertEquals("产业", trend.getDimensions().get(1));
        assertEquals("近5年", trend.getTimeRange(), "近五年应解析为近5年");

        AnalysisPlan all = planner.buildPlan(
                new RecognizedIntent("STRUCTURE", "占比结构", 0.65, List.of("占比")),
                "邵阳市近五年三大产业占比的变化趋势是怎样的");
        assertEquals("line", all.getChartType());
        assertEquals(3, all.getMetrics().size(), "三大产业趋势应保留全部产业指标");
        assertEquals("期间", all.getDimensions().get(0));
    }

    @Test
    void shouldRefineStatTrendToIndustryForStatMonthlyDataset() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("STRUCTURE", "STAT", "stat_monthly", "pie")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);
        AnalysisPlan plan = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.8, List.of("第一产业")), "邵阳市第一产业发展趋势", 23L);
        assertEquals("stat_monthly", plan.getTargetTable(), "统计月报数据集应路由到 stat_monthly");
        assertEquals(List.of("第一产业"), plan.getMetrics(), "提及第一产业时指标应收敛");
        assertEquals("line", plan.getChartType(), "趋势应识别为折线");
        assertEquals(List.of("期间", "产业"), plan.getDimensions(), "趋势维度应为期间+产业");
    }

    @Test
    void shouldNormalizeSalesTrendToStatTrendForStatMonthlyDataset() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);
        AnalysisPlan plan = datasetPlanner.buildPlan(
                new RecognizedIntent("SALES_TREND", "销售趋势", 0.65, List.of("趋势")), "邵阳市第一产业发展趋势", 23L);
        assertEquals("stat_monthly", plan.getTargetTable(), "趋势类问题在统计月报数据集下应路由 stat_monthly");
        assertEquals(List.of("第一产业"), plan.getMetrics(), "提及第一产业时指标应收敛");
        assertEquals("line", plan.getChartType(), "趋势应识别为折线");
    }

    @Test
    void shouldNormalizeRankingToStatTrendForStatMonthlyDataset() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);
        AnalysisPlan plan = datasetPlanner.buildPlan(
                new RecognizedIntent("RANKING", "排名", 0.8, List.of("排名")), "各区县规模工业增加值增速排名", 23L);
        assertEquals("STAT", plan.getPlanType(), "排名问题在统计月报数据集下应归一 STAT_TREND 路由");
        assertEquals("stat_monthly", plan.getTargetTable(), "排名问题不应回退 order_info");
        assertEquals(List.of("规模工业增加值"), plan.getMetrics(), "提及规模工业增加值时指标应收敛");
        assertEquals(List.of("区县"), plan.getDimensions(), "排名问题维度应为最新一期区县快照");
        assertEquals("bar", plan.getChartType(), "排名问题应为柱状图");
    }

    @Test
    void shouldNormalizeComparisonToStatTrendForStatMonthlyDataset() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);
        AnalysisPlan plan = datasetPlanner.buildPlan(
                new RecognizedIntent("COMPARISON", "对比", 0.8, List.of("对比")), "各区县规模工业增加值增速对比", 23L);
        assertEquals("STAT", plan.getPlanType(), "对比问题在统计月报数据集下应归一 STAT_TREND 路由");
        assertEquals("stat_monthly", plan.getTargetTable(), "对比问题不应回退 order_info");
        assertEquals(List.of("区县"), plan.getDimensions(), "对比问题维度应为区县快照");
    }

    @Test
    void shouldKeepRankingNormalOutsideStatMonthlyDataset() {
        AnalysisPlan plan = planner.buildPlan(intent("RANKING", List.of("排名")), "产品销量排名");
        assertEquals("NORMAL", plan.getPlanType(), "非统计数据集下排名问题应保持 NORMAL 路由");
        assertEquals("order_info", plan.getTargetTable(), "NORMAL 回退配置目标表应为 order_info");
    }

    @Test
    void shouldRefineStatTrendToNonIndustryIndicator() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);
        AnalysisPlan fdi = datasetPlanner.buildPlan(
                new RecognizedIntent("SALES_TREND", "销售趋势", 0.65, List.of("趋势")), "邵阳市外商直接投资分析", 23L);
        assertEquals("stat_monthly", fdi.getTargetTable(), "统计月报数据集应路由 stat_monthly");
        assertEquals(List.of("外商直接投资"), fdi.getMetrics(), "提及外商直接投资时指标应收敛");
        assertEquals("line", fdi.getChartType(), "趋势应识别为折线");
        assertEquals("指标", fdi.getDimensions().get(1), "非产业指标维度应为指标");

        AnalysisPlan trade = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.8, List.of("进出口")), "邵阳市进出口总额趋势", 23L);
        assertEquals(List.of("进出口"), trade.getMetrics(), "提及进出口时指标应收敛");
    }


    @Test
    void shouldRankMetricSnapshotBarWithoutRegionKeyword() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);
        AnalysisPlan plan = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_RANKING", "统计排名", 0.8, List.of("排名")), "邵阳市一般公共预算收入排名", 23L);
        assertEquals("stat_monthly", plan.getTargetTable(), "排名问题在统计月报数据集下应路由 stat_monthly");
        assertEquals(List.of("一般公共预算收入"), plan.getMetrics(), "排名问题指标应收敛到排名基数指标: " + plan.getMetrics());
        assertEquals(List.of("区县"), plan.getDimensions(), "无区县词排名问题也应按区县快照: " + plan.getDimensions());
        assertEquals("bar", plan.getChartType(), "排名问题应为柱状图");
    }

    @Test
    void shouldRouteCategoryKeywordToFamilyMetrics() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);

        AnalysisPlan trade = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.65, List.of("经济")), "邵阳外贸情况分析", 23L);
        assertEquals(List.of("进出口", "出口", "进口", "外商直接投资"), trade.getMetrics(),
                "外贸大类关键词应收敛到外贸外资族: " + trade.getMetrics());
        assertEquals("指标", trade.getDimensions().get(1), "族内多指标维度应为指标");

        AnalysisPlan finance = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.65, List.of("经济")), "邵阳市存贷款情况", 23L);
        assertEquals(List.of("各项存款", "各项贷款"), finance.getMetrics(),
                "存贷款大类关键词应收敛到金融运行族: " + finance.getMetrics());

        AnalysisPlan fiscal = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.65, List.of("经济")), "邵阳市财政收支情况", 23L);
        assertEquals(List.of("一般公共预算收入", "一般公共预算支出"), fiscal.getMetrics(),
                "财政大类关键词应收敛到财政收支族: " + fiscal.getMetrics());
    }

    private AnalysisPlanConfig structureConfig(String intentCode, String typeCode, String table,
                                               String metrics, String dimensions, String chart) {
        AnalysisPlanConfig c = new AnalysisPlanConfig();
        c.setIntentCode(intentCode);
        c.setPlanType(typeCode);
        c.setTableName(table);
        c.setMetrics(metrics);
        c.setDimensions(dimensions);
        c.setChartType(chart);
        c.setTimeRange("最新期间");
        c.setStatus(1);
        return c;
    }
    @Test
    void shouldRefineStatTrendToGdpAndFiscalIndicators() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);

        AnalysisPlan gdp = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.9, List.of("生产总值")), "2024年邵阳地区生产总值及增速如何？", 23L);
        assertEquals("stat_monthly", gdp.getTargetTable(), "GDP 问题应路由 stat_monthly");
        assertEquals(List.of("地区生产总值"), gdp.getMetrics(), "提及地区生产总值应收敛到规范名");
        assertEquals("2024年", gdp.getTimeRange(), "4 位年份应按绝对年份解析");

        AnalysisPlan fiscal = datasetPlanner.buildPlan(
                new RecognizedIntent("SALES_TREND", "销售趋势", 0.65, List.of("收入", "趋势")), "邵阳一般公共预算收入趋势", 23L);
        assertEquals("stat_monthly", fiscal.getTargetTable(), "财政问题应路由 stat_monthly");
        assertEquals(List.of("一般公共预算收入"), fiscal.getMetrics(), "提及一般公共预算收入应收敛");
    }

    @Test
    void shouldRefineStatTrendToIndustrySector() {
        com.aiagent.mapper.MetadataAdminMapper datasetMapper = mock(com.aiagent.mapper.MetadataAdminMapper.class);
        com.aiagent.entity.Dataset dataset = new com.aiagent.entity.Dataset();
        dataset.setTableName("stat_monthly");
        when(datasetMapper.selectDatasetById(23L)).thenReturn(dataset);
        when(mapper.selectPlanConfigs()).thenReturn(List.of(
                config("STAT_TREND", "STAT", "stat_monthly", "line"),
                structureConfig("STRUCTURE", "STAT", "stat_monthly", "第一产业,第二产业,第三产业", "产业", "pie"),
                config("SALES_TREND", "NORMAL", "order_info", "line")));
        AnalysisPlanner datasetPlanner = new AnalysisPlanner(new DemoMetadataCatalog(), new AnalysisConfigService(mapper), datasetMapper);

        AnalysisPlan sector = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.85, List.of("行业")), "邵阳规模工业大类行业增加值各行业增速", 23L);
        assertEquals("stat_monthly", sector.getTargetTable());
        assertTrue(sector.getMetrics().contains("通用设备制造业"), "行业族提问应收敛到全部行业");
        assertEquals(35, sector.getMetrics().size(), "行业族提问应覆盖 35 个行业");
        assertEquals("行业", sector.getDimensions().get(1), "行业族维度应为行业");

        AnalysisPlan single = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.9, List.of("增速")), "邵阳通用设备制造业增速是多少？", 23L);
        assertEquals(List.of("通用设备制造业"), single.getMetrics(), "提及具体行业应收敛到该行业");

        AnalysisPlan regionCompare = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.9, List.of("规模工业增加值")), "各区县规模工业增加值增速对比", 23L);
        assertEquals(List.of("规模工业增加值"), regionCompare.getMetrics(), "区县对比指标应收敛到提问指标");
        assertEquals(List.of("区县"), regionCompare.getDimensions(), "区县对比维度应收敛为区县快照");
        assertEquals("bar", regionCompare.getChartType(), "区县对比应为柱状图");

        AnalysisPlan regionTrend = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.9, List.of("地区生产总值")), "各区县地区生产总值趋势", 23L);
        assertEquals(List.of("期间", "区县"), regionTrend.getDimensions(), "含趋势词的区县问题应保留期间维度");
        assertEquals("line", regionTrend.getChartType(), "区县趋势应为折线图");

        AnalysisPlan fiscalStructure = datasetPlanner.buildPlan(
                new RecognizedIntent("STRUCTURE", "结构占比分析", 0.95, List.of("占比")), "邵阳市一般公共预算收入中，税收收入和非税收入各占多大比例", 23L);
        assertTrue(fiscalStructure.getMetrics().containsAll(List.of("税收收入", "非税收入")), "财政结构占比应收敛到税收/非税: " + fiscalStructure.getMetrics());
        assertTrue(!fiscalStructure.getMetrics().contains("一般公共预算收入"), "结构整体不应参与扇区: " + fiscalStructure.getMetrics());
        assertEquals(List.of("指标"), fiscalStructure.getDimensions(), "指标结构维度应为指标");
        assertEquals("pie", fiscalStructure.getChartType(), "指标结构应为饼图");
        assertEquals("stat_monthly", fiscalStructure.getTargetTable(), "财政结构应路由 stat_monthly");

        AnalysisPlan industryGuard = datasetPlanner.buildPlan(
                new RecognizedIntent("STRUCTURE", "结构占比分析", 0.95, List.of("占比")), "地区生产总值中三次产业占比", 23L);
        assertTrue(industryGuard.getMetrics().contains("第一产业"), "产业占比不应被指标收敛覆盖: " + industryGuard.getMetrics());

        AnalysisPlan regionFiscal = datasetPlanner.buildPlan(
                new RecognizedIntent("STRUCTURE", "结构占比分析", 0.95, List.of("占比")), "各区县税收收入占比", 23L);
        assertEquals(List.of("税收收入"), regionFiscal.getMetrics(), "区县指标占比应收敛到税收收入: " + regionFiscal.getMetrics());
        assertEquals(List.of("区县"), regionFiscal.getDimensions(), "区县占比应保留区县维度: " + regionFiscal.getDimensions());

        AnalysisPlan tradeStructure = datasetPlanner.buildPlan(
                new RecognizedIntent("STRUCTURE", "结构占比分析", 0.95, List.of("占比")), "邵阳市进出口中出口占比", 23L);
        assertEquals(List.of("出口"), tradeStructure.getMetrics(), "进出口中出口占比应收敛到出口: " + tradeStructure.getMetrics());
        assertEquals(List.of("指标"), tradeStructure.getDimensions(), "进出口结构维度应为指标");

        AnalysisPlan tradeTrend = datasetPlanner.buildPlan(
                new RecognizedIntent("STAT_TREND", "统计指标趋势", 0.85, List.of("进出口")), "邵阳市进出口总额趋势", 23L);
        assertEquals(List.of("进出口"), tradeTrend.getMetrics(), "进出口趋势不应误收敛到出口: " + tradeTrend.getMetrics());
    }
}
