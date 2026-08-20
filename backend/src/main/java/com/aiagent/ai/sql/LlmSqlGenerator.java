package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.prompt.PromptLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM Text-to-SQL 生成器（@Primary，SqlGenerator 接口的默认实现）。
 * 回退链：LLM 未配置 → 规则生成；LLM 输出校验失败 → 带错误重试一次 → 仍失败回退规则生成。
 */
@Component
@Primary
public class LlmSqlGenerator implements SqlGenerator {

    private static final String SYSTEM_PROMPT =
            "你是资深数据分析师，根据给定的数据表元数据与用户问题，生成一条只读 SELECT SQL。"
                    + "只输出 SQL 本身，不要任何解释、不要 markdown 代码块、不要分号结尾。"
                    + "统计长表 stat_monthly 的规则："
                    + "1) 查询数值指标必须同时返回 value、growth_rate、unit（unit 用于图表标注单位），不要遗漏增速列；"
                    + "2) period 为累计期别：1-3月=一季度、1-6月=上半年、1-9月=前三季度、1-12月=全年；用户说「前三季度/1-9月」就用 2025年1-9月，严禁臆造 2025年1-12月；"
                    + "3) 全市/邵阳市整体问题必须加 region='全市'；区县排名问题按 region 分组并取最新期别；"
                    + "4) 「2023年以来」类起始年问题用 period >= '2023年1月'；「近N年」趋势问题取最近 N 年同累计期别（如近3年前三季度 = 2023/2024/2025 年 1-9月）；"
                    + "5) 单期快照问题取最新存在期别（可用 ORDER BY period DESC LIMIT 1 或 MAX(period)），不要把不存在的未来期别当最新；"
                    + "6) 若用户指定期别（如 2025年1-9月）在该指标下查不到数据，回退该指标最新存在期别（部分效益/财务指标为 1-8月 上月数据），并在结果中保留真实 period 列便于标注。\n"
                    + "7) 交通运输 2025 年口径拆分：客运量(万人)/旅客周转量(万人公里) 为合计指标（2025年1-9月合计值已入库，直接按 indicator_name 查询即可）；" + "货运量/货物周转量 按公路口径发布，指标名为 公路(万吨)/公路(万吨公里)；2025年公路/水运分列指标为 公路(万人)/水运(万人) 等，若问题要求合计请用已入库的合计指标，不要自行对分列指标求和后再算增速。\n"
                    + "8) 「指标」列表中给出的指标名必须逐字使用（如 规模工业利润总额 不可替换为 利润总额，两者口径不同），严禁按字面简化、拆分或替换为相似指标名；"
                    + "9) 用户问「最新数据/最新是多少」= 单期快照：用 ORDER BY period DESC LIMIT 1 取该指标最新存在期别并保留真实 period 列；除非用户明确要求趋势/历年/多年对比，否则不要展开多年数据。\n"
                    + "10) region 列取值必须使用元数据中已知的区域名（如 长株潭地区/环长株潭城市群/湘南地区/大湘西地区/洞庭湖地区），严禁添加括号或 sheet 后缀；查询2019年及以前进出口用 进出口（万美元），2025年起用 进出口（亿元口径）。\n"
                    + "11) 「最新/当前/月末/是多少」类问题 = 单期快照：最新期别以数据实际存在为准（部分指标 2025 年停发，最新仍为 2024年1-9月；效益/财务类部分指标最新为 2025年1-8月），严禁用年度 1-12月 期别或臆造的未来期别冒充最新累计期；快照只返回该指标最新一期的 period/value/growth_rate/unit，不展开多年数据。";

    private final LlmClient llmClient;
    private final SqlValidator sqlValidator;
    private final RuleSqlGenerator ruleSqlGenerator;
    private final MetadataService metadataService;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;

    public LlmSqlGenerator(LlmClient llmClient, SqlValidator sqlValidator, RuleSqlGenerator ruleSqlGenerator,
                           MetadataService metadataService, ModelRouter modelRouter, ObjectMapper objectMapper,
                           PromptLoader promptLoader) {
        this.llmClient = llmClient;
        this.sqlValidator = sqlValidator;
        this.ruleSqlGenerator = ruleSqlGenerator;
        this.metadataService = metadataService;
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    @Override
    public GeneratedSql generate(AnalysisPlan plan, RecognizedIntent intent) {
        return generate(plan, intent, null);
    }

    @Override
    public GeneratedSql generate(AnalysisPlan plan, RecognizedIntent intent, String question) {
        return generateInternal(plan, intent, question, null);
    }

    @Override
    public GeneratedSql generateWithHint(AnalysisPlan plan, RecognizedIntent intent, String question, String hint) {
        return generateInternal(plan, intent, question, hint);
    }

    /** stat_monthly 排名/区县快照：维度=区县 且 柱状图（如「X排名」），规则引擎确定性输出区县快照 SQL。 */
    /** 单期快照（最新期间 + 指标维度）→ 规则引擎确定性取数，规避 LLM 臆造/写错最新期别（如年度期别冒充最新累计期）。 */
    private static boolean isStatSnapshotPlan(AnalysisPlan plan) {
        if (plan == null || !"stat_monthly".equalsIgnoreCase(plan.getTargetTable())) {
            return false;
        }
        if (!"最新期间".equals(plan.getTimeRange())) {
            return false;
        }
        return plan.getDimensions() != null && plan.getDimensions().contains("指标");
    }

    private static boolean isStatRegionSnapshot(AnalysisPlan plan) {
        if (plan == null || !"stat_monthly".equalsIgnoreCase(plan.getTargetTable())) {
            return false;
        }
        if (!"bar".equalsIgnoreCase(plan.getChartType())) {
            return false;
        }
        return plan.getDimensions() != null && plan.getDimensions().contains("区县");
    }

    private GeneratedSql generateInternal(AnalysisPlan plan, RecognizedIntent intent, String question, String hint) {
        String target = plan == null ? null : plan.getTargetTable();
        // 2026-08-20 政务部员深度测试决策：stat_monthly 全量走 LLM（配置 key 时），规则引擎保留为未配置/校验失败兜底；
        // LLM 生成 SQL 仍经 9 条安全校验 → 失败重试一次 → 仍失败回退规则，保障链路不中断。
        // 例外：stat_monthly 排名/区县快照（维度=区县 且 bar）必须精确，直接走规则引擎（区县快照 SQL 已单测覆盖），
        // 避免 LLM 忽略计划维度生成「全市逐年」类错误 SQL。
        if (isStatRegionSnapshot(plan) || isStatSnapshotPlan(plan)) {
            return ruleSqlGenerator.generate(plan, intent);
        }
        if (!llmClient.isConfigured()) {
            return ruleSqlGenerator.generate(plan, intent);
        }
        // 按计划关联数据集裁剪元数据注入：stat 问题只给 stat_monthly 口径，避免 LLM 混入政务/订单表
        Long datasetId = plan == null ? null : plan.getDatasetId();
        String metadataText = datasetId == null ? metadataService.buildMetadataText()
                : metadataService.buildMetadataText(datasetId);
        String system = promptLoader.load("SQL", SYSTEM_PROMPT, Map.of("datasetSchema", metadataText));
        String user = buildUserPrompt(metadataText, plan, intent, question);
        if (hint != null && !hint.isBlank()) {
            user = user + "\n[纠错提示] " + hint;
        }
        ModelRouter.ModelConfig modelConfig = modelRouter.resolve("SQL");

        String firstSql = cleanSql(chat(system, user, modelConfig));
        SqlValidator.ValidationResult first = sqlValidator.validate(firstSql, targetTableOf(plan));
        if (first.valid()) {
            return new GeneratedSql(firstSql, "LLM");
        }

        String retry = "上次 SQL 未通过校验: " + serializeErrors(first.errors()) + "，请修正，仅输出 SQL";
        String secondSql = cleanSql(chat(system, user + "\n" + retry, modelConfig));
        SqlValidator.ValidationResult second = sqlValidator.validate(secondSql, targetTableOf(plan));
        if (second.valid()) {
            return new GeneratedSql(secondSql, "LLM");
        }
        return ruleSqlGenerator.generate(plan, intent);
    }

    /** 组装用户 Prompt：元数据 + 意图/目标表/指标/维度/时间范围（便于单测）。 */
    static String buildUserPrompt(String metadataText, AnalysisPlan plan, RecognizedIntent intent) {
        return buildUserPrompt(metadataText, plan, intent, null);
    }

    static String buildUserPrompt(String metadataText, AnalysisPlan plan, RecognizedIntent intent, String question) {
        String intentName = intent == null ? "" : safe(intent.getIntentName());
        String intentType = intent == null ? "" : safe(intent.getIntentType());
        String targetTable = plan == null ? "" : safe(plan.getTargetTable());
        String metrics = plan == null || plan.getMetrics() == null ? "" : String.valueOf(plan.getMetrics());
        String dimensions = plan == null || plan.getDimensions() == null ? "" : String.valueOf(plan.getDimensions());
        String timeRange = plan == null ? "" : safe(plan.getTimeRange());
        String questionLine = question == null || question.isBlank() ? "" : "\n用户问题: " + question;
        return safe(metadataText) + questionLine + "\n分析意图: " + intentName + "（" + intentType + "），目标表: " + targetTable
                + "，指标: " + metrics + "，维度: " + dimensions + "，时间范围: " + timeRange;
    }

    /** 清理 LLM 输出：去 markdown 围栏、去分号（避免误伤 R2）、trim（便于单测）。 */
    static String cleanSql(String raw) {
        if (raw == null) {
            return "";
        }
        String sql = raw.trim();
        sql = sql.replaceFirst("(?i)^```(?:sql)?\\s*", "");
        sql = sql.replaceFirst("(?i)\\s*```$", "");
        sql = sql.trim();
        sql = sql.replace(";", "");
        return sql.trim();
    }

    private String chat(String system, String user, ModelRouter.ModelConfig modelConfig) {
        return modelConfig == null ? llmClient.chat(system, user) : llmClient.chat(system, user, modelConfig);
    }

    private String serializeErrors(List<String> errors) {
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (JsonProcessingException e) {
            return String.valueOf(errors);
        }
    }

    private static String targetTableOf(AnalysisPlan plan) {
        return plan == null ? null : plan.getTargetTable();
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
