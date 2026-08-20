package com.aiagent.service;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.IntentRecognizer;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.planner.AnalysisPlanner;
import com.aiagent.ai.interpreter.DataInterpreter;
import com.aiagent.ai.sql.SqlGenerator;
import com.aiagent.ai.sql.SqlValidator;
import com.aiagent.entity.AgentPlan;
import com.aiagent.mapper.AgentPlanMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Agent 多步分析计划：宏观目标 → LLM/规则拆解多步 → 逐步执行（复用分析引擎）→ 生成结构化报告。 */
@Service
public class AgentPlanService {

    /** 单步执行的最低意图置信度（与分析接口一致）。 */
    private static final double MIN_INTENT_CONFIDENCE = 0.35;
    private static final int MAX_STEPS = 6;
    private static final int MAX_CHART_ROWS = 200;
    private static final int MAX_TITLE_LEN = 50;

    /** 演示/示例业务表：经济分析计划命中则判定为错表查询。 */
    private static final List<String> DEMO_TABLES = List.of("order_info", "order_count", "user_info",
            "product_info", "order", "orders", "demo_order");
    /** 步骤问题强指向的业务指标域触发词（与 METRIC_SQL_HINTS 一一对应）。 */
    private static final List<List<String>> METRIC_TRIGGERS = List.of(
            List.of("消费", "外贸", "社零", "零售", "进出口"),
            List.of("居民收入", "人均可支配", "可支配收入"),
            List.of("财政", "一般公共预算", "存贷款", "存款", "贷款余额", "金融"));
    private static final List<List<String>> METRIC_SQL_HINTS = List.of(
            List.of("消费", "零售", "进出口", "外贸", "社零"),
            List.of("收入", "可支配"),
            List.of("财政", "预算", "存款", "贷款", "金融"));

    private static final String DECOMPOSE_SYSTEM =
            "你是数据分析 Agent 的计划拆解器。根据用户的分析目标，将其拆解为 2~6 个可独立执行的数据分析步骤，"
                    + "每个步骤必须能被一条数据库查询完成。只输出 JSON 数组，不要输出其他文字，格式："
                    + "[{\"name\":\"步骤名称\",\"question\":\"可执行的分析问题\",\"logic\":\"分析逻辑说明\",\"chartType\":\"line|bar|pie|table\"}]。";
    private static final String REPORT_SYSTEM =
            "你是资深数据分析师。根据多步分析计划的执行结果，用中文输出一份结构化 Markdown 分析报告，必须包含四个部分："
                    + "## 分析目标、## 查询过程与图表结论（每步一个小节并引用关键数字）、## 关键洞察、## 行动建议；"
                    + "报告用 # 一级标题，只输出报告正文，不要多余解释。";

    private final AgentPlanMapper planMapper;
    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;
    private final ModelRouter modelRouter;
    private final MetadataService metadataService;
    private final IntentRecognizer intentRecognizer;
    private final AnalysisPlanner analysisPlanner;
    private final SqlGenerator sqlGenerator;
    private final SqlValidator sqlValidator;
    private final SqlExecutor sqlExecutor;
    private final DataInterpreter dataInterpreter;

    public AgentPlanService(AgentPlanMapper planMapper, ObjectMapper objectMapper, LlmClient llmClient,
                            ModelRouter modelRouter, MetadataService metadataService,
                            IntentRecognizer intentRecognizer, AnalysisPlanner analysisPlanner,
                            SqlGenerator sqlGenerator, SqlValidator sqlValidator,
                            SqlExecutor sqlExecutor, DataInterpreter dataInterpreter) {
        this.planMapper = planMapper;
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
        this.modelRouter = modelRouter;
        this.metadataService = metadataService;
        this.intentRecognizer = intentRecognizer;
        this.analysisPlanner = analysisPlanner;
        this.sqlGenerator = sqlGenerator;
        this.sqlValidator = sqlValidator;
        this.sqlExecutor = sqlExecutor;
        this.dataInterpreter = dataInterpreter;
    }

    /** 计划步骤（Jackson 可直接序列化/反序列化）。 */
    public static class PlanStep {
        public int stepNo;
        public String name;
        public String question;
        public String logic;
        public String chartType;
        public String status = "PENDING";
        public Long durationMs;
        public String error;
        public String intentType;
        public String targetTable;
        public String sqlPurpose;
        public String sql;
        public Integer rowCount;
        public List<String> columns;
        public List<Map<String, Object>> rows;
        public String interpretation;

        public PlanStep() {
        }

        public PlanStep(int stepNo, String name, String question, String logic, String chartType) {
            this.stepNo = stepNo;
            this.name = name;
            this.question = question;
            this.logic = logic;
            this.chartType = chartType;
        }
    }

    /** 拆解宏观目标生成计划（LLM 优先，规则兜底）。 */
    public AgentPlan decompose(Long userId, String goal, Long datasetId, Long modelConfigId, String title) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("分析目标不能为空");
        }
        List<PlanStep> steps = llmClient.isConfigured() ? decomposeByLlm(goal, datasetId, modelConfigId) : null;
        if (steps == null || steps.isEmpty()) {
            steps = decomposeByRule(goal);
        }
        AgentPlan plan = new AgentPlan();
        plan.setUserId(userId);
        plan.setTitle(title == null || title.isBlank() ? defaultTitle(goal) : title);
        plan.setGoal(goal);
        plan.setDatasetId(datasetId);
        plan.setModelConfigId(modelConfigId);
        plan.setStatus("GENERATED");
        plan.setStepsJson(toJson(steps));
        planMapper.insert(plan);
        return plan;
    }

    /** 逐步执行计划：每步复用意图/计划/SQL/校验/执行/解读引擎，更新步骤状态、耗时与结果。 */
    public AgentPlan execute(Long planId, Long userId) {
        AgentPlan plan = loadOwned(planId, userId);
        List<PlanStep> steps = parseSteps(plan.getStepsJson());
        plan.setStatus("EXECUTING");
        persist(plan, steps);
        boolean allOk = true;
        for (PlanStep step : steps) {
            if ("SUCCESS".equals(step.status)) {
                continue;
            }
            runStep(step, plan.getDatasetId(), plan.getModelConfigId());
            if (!"SUCCESS".equals(step.status)) {
                allOk = false;
            }
            persist(plan, steps);
        }
        plan.setStatus(allOk ? "DONE" : "FAILED");
        persist(plan, steps);
        return plan;
    }

    /** 生成报告：按多步执行结果（每步结论+图表数据）生成结构化 Markdown，覆盖式落库。 */
    public AgentPlan generateReport(Long planId, Long userId, Long modelConfigId) {
        AgentPlan plan = loadOwned(planId, userId);
        List<PlanStep> steps = parseSteps(plan.getStepsJson());
        if (steps.stream().noneMatch(s -> "SUCCESS".equals(s.status))) {
            throw new IllegalStateException("计划尚未执行成功，无法生成报告");
        }
        String content = llmClient.isConfigured() ? reportByLlm(plan, steps, modelConfigId) : null;
        String generatorType = "LLM";
        if (content == null || content.isBlank()) {
            content = reportByRule(plan, steps);
            generatorType = "RULE";
        }
        plan.setReportTitle(plan.getTitle() + "分析报告");
        plan.setReportContent(content);
        plan.setReportGeneratorType(generatorType);
        plan.setReportChartsJson(toJson(buildCharts(steps)));
        planMapper.update(plan);
        return plan;
    }

    public AgentPlan loadOwned(Long planId, Long userId) {
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null || !userId.equals(plan.getUserId())) {
            throw new IllegalArgumentException("计划不存在或无权访问");
        }
        return plan;
    }

    public List<AgentPlan> list(Long userId) {
        return planMapper.selectByUserId(userId);
    }

    public List<AgentPlan> listReports(Long userId) {
        return planMapper.selectReportsByUserId(userId);
    }

    public void delete(Long planId, Long userId) {
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null || !userId.equals(plan.getUserId())) {
            throw new IllegalArgumentException("计划不存在或无权访问");
        }
        planMapper.deleteById(planId);
    }

    public List<Map<String, Object>> parseCharts(String chartsJson) {
        if (chartsJson == null || chartsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> charts = objectMapper.readValue(chartsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            return charts == null ? new ArrayList<>() : charts;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    public List<PlanStep> parseSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<PlanStep> steps = objectMapper.readValue(stepsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PlanStep.class));
            return steps == null ? new ArrayList<>() : steps;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ---------- 拆解 ----------

    private List<PlanStep> decomposeByLlm(String goal, Long datasetId, Long modelConfigId) {
        try {
            String metadata = "";
            try {
                metadata = datasetId == null ? metadataService.buildMetadataText()
                        : metadataService.buildMetadataText(datasetId);
            } catch (Exception ignore) {
                // 元数据失败不影响拆解
            }
            String user = "分析目标：" + goal + "\n可用数据说明：\n" + (metadata == null ? "" : metadata)
                    + "\n请输出拆解后的步骤 JSON 数组。";
            String raw = llmClient.chat(DECOMPOSE_SYSTEM, user, modelRouter.resolve("PLAN", modelConfigId));
            JsonNode arr = extractJsonArray(raw);
            List<PlanStep> steps = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                int no = 1;
                for (JsonNode node : arr) {
                    if (no > MAX_STEPS) {
                        break;
                    }
                    String question = text(node, "question");
                    String logic = text(node, "logic");
                    String chartType = text(node, "chartType");
                    String name = text(node, "name");
                    if (question.isBlank()) {
                        continue;
                    }
                    steps.add(new PlanStep(no++, name.isBlank() ? shortName(question) : name,
                            question, logic.isBlank() ? "按计划配置执行该分析问题。" : logic,
                            normalizeChart(chartType)));
                }
            }
            return steps;
        } catch (Exception e) {
            return null;
        }
    }

    private List<PlanStep> decomposeByRule(String goal) {
        String lower = goal.toLowerCase(Locale.ROOT);
        List<PlanStep> steps = new ArrayList<>();
        if (matchesAny(lower, List.of("gdp", "生产总值", "经济", "统计", "财政收入", "规上工业", "规模以上工业",
                "居民收入", "固定资产投资", "社会消费品", "增速", "产业"))) {
            steps.add(new PlanStep(1, "总量趋势", goal + "，整体总量趋势如何？", "按期间聚合总量指标，观察时间变化趋势。", "line"));
            steps.add(new PlanStep(2, "区县对比", goal + "，各区县最新期间指标对比", "按区县分组取最新期间指标值，对比区域差异。", "bar"));
            steps.add(new PlanStep(3, "结构占比", goal + "，各组成部分占比结构", "按指标/类别分组计算占比结构。", "pie"));
        } else if (matchesAny(lower, List.of("政务", "公开", "发文", "gov"))) {
            steps.add(new PlanStep(1, "发文趋势", "近30天每日发文量趋势如何？", "按发布日期聚合发文量，观察趋势。", "line"));
            steps.add(new PlanStep(2, "类目占比", "各公开类目发文量占比如何？", "按公开类目分组计算发文占比。", "pie"));
            steps.add(new PlanStep(3, "单位排名", "哪些单位发文最多？", "按发文单位分组统计并排序。", "bar"));
        } else {
            steps.add(new PlanStep(1, shortName(goal), goal, "按意图识别与计划配置执行该分析目标。", "table"));
        }
        return steps;
    }

    // ---------- 单步执行 ----------

    private void runStep(PlanStep step, Long datasetId, Long modelConfigId) {
        long start = System.currentTimeMillis();
        step.status = "RUNNING";
        try {
            String question = step.question == null ? "" : step.question;
            RecognizedIntent intent = intentRecognizer.recognize(question);
            if (intent == null || intent.getConfidence() < MIN_INTENT_CONFIDENCE || !containsCjk(question)) {
                fail(step, start, "无法识别分析意图（内容过短或置信度过低），请用更明确的业务问题重新描述");
                return;
            }
            AnalysisPlan plan = analysisPlanner.buildPlan(intent, question, datasetId);
            SqlGenerator.GeneratedSql generated = sqlGenerator.generate(plan, intent, question);
            SqlValidator.ValidationResult validation = sqlValidator.validate(generated.sql(), plan.getTargetTable());
            if (!validation.valid()) {
                fail(step, start, "SQL 校验未通过：" + String.join("; ", validation.errors()));
                return;
            }
            SqlExecutor.ExecutionResult execution = sqlExecutor.execute(generated.sql());
            DataInterpreter.Interpretation interpretation = dataInterpreter.interpret(plan, intent, execution,
                    modelConfigId, "", datasetId);
            step.status = "SUCCESS";
            step.durationMs = System.currentTimeMillis() - start;
            step.error = null;
            step.intentType = intent.getIntentType();
            step.targetTable = plan.getTargetTable();
            step.sqlPurpose = "按「" + join(plan.getDimensions()) + "」维度统计「" + join(plan.getMetrics())
                    + "」（" + safe(plan.getTimeRange()) + "）";
            step.sql = generated.sql();
            step.rowCount = execution.rowCount();
            step.columns = execution.columns();
            step.rows = capRows(execution.rows());
            step.interpretation = interpretation == null ? "" : interpretation.text();
        } catch (Exception e) {
            fail(step, start, e.getMessage() == null ? "执行异常" : e.getMessage());
        }
    }

    private void fail(PlanStep step, long start, String error) {
        step.status = "FAILED";
        step.durationMs = System.currentTimeMillis() - start;
        step.error = error;
    }

    private List<Map<String, Object>> capRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() <= MAX_CHART_ROWS) {
            return rows;
        }
        return new ArrayList<>(rows.subList(0, MAX_CHART_ROWS));
    }

    // ---------- 报告 ----------

    private String reportByLlm(AgentPlan plan, List<PlanStep> steps, Long modelConfigId) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("计划标题：").append(plan.getTitle()).append("\n分析目标：").append(plan.getGoal()).append("\n\n逐步执行结果：\n");
            for (PlanStep step : steps) {
                sb.append("步骤").append(step.stepNo).append("：").append(safe(step.name)).append("\n")
                        .append("- 分析问题：").append(safe(step.question)).append("\n")
                        .append("- SQL 目的：").append(safe(step.sqlPurpose)).append("\n")
                        .append("- 建议图表：").append(safe(step.chartType)).append("\n")
                        .append("- 返回行数：").append(step.rowCount == null ? 0 : step.rowCount).append("\n");
                if (step.rows != null && !step.rows.isEmpty()) {
                    sb.append("- 关键数据（前5行）：\n");
                    int limit = Math.min(5, step.rows.size());
                    for (int i = 0; i < limit; i++) {
                        sb.append("  ").append(step.rows.get(i)).append("\n");
                    }
                }
                sb.append("- AI 解读：").append(safe(step.interpretation)).append("\n\n");
            }
            String content = llmClient.chat(REPORT_SYSTEM, sb.toString(), modelRouter.resolve("REPORT", modelConfigId));
            return content == null ? "" : content.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String reportByRule(AgentPlan plan, List<PlanStep> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.getTitle()).append("分析报告\n\n## 分析目标\n").append(safe(plan.getGoal())).append("\n\n");
        sb.append("## 查询过程与图表结论\n");
        for (PlanStep step : steps) {
            sb.append("### ").append(step.stepNo).append(". ").append(safe(step.name)).append("\n")
                    .append("- 分析问题：").append(safe(step.question)).append("\n")
                    .append("- SQL 目的：").append(safe(step.sqlPurpose)).append("\n")
                    .append("- 执行结果：").append("SUCCESS".equals(step.status) ? "返回 " + (step.rowCount == null ? 0 : step.rowCount) + " 行" : "失败：" + safe(step.error)).append("\n");
            if (step.interpretation != null && !step.interpretation.isBlank()) {
                sb.append("- 数据解读：").append(step.interpretation).append("\n");
            }
        }
        sb.append("\n## 关键洞察\n");
        sb.append("各步骤均已执行，整体结论请结合上方图表与数据解读。\n\n");
        sb.append("## 行动建议\n1. 结合各步骤图表结论，优先处理增长/占比异常项；\n2. 对关键指标建立持续跟踪与对比。\n");
        return sb.toString();
    }

    public List<Map<String, Object>> buildCharts(List<PlanStep> steps) {
        List<Map<String, Object>> charts = new ArrayList<>();
        for (PlanStep step : steps) {
            if (!"SUCCESS".equals(step.status) || step.columns == null || step.columns.isEmpty()) {
                continue;
            }
            String blocked = chartBlockedReason(step);
            int count = step.rowCount == null ? (step.rows == null ? 0 : step.rows.size()) : step.rowCount;
            Map<String, Object> chart = new LinkedHashMap<>();
            chart.put("stepNo", step.stepNo);
            chart.put("stepName", safe(step.name));
            chart.put("chartType", safe(step.chartType));
            chart.put("title", safe(step.targetTable) + "「" + safe(step.name) + "」");
            chart.put("columns", step.columns);
            chart.put("rows", blocked == null ? (step.rows == null ? List.of() : step.rows) : List.of());
            if (blocked != null) {
                chart.put("count", count);
                chart.put("dataStatus", "blocked");
                chart.put("blockedReason", blocked);
                chart.put("blockedText", blockedText(step, blocked, count));
            }
            charts.add(chart);
        }
        return charts;
    }

    /** 语义校验：0 行 / 命中演示业务表 / 指标错配时返回阻断原因，否则返回 null（正常出图）。 */
    private static String chartBlockedReason(PlanStep step) {
        int count = step.rowCount == null ? (step.rows == null ? 0 : step.rows.size()) : step.rowCount;
        if (count <= 0) {
            return "NO_DATA";
        }
        String table = (step.targetTable == null ? "" : step.targetTable).toLowerCase(Locale.ROOT);
        if (DEMO_TABLES.contains(table)) {
            return "WRONG_TABLE";
        }
        String sql = (step.sql == null ? "" : step.sql).toLowerCase(Locale.ROOT);
        String ask = ((step.question == null ? "" : step.question) + " " + (step.name == null ? "" : step.name))
                .toLowerCase(Locale.ROOT);
        for (int i = 0; i < METRIC_TRIGGERS.size(); i++) {
            if (matchesAny(ask, METRIC_TRIGGERS.get(i)) && !matchesAny(sql, METRIC_SQL_HINTS.get(i))) {
                return "QUERY_MISMATCH";
            }
        }
        return null;
    }

    private static String blockedText(PlanStep step, String reason, int count) {
        String name = safe(step.name);
        switch (reason) {
            case "NO_DATA":
                return "查询无返回数据（0 行），可能该期间指标尚未发布或口径不匹配，已隐藏图表。详见正文步骤说明。";
            case "WRONG_TABLE":
                return "查询结果与目标指标不匹配：实际命中业务表「" + safe(step.targetTable) + "」（返回 " + count
                        + " 行），与步骤目标「" + name + "」不符，已隐藏图表。详见正文步骤说明。";
            default:
                return "查询结果与目标指标不匹配：SQL 未命中目标指标（返回 " + count + " 行），与步骤目标「" + name
                        + "」不符，已隐藏图表。详见正文步骤说明。";
        }
    }

    // ---------- 工具 ----------

    private void persist(AgentPlan plan, List<PlanStep> steps) {
        plan.setStepsJson(toJson(steps));
        planMapper.update(plan);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private JsonNode extractJsonArray(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                return objectMapper.readTree(raw.substring(start, end + 1));
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String normalizeChart(String chartType) {
        String c = chartType == null ? "" : chartType.toLowerCase(Locale.ROOT);
        if (c.contains("line") || c.contains("折线")) {
            return "line";
        }
        if (c.contains("bar") || c.contains("柱")) {
            return "bar";
        }
        if (c.contains("pie") || c.contains("饼")) {
            return "pie";
        }
        return "table";
    }

    private static boolean matchesAny(String lower, List<String> keywords) {
        for (String k : keywords) {
            if (lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static String defaultTitle(String goal) {
        return shortName(goal) + "分析计划";
    }

    private static String shortName(String goal) {
        String g = goal == null ? "" : goal.trim();
        return g.length() <= MAX_TITLE_LEN ? g : g.substring(0, MAX_TITLE_LEN);
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "-";
        }
        return String.join("、", list);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
