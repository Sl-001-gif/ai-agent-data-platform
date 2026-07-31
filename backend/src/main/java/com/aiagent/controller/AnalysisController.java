package com.aiagent.controller;

import com.aiagent.ai.intent.IntentRecognizer;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.planner.AnalysisPlanner;
import com.aiagent.ai.sql.SqlGenerator;
import com.aiagent.ai.sql.SqlValidator;
import com.aiagent.dto.AnalysisParseRequest;
import com.aiagent.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 数据分析入口：意图识别 + 分析计划 + Text-to-SQL 生成与校验。 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final IntentRecognizer intentRecognizer;
    private final AnalysisPlanner analysisPlanner;
    private final SqlGenerator sqlGenerator;
    private final SqlValidator sqlValidator;

    public AnalysisController(IntentRecognizer intentRecognizer, AnalysisPlanner analysisPlanner,
                              SqlGenerator sqlGenerator, SqlValidator sqlValidator) {
        this.intentRecognizer = intentRecognizer;
        this.analysisPlanner = analysisPlanner;
        this.sqlGenerator = sqlGenerator;
        this.sqlValidator = sqlValidator;
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
}