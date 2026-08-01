package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM Text-to-SQL 生成器（@Primary，SqlGenerator 接口的默认实现）。
 * 回退链：LLM 未配置 → 规则生成；LLM 输出校验失败 → 带错误重试一次 → 仍失败回退规则生成。
 */
@Component
@Primary
public class LlmSqlGenerator implements SqlGenerator {

    private static final String SYSTEM_PROMPT =
            "你是资深数据分析师，根据给定的数据表元数据生成一条只读 SELECT SQL。"
                    + "只输出 SQL 本身，不要任何解释、不要 markdown 代码块、不要分号结尾。";

    private final LlmClient llmClient;
    private final SqlValidator sqlValidator;
    private final RuleSqlGenerator ruleSqlGenerator;
    private final MetadataService metadataService;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    public LlmSqlGenerator(LlmClient llmClient, SqlValidator sqlValidator, RuleSqlGenerator ruleSqlGenerator,
                           MetadataService metadataService, ModelRouter modelRouter, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.sqlValidator = sqlValidator;
        this.ruleSqlGenerator = ruleSqlGenerator;
        this.metadataService = metadataService;
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    public GeneratedSql generate(AnalysisPlan plan, RecognizedIntent intent) {
        if (!llmClient.isConfigured()) {
            return ruleSqlGenerator.generate(plan, intent);
        }
        String system = SYSTEM_PROMPT;
        String user = buildUserPrompt(metadataService.buildMetadataText(), plan, intent);
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
        String intentName = intent == null ? "" : safe(intent.getIntentName());
        String intentType = intent == null ? "" : safe(intent.getIntentType());
        String targetTable = plan == null ? "" : safe(plan.getTargetTable());
        String metrics = plan == null || plan.getMetrics() == null ? "" : String.valueOf(plan.getMetrics());
        String dimensions = plan == null || plan.getDimensions() == null ? "" : String.valueOf(plan.getDimensions());
        String timeRange = plan == null ? "" : safe(plan.getTimeRange());
        return safe(metadataText) + "\n分析意图: " + intentName + "（" + intentType + "），目标表: " + targetTable
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