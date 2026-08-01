package com.aiagent.controller;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.IntentRecognizer;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.interpreter.DataInterpreter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.planner.AnalysisPlanner;
import com.aiagent.ai.recommender.FollowupRecommender;
import com.aiagent.ai.sql.SqlGenerator;
import com.aiagent.ai.sql.SqlValidator;
import com.aiagent.dto.AnalysisExecuteRequest;
import com.aiagent.dto.AnalysisParseRequest;
import com.aiagent.dto.ApiResponse;
import com.aiagent.entity.AnalysisSession;
import com.aiagent.service.AnalysisTraceService;
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

/** 数据分析入口：意图识别 + 分析计划 + Text-to-SQL 生成校验 + SQL 受控执行与步骤追踪。 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final int TITLE_MAX_LENGTH = 50;

    private final IntentRecognizer intentRecognizer;
    private final AnalysisPlanner analysisPlanner;
    private final SqlGenerator sqlGenerator;
    private final SqlValidator sqlValidator;
    private final SqlExecutor sqlExecutor;
    private final DataInterpreter dataInterpreter;
    private final FollowupRecommender followupRecommender;
    private final AnalysisTraceService analysisTraceService;
    private final ObjectMapper objectMapper;

    public AnalysisController(IntentRecognizer intentRecognizer, AnalysisPlanner analysisPlanner,
                              SqlGenerator sqlGenerator, SqlValidator sqlValidator,
                              SqlExecutor sqlExecutor, DataInterpreter dataInterpreter,
                              FollowupRecommender followupRecommender, AnalysisTraceService analysisTraceService,
                              ObjectMapper objectMapper) {
        this.intentRecognizer = intentRecognizer;
        this.analysisPlanner = analysisPlanner;
        this.sqlGenerator = sqlGenerator;
        this.sqlValidator = sqlValidator;
        this.sqlExecutor = sqlExecutor;
        this.dataInterpreter = dataInterpreter;
        this.followupRecommender = followupRecommender;
        this.analysisTraceService = analysisTraceService;
        this.objectMapper = objectMapper;
    }

    /** 自然语言解析：识别分析意图并生成结构化分析计划。 */
    @PostMapping("/parse")
    public ResponseEntity<ApiResponse<Map<String, Object>>> parse(@Valid @RequestBody AnalysisParseRequest request) {
        RecognizedIntent intent = intentRecognizer.recognize(request.getText());
        AnalysisPlan plan = analysisPlanner.buildPlan(intent);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", intent);
        data.put("plan", plan);
        return ResponseEntity.ok(ApiResponse.success("解析成功", data));
    }

    /** Text-to-SQL：意图识别 → 计划 → 规则 SQL 生成 → 安全校验。校验失败仍返回 422 业务码与四键 data。 */
    @PostMapping("/sql")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sql(@Valid @RequestBody AnalysisParseRequest request) {
        RecognizedIntent intent = intentRecognizer.recognize(request.getText());
        AnalysisPlan plan = analysisPlanner.buildPlan(intent);
        SqlGenerator.GeneratedSql generated = sqlGenerator.generate(plan, intent);
        SqlValidator.ValidationResult validation = sqlValidator.validate(generated.sql(), plan.getTargetTable());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intent", intent);
        data.put("plan", plan);
        data.put("sql", generated.sql());
        data.put("validation", validation);

        if (validation.valid()) {
            return ResponseEntity.ok(ApiResponse.success("SQL 生成成功", data));
        }
        return ResponseEntity.ok(new ApiResponse<>(422, "SQL 校验未通过", data));
    }

    /** 全链路执行：会话（覆盖式复用）→ 意图 → 计划 → SQL → 校验 → 受控执行 → AI 解读与推荐追问，每步落库追踪。 */
    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> execute(@Valid @RequestBody AnalysisExecuteRequest request,
                                                                    Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        String text = request.getText();
        String title = text.length() > TITLE_MAX_LENGTH ? text.substring(0, TITLE_MAX_LENGTH) : text;

        AnalysisSession session = analysisTraceService.startOrReuse(userId, request.getSessionId(), title);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());

        long start = System.currentTimeMillis();
        RecognizedIntent intent = intentRecognizer.recognize(text);
        analysisTraceService.appendStep(session.getId(), 1, "INTENT", toJson(text), toJson(intent),
                "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("intent", intent);

        start = System.currentTimeMillis();
        AnalysisPlan plan = analysisPlanner.buildPlan(intent);
        analysisTraceService.appendStep(session.getId(), 2, "PLAN", toJson(intent), toJson(plan),
                "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("plan", plan);

        start = System.currentTimeMillis();
        SqlGenerator.GeneratedSql generated = sqlGenerator.generate(plan, intent);
        analysisTraceService.appendStep(session.getId(), 3, "SQL", toJson(plan), toJson(generated),
                "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("sql", generated.sql());

        start = System.currentTimeMillis();
        SqlValidator.ValidationResult validation = sqlValidator.validate(generated.sql(), plan.getTargetTable());
        if (validation.valid()) {
            analysisTraceService.appendStep(session.getId(), 4, "VALIDATE", toJson(generated.sql()), toJson(validation),
                    "SUCCESS", null, System.currentTimeMillis() - start);
        } else {
            analysisTraceService.appendStep(session.getId(), 4, "VALIDATE", toJson(generated.sql()), toJson(validation),
                    "FAILED", String.join("; ", validation.errors()), System.currentTimeMillis() - start);
            data.put("validation", validation);
            return ResponseEntity.ok(new ApiResponse<>(422, "SQL 校验未通过", data));
        }
        data.put("validation", validation);

        start = System.currentTimeMillis();
        SqlExecutor.ExecutionResult execution;
        try {
            execution = sqlExecutor.execute(generated.sql());
        } catch (SqlExecutor.SqlExecutionException e) {
            analysisTraceService.appendStep(session.getId(), 5, "EXECUTE", toJson(generated.sql()), null,
                    "FAILED", e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
        analysisTraceService.appendStep(session.getId(), 5, "EXECUTE", toJson(generated.sql()), toJson(execution),
                "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("execution", execution);
        data.put("chartType", plan.getChartType());

        start = System.currentTimeMillis();
        DataInterpreter.Interpretation interpretation = dataInterpreter.interpret(plan, intent, execution);
        List<String> followups = followupRecommender.recommend(text, intent, plan, execution);
        Map<String, Object> interpretOutput = new LinkedHashMap<>();
        interpretOutput.put("interpretation", interpretation);
        interpretOutput.put("followups", followups);
        analysisTraceService.appendStep(session.getId(), 7, "INTERPRET",
                toJson(Map.of("rowCount", execution.rowCount(), "columns", execution.columns())),
                toJson(interpretOutput), "SUCCESS", null, System.currentTimeMillis() - start);
        data.put("interpretation", interpretation);
        data.put("followups", followups);
        return ResponseEntity.ok(ApiResponse.success("分析执行成功", data));
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