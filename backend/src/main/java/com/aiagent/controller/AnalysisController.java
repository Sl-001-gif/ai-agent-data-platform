package com.aiagent.controller;

import com.aiagent.ai.context.AnalysisContextBuilder;
import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.IntentRecognizer;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.interpreter.DataInterpreter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.planner.AnalysisPlanner;
import com.aiagent.ai.recommender.FollowupRecommender;
import com.aiagent.ai.report.ReportGenerator;
import com.aiagent.ai.sql.SqlGenerator;
import com.aiagent.ai.sql.SqlValidator;
import com.aiagent.ai.validate.DataSanityChecker;
import com.aiagent.dto.AnalysisExecuteRequest;
import com.aiagent.dto.AnalysisParseRequest;
import com.aiagent.dto.AnalysisReportRequest;
import com.aiagent.dto.ApiResponse;
import com.aiagent.entity.AnalysisReport;
import com.aiagent.entity.AnalysisSession;
import com.aiagent.entity.AnalysisStep;
import com.aiagent.mapper.AnalysisReportMapper;
import com.aiagent.mapper.AnalysisStepMapper;
import com.aiagent.service.AnalysisTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据分析入口：意图识别 + 分析计划 + Text-to-SQL 生成校验 + SQL 受控执行与多轮步骤追踪 + AI 解读/追问/报告。 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    /** 意图置信度下限：低于该值视为无法识别（拒绝执行并提示用户补充问题）。 */
    private static final double MIN_INTENT_CONFIDENCE = 0.35;

    private final IntentRecognizer intentRecognizer;
    private final AnalysisPlanner analysisPlanner;
    private final SqlGenerator sqlGenerator;
    private final SqlValidator sqlValidator;
    private final SqlExecutor sqlExecutor;
    private final DataInterpreter dataInterpreter;
    private final FollowupRecommender followupRecommender;
    private final AnalysisTraceService analysisTraceService;
    private final AnalysisContextBuilder analysisContextBuilder;
    private final AnalysisStepMapper stepMapper;
    private final AnalysisReportMapper reportMapper;
    private final ReportGenerator reportGenerator;
    private final DataSanityChecker dataSanityChecker;
    private final ObjectMapper objectMapper;

    public AnalysisController(IntentRecognizer intentRecognizer, AnalysisPlanner analysisPlanner,
                              SqlGenerator sqlGenerator, SqlValidator sqlValidator,
                              SqlExecutor sqlExecutor, DataInterpreter dataInterpreter,
                              FollowupRecommender followupRecommender, AnalysisTraceService analysisTraceService,
                              AnalysisContextBuilder analysisContextBuilder, AnalysisStepMapper stepMapper,
                              AnalysisReportMapper reportMapper, ReportGenerator reportGenerator,
                              DataSanityChecker dataSanityChecker, ObjectMapper objectMapper) {
        this.intentRecognizer = intentRecognizer;
        this.analysisPlanner = analysisPlanner;
        this.sqlGenerator = sqlGenerator;
        this.sqlValidator = sqlValidator;
        this.sqlExecutor = sqlExecutor;
        this.dataInterpreter = dataInterpreter;
        this.followupRecommender = followupRecommender;
        this.analysisTraceService = analysisTraceService;
        this.analysisContextBuilder = analysisContextBuilder;
        this.stepMapper = stepMapper;
        this.reportMapper = reportMapper;
        this.reportGenerator = reportGenerator;
        this.dataSanityChecker = dataSanityChecker;
        this.objectMapper = objectMapper;
    }

    /** 自然语言解析：识别意图并生成计划，新建/复用会话并落库 INTENT + PLAN 步骤（一轮会话可含多次分析）。 */
    @PostMapping("/parse")
    public ResponseEntity<ApiResponse<Map<String, Object>>> parse(@Valid @RequestBody AnalysisParseRequest request,
                                                                  Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        String text = request.getText();
        RecognizedIntent intent = intentRecognizer.recognize(text);
        if (tooAmbiguous(text, intent)) {
            return ResponseEntity.ok(new ApiResponse<>(422, "无法识别分析意图（内容过短或置信度过低），请用更明确的业务问题重新描述，例如：分析邵阳最近3年GDP变化",
                    Map.of("intentType", intent.getIntentType(), "confidence", intent.getConfidence())));
        }
        AnalysisTraceService.StartResult startResult =
                analysisTraceService.startOrReuse(userId, request.getSessionId(), request.getTitle(),
                        request.getAnalysisGoal(), request.getDatasetId(), text);
        AnalysisSession session = startResult.session();
        Integer roundNo = startResult.roundNo();
        Long effectiveDatasetId = request.getDatasetId() != null ? request.getDatasetId() : session.getDatasetId();

        long start = System.currentTimeMillis();
        analysisTraceService.appendStep(session.getId(), roundNo, 1, "INTENT", toJson(text), toJson(intent),
                "SUCCESS", null, System.currentTimeMillis() - start);

        start = System.currentTimeMillis();
        AnalysisPlan plan = analysisPlanner.buildPlan(intent, text, effectiveDatasetId);
        analysisTraceService.appendStep(session.getId(), roundNo, 2, "PLAN", toJson(intent), toJson(plan),
                "SUCCESS", null, System.currentTimeMillis() - start);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("roundNo", roundNo);
        data.put("datasetId", session.getDatasetId());
        data.put("intent", intent);
        data.put("plan", plan);
        data.put("sqlExplanation", sqlExplanation(plan, intent));
        return ResponseEntity.ok(ApiResponse.success("解析成功", data));
    }

    /** Text-to-SQL：意图识别 → 计划 → SQL 生成 → 安全校验，新建/复用会话并落库 INTENT/PLAN/SQL/VALIDATE 步骤。校验失败仍返回 422 业务码与 data。 */
    @PostMapping("/sql")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sql(@Valid @RequestBody AnalysisParseRequest request,
                                                                Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        String text = request.getText();
        RecognizedIntent intent = intentRecognizer.recognize(text);
        if (tooAmbiguous(text, intent)) {
            return ResponseEntity.ok(new ApiResponse<>(422, "无法识别分析意图（内容过短或置信度过低），请用更明确的业务问题重新描述，例如：分析邵阳最近3年GDP变化",
                    Map.of("intentType", intent.getIntentType(), "confidence", intent.getConfidence())));
        }
        AnalysisTraceService.StartResult startResult =
                analysisTraceService.startOrReuse(userId, request.getSessionId(), request.getTitle(),
                        request.getAnalysisGoal(), request.getDatasetId(), text);
        AnalysisSession session = startResult.session();
        Integer roundNo = startResult.roundNo();
        Long effectiveDatasetId = request.getDatasetId() != null ? request.getDatasetId() : session.getDatasetId();

        long start = System.currentTimeMillis();
        analysisTraceService.appendStep(session.getId(), roundNo, 1, "INTENT", toJson(text), toJson(intent),
                "SUCCESS", null, System.currentTimeMillis() - start);

        start = System.currentTimeMillis();
        AnalysisPlan plan = analysisPlanner.buildPlan(intent, text, effectiveDatasetId);
        analysisTraceService.appendStep(session.getId(), roundNo, 2, "PLAN", toJson(intent), toJson(plan),
                "SUCCESS", null, System.currentTimeMillis() - start);

        start = System.currentTimeMillis();
        SqlGenerator.GeneratedSql generated = sqlGenerator.generate(plan, intent, text);
        analysisTraceService.appendStep(session.getId(), roundNo, 3, "SQL", toJson(plan), toJson(generated),
                "SUCCESS", null, System.currentTimeMillis() - start);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("roundNo", roundNo);
        data.put("datasetId", session.getDatasetId());
        data.put("intent", intent);
        data.put("plan", plan);
        data.put("sql", generated.sql());
        data.put("sqlExplanation", sqlExplanation(plan, intent));

        start = System.currentTimeMillis();
        SqlValidator.ValidationResult validation = sqlValidator.validate(generated.sql(), plan.getTargetTable());
        if (validation.valid()) {
            analysisTraceService.appendStep(session.getId(), roundNo, 4, "VALIDATE", toJson(generated.sql()),
                    toJson(validation), "SUCCESS", null, System.currentTimeMillis() - start);
        } else {
            analysisTraceService.appendStep(session.getId(), roundNo, 4, "VALIDATE", toJson(generated.sql()),
                    toJson(validation), "FAILED", String.join("; ", validation.errors()), System.currentTimeMillis() - start);
        }
        data.put("validation", validation);

        if (validation.valid()) {
            return ResponseEntity.ok(ApiResponse.success("SQL 生成成功", data));
        }
        return ResponseEntity.ok(new ApiResponse<>(422, "SQL 校验未通过", data));
    }

    /**
     * Agent 分析计划：仅生成并落库 INTENT + PLAN 步骤（不执行 SQL）。
     * 会话为空时新建会话（轮次 1）；同问题重复生成覆盖该轮；不同问题追加新轮。
     */
    @PostMapping("/plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> plan(@Valid @RequestBody AnalysisParseRequest request,
                                                                 Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        String text = request.getText();
        RecognizedIntent intent = intentRecognizer.recognize(text);
        if (tooAmbiguous(text, intent)) {
            return ResponseEntity.ok(new ApiResponse<>(422, "无法识别分析意图（内容过短或置信度过低），请用更明确的业务问题重新描述，例如：分析邵阳最近3年GDP变化",
                    Map.of("intentType", intent.getIntentType(), "confidence", intent.getConfidence())));
        }
        AnalysisTraceService.StartResult startResult =
                analysisTraceService.startOrReuse(userId, request.getSessionId(), request.getTitle(),
                        request.getAnalysisGoal(), request.getDatasetId(), text);
        AnalysisSession session = startResult.session();
        Integer roundNo = startResult.roundNo();
        Long effectiveDatasetId = request.getDatasetId() != null ? request.getDatasetId() : session.getDatasetId();

        long start = System.currentTimeMillis();
        analysisTraceService.appendStep(session.getId(), roundNo, 1, "INTENT", toJson(text), toJson(intent),
                "SUCCESS", null, System.currentTimeMillis() - start);

        start = System.currentTimeMillis();
        AnalysisPlan plan = analysisPlanner.buildPlan(intent, text, effectiveDatasetId);
        analysisTraceService.appendStep(session.getId(), roundNo, 2, "PLAN", toJson(intent), toJson(plan),
                "SUCCESS", null, System.currentTimeMillis() - start);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("roundNo", roundNo);
        data.put("datasetId", session.getDatasetId());
        data.put("intent", intent);
        data.put("plan", plan);
        data.put("sqlExplanation", sqlExplanation(plan, intent));
        return ResponseEntity.ok(ApiResponse.success("计划生成成功", data));
    }

    /**
     * 全链路执行：会话（多轮复用，同问题覆盖该轮）→ 意图 → 计划 → SQL → 校验 → 受控执行 →
     * AI 解读（注入会话历史上下文）与推荐追问，每步按轮次落库追踪。
     */
    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> execute(@Valid @RequestBody AnalysisExecuteRequest request,
                                                                    Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        String text = request.getText();
        RecognizedIntent intent = intentRecognizer.recognize(text);
        if (tooAmbiguous(text, intent)) {
            return ResponseEntity.ok(new ApiResponse<>(422, "无法识别分析意图（内容过短或置信度过低），请用更明确的业务问题重新描述，例如：分析邵阳最近3年GDP变化",
                    Map.of("intentType", intent.getIntentType(), "confidence", intent.getConfidence())));
        }
        AnalysisTraceService.StartResult startResult =
                analysisTraceService.startOrReuse(userId, request.getSessionId(), request.getTitle(),
                        request.getAnalysisGoal(), request.getDatasetId(), text);
        AnalysisSession session = startResult.session();
        Integer roundNo = startResult.roundNo();
        Long effectiveDatasetId = request.getDatasetId() != null ? request.getDatasetId() : session.getDatasetId();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("roundNo", roundNo);
        data.put("datasetId", session.getDatasetId());

        long start = System.currentTimeMillis();
        analysisTraceService.appendStep(session.getId(), roundNo, 1, "INTENT", toJson(text), toJson(intent),
                "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("intent", intent);

        start = System.currentTimeMillis();
        AnalysisPlan plan = analysisPlanner.buildPlan(intent, text, effectiveDatasetId);
        analysisTraceService.appendStep(session.getId(), roundNo, 2, "PLAN", toJson(intent), toJson(plan),
                "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("plan", plan);
        data.put("sqlExplanation", sqlExplanation(plan, intent));

        start = System.currentTimeMillis();
        SqlGenerator.GeneratedSql generated = sqlGenerator.generate(plan, intent, text);
        SqlExecutor.ExecutionResult execution = null;
        for (int attempt = 0; ; attempt++) {
            long sqlStart = System.currentTimeMillis();
            analysisTraceService.appendStep(session.getId(), roundNo, 3, "SQL", toJson(plan), toJson(generated),
                    "SUCCESS", null, System.currentTimeMillis() - sqlStart);
            data.put("sql", generated.sql());

            start = System.currentTimeMillis();
            SqlValidator.ValidationResult validation = sqlValidator.validate(generated.sql(), plan.getTargetTable());
            if (validation.valid()) {
                analysisTraceService.appendStep(session.getId(), roundNo, 4, "VALIDATE", toJson(generated.sql()), toJson(validation),
                        "SUCCESS", null, System.currentTimeMillis() - start);
            } else {
                analysisTraceService.appendStep(session.getId(), roundNo, 4, "VALIDATE", toJson(generated.sql()), toJson(validation),
                        "FAILED", String.join("; ", validation.errors()), System.currentTimeMillis() - start);
                data.put("validation", validation);
                return ResponseEntity.ok(new ApiResponse<>(422, "SQL 校验未通过", data));
            }
            data.put("validation", validation);

            start = System.currentTimeMillis();
            try {
                execution = sqlExecutor.execute(generated.sql());
            } catch (SqlExecutor.SqlExecutionException e) {
                analysisTraceService.appendStep(session.getId(), roundNo, 5, "EXECUTE", toJson(generated.sql()), null,
                        "FAILED", e.getMessage(), System.currentTimeMillis() - start);
                throw e;
            }
            analysisTraceService.appendStep(session.getId(), roundNo, 5, "EXECUTE", toJson(generated.sql()), toJson(execution),
                    "SUCCESS", null, System.currentTimeMillis() - start);

            // 空结果纠错：首轮查询为空且带提示重生成的 SQL 有变化时，覆盖同轮 SQL/VALIDATE/EXECUTE 步骤重跑一次
            if (attempt == 0 && execution.rowCount() == 0) {
                SqlGenerator.GeneratedSql retried = sqlGenerator.generateWithHint(plan, intent, text,
                        "查询结果为空，请逐项自查后重新生成 SQL：1) 指定期别可能无数据，改用该指标最新存在期别（如部分效益指标为 1-8月）；2) region 必须使用元数据中的精确区域名（如 长株潭地区，严禁加括号/sheet 后缀）；3) 2019年及以前进出口请用 进出口（万美元）指标。保留真实 period 列，仅输出 SQL");
                if (retried != null && retried.sql() != null && !retried.sql().equals(generated.sql())) {
                    stepMapper.deleteBySessionIdAndRound(session.getId(), roundNo);
                    // 覆盖重跑前补回 INTENT/PLAN 步骤，保证 report 端步骤完整（此前仅重放 SQL/VALIDATE/EXECUTE 导致 INTENT/PLAN 丢失）
                    analysisTraceService.appendStep(session.getId(), roundNo, 1, "INTENT", toJson(text), toJson(intent),
                            "SUCCESS", null, 0L);
                    analysisTraceService.appendStep(session.getId(), roundNo, 2, "PLAN", toJson(intent), toJson(plan),
                            "SUCCESS", null, 0L);
                    generated = retried;
                    continue;
                }
            }
            data.put("execution", execution);
            data.put("chartType", plan.getChartType());
            break;
        }


        start = System.currentTimeMillis();
        List<String> dataWarnings = dataSanityChecker.check(execution);
        String dataCheckStatus = dataWarnings.isEmpty() ? "SUCCESS" : "WARNING";
        analysisTraceService.appendStep(session.getId(), roundNo, 6, "DATA_CHECK", toJson(execution),
                toJson(Map.of("warnings", dataWarnings)), dataCheckStatus,
                String.join("; ", dataWarnings), System.currentTimeMillis() - start);
        data.put("dataWarnings", dataWarnings);

        String historyContext = analysisContextBuilder.buildHistoryContext(session.getId(), roundNo, 3);
        start = System.currentTimeMillis();
        DataInterpreter.Interpretation interpretation = dataInterpreter.interpret(plan, intent, execution,
                request.getModelConfigId(), historyContext, session.getDatasetId());
        List<String> followups = followupRecommender.recommend(text, intent, plan, execution);
        Map<String, Object> interpretOutput = new LinkedHashMap<>();
        interpretOutput.put("interpretation", interpretation);
        interpretOutput.put("followups", followups);
        analysisTraceService.appendStep(session.getId(), roundNo, 7, "INTERPRET",
                toJson(Map.of("rowCount", execution.rowCount(), "columns", execution.columns())),
                toJson(interpretOutput), "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("interpretation", interpretation);
        data.put("followups", followups);
        return ResponseEntity.ok(ApiResponse.success("分析执行成功", data));
    }

    /** 报告生成：基于指定轮次（默认最近一轮）已落库步骤重建上下文，不重跑 SQL。 */
    @PostMapping("/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(@Valid @RequestBody AnalysisReportRequest request,
                                                                   Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        AnalysisSession session;
        try {
            session = analysisTraceService.validateOwnership(userId, request.getSessionId());
        } catch (RuntimeException e) {
            return ResponseEntity.ok(new ApiResponse<>(422, "会话不存在或无权访问",
                    Map.of("sessionId", request.getSessionId())));
        }

        Integer targetRound = request.getRoundNo() != null ? request.getRoundNo()
                : analysisTraceService.latestRound(session.getId());
        if (targetRound == null) {
            return ResponseEntity.ok(new ApiResponse<>(422, "请先执行分析后再生成报告",
                    Map.of("sessionId", session.getId())));
        }
        List<AnalysisStep> allSteps = stepMapper.selectBySessionId(session.getId());
        List<AnalysisStep> steps = allSteps == null ? List.of()
                : allSteps.stream().filter(s -> targetRound.equals(s.getRoundNo())).toList();
        if (steps.isEmpty()) {
            return ResponseEntity.ok(new ApiResponse<>(422, "该轮次暂无执行数据，请重新执行分析后再生成报告",
                    Map.of("sessionId", session.getId(), "roundNo", targetRound)));
        }
        AnalysisStep intentStep = findStep(steps, "INTENT");
        AnalysisStep planStep = findStep(steps, "PLAN");
        AnalysisStep executeStep = findStep(steps, "EXECUTE");
        AnalysisStep interpretStep = findStep(steps, "INTERPRET");
        if (intentStep == null || planStep == null || executeStep == null) {
            return ResponseEntity.ok(new ApiResponse<>(422, "请先执行分析后再生成报告",
                    Map.of("sessionId", session.getId())));
        }

        String question;
        RecognizedIntent intent;
        AnalysisPlan plan;
        SqlExecutor.ExecutionResult execution;
        String interpretationText;
        try {
            question = readText(intentStep.getInputData());
            intent = readValue(intentStep.getOutputData(), RecognizedIntent.class);
            plan = readValue(planStep.getOutputData(), AnalysisPlan.class);
            execution = readValue(executeStep.getOutputData(), SqlExecutor.ExecutionResult.class);
            interpretationText = readInterpretation(interpretStep);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(new ApiResponse<>(422, "执行数据不完整，请重新执行分析后再生成报告",
                    Map.of("sessionId", session.getId())));
        }

        String historyContext = analysisContextBuilder.buildHistoryContext(session.getId(), targetRound, 3);
        long start = System.currentTimeMillis();
        ReportGenerator.ReportResult report = reportGenerator.generate(plan, intent, execution, interpretationText, question,
                request.getModelConfigId(), historyContext, session.getDatasetId());

        reportMapper.deleteBySessionIdAndRound(session.getId(), targetRound);
        AnalysisReport entity = new AnalysisReport();
        entity.setSessionId(session.getId());
        entity.setRoundNo(targetRound);
        entity.setTitle(report.title());
        entity.setContent(report.content());
        entity.setStatus("DONE");
        entity.setCreateBy(userId);
        reportMapper.insert(entity);

        Map<String, Object> stepOutput = new LinkedHashMap<>();
        stepOutput.put("title", report.title());
        stepOutput.put("generatorType", report.generatorType());
        stepOutput.put("templateName", report.templateName());
        stepOutput.put("contentLength", report.content().length());
        analysisTraceService.appendStep(session.getId(), targetRound, 8, "REPORT",
                toJson(Map.of("question", question, "rowCount", execution.rowCount(), "columns", execution.columns())),
                toJson(stepOutput), "SUCCESS", null, System.currentTimeMillis() - start);

        Map<String, Object> reportData = new LinkedHashMap<>();
        reportData.put("id", entity.getId());
        reportData.put("roundNo", targetRound);
        reportData.put("title", report.title());
        reportData.put("content", report.content());
        reportData.put("generatorType", report.generatorType());
        reportData.put("templateName", report.templateName());
        reportData.put("chart", buildChartData(plan, execution));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("roundNo", targetRound);
        data.put("report", reportData);
        return ResponseEntity.ok(ApiResponse.success("报告生成成功", data));
    }

    /** 内容过短/无中文（纯乱码）或意图置信度过低 → 拒绝分析并引导用户补充明确业务问题。 */
    private boolean tooAmbiguous(String text, RecognizedIntent intent) {
        if (intent == null || intent.getConfidence() < MIN_INTENT_CONFIDENCE) {
            return true;
        }
        return text != null && !text.isBlank() && !containsCjk(text);
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    /** 报告图表数据：chartType + 列 + 行（前端 ECharts 渲染，行数封顶 500）。 */
    private Map<String, Object> buildChartData(AnalysisPlan plan, SqlExecutor.ExecutionResult execution) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", plan == null || plan.getChartType() == null ? "table" : plan.getChartType());
        List<String> metrics = plan == null || plan.getMetrics() == null ? List.of() : plan.getMetrics();
        String table = plan == null || plan.getTargetTable() == null || plan.getTargetTable().isBlank()
                ? "" : plan.getTargetTable();
        chart.put("title", table + (metrics.isEmpty() ? "" : "「" + String.join("、", metrics) + "」"));
        List<Map<String, Object>> rows = execution == null || execution.rows() == null ? List.of() : execution.rows();
        chart.put("columns", execution == null || execution.columns() == null ? List.of() : execution.columns());
        chart.put("rows", rows.size() > 500 ? rows.subList(0, 500) : rows);
        return chart;
    }

    /** 依据计划与意图生成 SQL 业务说明（供前端 SQL Tab 展示）。 */
    private String sqlExplanation(AnalysisPlan plan, RecognizedIntent intent) {
        String metrics = plan.getMetrics() == null || plan.getMetrics().isEmpty() ? "相关指标" : String.join("、", plan.getMetrics());
        String dimensions = plan.getDimensions() == null || plan.getDimensions().isEmpty() ? "整体" : String.join("、", plan.getDimensions());
        String timeRange = plan.getTimeRange() == null || plan.getTimeRange().isBlank() ? "指定期间" : plan.getTimeRange();
        String intentName = intent == null ? "" : safe(intent.getIntentName());
        return "意图「" + intentName + "」：统计「" + metrics + "」，按「" + dimensions + "」维度，覆盖" + timeRange + "（数据源：" + safe(plan.getTargetTable()) + "）。";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static AnalysisStep findStep(List<AnalysisStep> steps, String stepType) {
        if (steps == null) {
            return null;
        }
        for (AnalysisStep step : steps) {
            if (stepType.equals(step.getStepType())) {
                return step;
            }
        }
        return null;
    }

    /** 步骤 JSON 字符串解码：INTENT 的 input 是原文 JSON 字符串。 */
    private String readText(String json) {
        try {
            return objectMapper.readTree(json).asText();
        } catch (Exception e) {
            return json;
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("步骤数据解析失败: " + type.getSimpleName(), e);
        }
    }

    /** 从 INTERPRET 步骤输出提取解读正文。 */
    private String readInterpretation(AnalysisStep step) {
        if (step == null || step.getOutputData() == null) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(step.getOutputData());
            return node.path("interpretation").path("text").asText();
        } catch (Exception e) {
            return "";
        }
    }

    /** 步骤输入输出 JSON 序列化，失败回退 String.valueOf。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
