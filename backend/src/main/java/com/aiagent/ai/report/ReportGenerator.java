package com.aiagent.ai.report;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 报告生成器：LLM 优先，未配置 key 预检直接降级；
 * 异常/空白输出回退业务模板（按意图匹配，含政务变体）；
 * 无匹配意图回退极简数据报告。全程复用已执行结果，不重跑 SQL，不向上抛错。
 */
@Component
public class ReportGenerator {

    private static final String GOV_KEYWORD = "政务公开";
    private static final String GOV_TABLE = "GOV_INFO_RECORD";
    private static final int SUMMARY_ROW_LIMIT = 10;

    private static final String SYSTEM_PROMPT =
            "你是资深数据分析师。根据给定的分析意图、指标口径与查询结果，用中文输出一份 Markdown 数据分析报告，"
                    + "必须包含三个部分：## 概述、## 数据要点（引用关键数字）、## 结论与建议；"
                    + "报告用 # 一级标题，只输出报告正文，不要多余解释。";

    /** 报告结果：content 为 Markdown 正文，generatorType 为 LLM 或 RULE，templateName 为规则模板名（LLM 时为 null）。 */
    public record ReportResult(String title, String content, String generatorType, String templateName) {
    }

    private final LlmClient llmClient;
    private final MetadataService metadataService;
    private final ModelRouter modelRouter;

    public ReportGenerator(LlmClient llmClient, MetadataService metadataService, ModelRouter modelRouter) {
        this.llmClient = llmClient;
        this.metadataService = metadataService;
        this.modelRouter = modelRouter;
    }

    /** 生成报告：LLM 优先；未配置/异常/空白输出一律回退规则模板，不向上抛错。 */
    public ReportResult generate(AnalysisPlan plan, RecognizedIntent intent, SqlExecutor.ExecutionResult execution,
                                 String interpretationText, String question) {
        if (!llmClient.isConfigured()) {
            return fallback(plan, intent, execution, interpretationText);
        }
        try {
            String metadataText = isGov(plan, intent) ? "" : metadataService.buildMetadataText();
            String user = buildUserPrompt(metadataText, plan, intent, execution, interpretationText, question);
            String content = llmClient.chat(SYSTEM_PROMPT, user, modelRouter.resolve("REPORT"));
            if (content == null || content.isBlank()) {
                return fallback(plan, intent, execution, interpretationText);
            }
            return new ReportResult(buildTitle(plan, intent), content.trim(), "LLM", null);
        } catch (RuntimeException e) {
            return fallback(plan, intent, execution, interpretationText);
        }
    }

    /** 规则降级：优先业务模板，无匹配意图回退极简数据报告。 */
    public ReportResult fallback(AnalysisPlan plan, RecognizedIntent intent, SqlExecutor.ExecutionResult execution,
                                 String interpretationText) {
        String type = intent == null || intent.getIntentType() == null ? "GENERAL" : intent.getIntentType();
        String templateName = TEMPLATE_TYPES.contains(type.toUpperCase(Locale.ROOT)) ? type.toUpperCase(Locale.ROOT) : "MINIMAL";
        String content = "MINIMAL".equals(templateName)
                ? buildMinimalReport(plan, execution)
                : buildTemplateReport(plan, intent, execution, interpretationText, templateName);
        return new ReportResult(buildTitle(plan, intent), content, "RULE", templateName);
    }

    private static final List<String> TEMPLATE_TYPES = List.of(
            "SALES_TREND", "RANKING", "STRUCTURE", "COMPARISON", "USER_PROFILE", "RETENTION", "ANOMALY", "GENERAL");

    /** 组装 LLM 用户 Prompt：指标口径（非政务）+ 意图/计划 + 解读参考 + 查询结果摘要。 */
    static String buildUserPrompt(String metadataText, AnalysisPlan plan, RecognizedIntent intent,
                                  SqlExecutor.ExecutionResult execution, String interpretationText, String question) {
        String intentName = intent == null ? "" : safe(intent.getIntentName());
        String intentType = intent == null ? "" : safe(intent.getIntentType());
        String targetTable = plan == null ? "" : safe(plan.getTargetTable());
        String tableComment = plan == null ? "" : safe(plan.getTableComment());
        String metrics = plan == null || plan.getMetrics() == null ? "" : String.valueOf(plan.getMetrics());
        String dimensions = plan == null || plan.getDimensions() == null ? "" : String.valueOf(plan.getDimensions());
        String timeRange = plan == null ? "" : safe(plan.getTimeRange());
        String chartType = plan == null ? "" : safe(plan.getChartType());
        StringBuilder sb = new StringBuilder();
        if (metadataText != null && !metadataText.isBlank()) {
            sb.append(metadataText).append("\n");
        }
        sb.append("分析目标原文: ").append(safe(question)).append("\n");
        sb.append("分析意图: ").append(intentName).append("（").append(intentType)
                .append("），目标表: ").append(targetTable).append("（").append(tableComment)
                .append("），指标: ").append(metrics)
                .append("，维度: ").append(dimensions)
                .append("，时间范围: ").append(timeRange)
                .append("，推荐图表: ").append(chartType).append("\n");
        if (interpretationText != null && !interpretationText.isBlank()) {
            sb.append("AI 解读参考: ").append(interpretationText.trim()).append("\n");
        }
        sb.append("查询结果摘要:\n").append(buildRowsSummary(execution));
        return sb.toString();
    }

    /** 行数据摘要：行数 + 列名 + 前 10 行（值超 60 字符截断）。 */
    static String buildRowsSummary(SqlExecutor.ExecutionResult execution) {
        List<String> cols = execution == null || execution.columns() == null ? List.of() : execution.columns();
        List<Map<String, Object>> rows = execution == null || execution.rows() == null ? List.of() : execution.rows();
        StringBuilder sb = new StringBuilder();
        sb.append("共").append(rows.size()).append("行，列: ").append(cols).append("\n");
        int limit = Math.min(rows.size(), SUMMARY_ROW_LIMIT);
        for (int i = 0; i < limit; i++) {
            sb.append(i).append(": ").append(formatRow(rows.get(i))).append("\n");
        }
        if (rows.size() > SUMMARY_ROW_LIMIT) {
            sb.append("…（共").append(rows.size()).append("行，仅展示前").append(SUMMARY_ROW_LIMIT).append("行）");
        }
        return sb.toString().trim();
    }

    /** 业务模板报告：概述 + 数据要点 + 结论与建议（按意图/政务变体）。 */
    static String buildTemplateReport(AnalysisPlan plan, RecognizedIntent intent,
                                      SqlExecutor.ExecutionResult execution, String interpretationText, String type) {
        boolean gov = isGov(plan, intent);
        int rowCount = execution == null || execution.rows() == null ? 0 : execution.rows().size();
        String rowsSummary = buildRowsSummary(execution);
        String interp = interpretationText == null || interpretationText.isBlank()
                ? "暂无独立解读，可参考上方数据要点。"
                : interpretationText.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("## 概述\n");
        sb.append("围绕「").append(intentNameOf(intent)).append("」，本次基于 ").append(tableOf(plan))
                .append(" 共查询 ").append(rowCount).append(" 行数据");
        sb.append("（指标：").append(metricsOf(plan)).append("；维度：").append(dimensionsOf(plan))
                .append("；时间范围：").append(timeRangeOf(plan)).append("）");
        sb.append("，推荐图表类型为 ").append(chartTypeOf(plan)).append("。\n\n");
        sb.append("## 数据要点\n").append(rowsSummary).append("\n\n");
        sb.append("## 结论与建议\n").append(interp);
        String advice = adviceFor(type, gov);
        if (!advice.isBlank()) {
            sb.append("\n").append(advice);
        }
        return sb.toString();
    }

    /** 极简数据报告：无匹配业务模板时自动组装数据表与图表信息。 */
    static String buildMinimalReport(AnalysisPlan plan, SqlExecutor.ExecutionResult execution) {
        List<String> cols = execution == null || execution.columns() == null ? List.of() : execution.columns();
        int rowCount = execution == null || execution.rows() == null ? 0 : execution.rows().size();
        StringBuilder sb = new StringBuilder();
        sb.append("## 概述\n");
        sb.append("本报告由规则引擎生成（未匹配业务模板），基于 ").append(tableOf(plan))
                .append(" 共查询 ").append(rowCount).append(" 行数据，推荐图表类型为 ")
                .append(chartTypeOf(plan)).append("。\n\n");
        sb.append("## 数据要点\n");
        sb.append("列：").append(cols).append("\n");
        sb.append(buildRowsSummary(execution)).append("\n\n");
        sb.append("## 结论与建议\n");
        if (rowCount == 0) {
            sb.append("当前查询结果为空，建议调整时间范围或筛选条件后重新执行分析。");
        } else {
            sb.append("建议调整分析目标关键词，或补充指标口径后重新生成报告，以获得更贴合业务的分析结论。");
        }
        return sb.toString();
    }

    /** 意图类型 → 建议文案（政务变体优先）。 */
    static String adviceFor(String type, boolean gov) {
        return switch (type) {
            case "SALES_TREND" -> gov
                    ? "建议重点跟踪发文量变化较大的月份，排查集中发文或断更原因。"
                    : "建议结合品类与渠道拆分，定位销售额增长的主要驱动因素。";
            case "RANKING" -> gov
                    ? "建议对发文量领先与垫底单位进行对比，优化公开工作均衡性。"
                    : "建议关注头部与尾部差距，优化品类与渠道结构。";
            case "STRUCTURE" -> gov
                    ? "建议优化占比偏低类目的公开供给，提升信息公开均衡度。"
                    : "建议结合客单价与占比，评估品类结构健康度。";
            case "COMPARISON" -> "建议进一步下钻维度，验证差异的稳定性与成因。";
            case "USER_PROFILE" -> "建议结合渠道与地域，制定差异化的用户运营策略。";
            case "RETENTION" -> "建议针对留存偏低的群体开展专项召回分析。";
            case "ANOMALY" -> "建议对偏差最大的记录重点核查，定位异常成因。";
            case "GENERAL" -> gov
                    ? "建议按单位与类目持续跟踪发文情况，形成月度政务公开简报。"
                    : "建议按日期、区域与类目进一步下钻，形成多维度分析。";
            default -> "";
        };
    }

    /** 报告标题：优先意图名称。 */
    static String buildTitle(AnalysisPlan plan, RecognizedIntent intent) {
        String name = intentNameOf(intent);
        return "「" + name + "」分析报告";
    }

    private static String intentNameOf(RecognizedIntent intent) {
        if (intent == null || intent.getIntentName() == null || intent.getIntentName().isBlank()) {
            return "数据分析";
        }
        return intent.getIntentName();
    }

    private static String metricsOf(AnalysisPlan plan) {
        return plan == null || plan.getMetrics() == null ? "-" : String.join("、", plan.getMetrics());
    }

    private static String dimensionsOf(AnalysisPlan plan) {
        return plan == null || plan.getDimensions() == null ? "-" : String.join("、", plan.getDimensions());
    }

    private static String timeRangeOf(AnalysisPlan plan) {
        return plan == null ? "-" : safe(plan.getTimeRange());
    }

    private static String chartTypeOf(AnalysisPlan plan) {
        return plan == null ? "-" : safe(plan.getChartType());
    }

    private static String tableOf(AnalysisPlan plan) {
        if (plan == null) {
            return "-";
        }
        String table = safe(plan.getTargetTable());
        String comment = safe(plan.getTableComment());
        return comment.isBlank() ? table : table + "（" + comment + "）";
    }

    /** 政务类判断：命中关键词或目标表为 GOV_INFO_RECORD。 */
    static boolean isGov(AnalysisPlan plan, RecognizedIntent intent) {
        if (intent != null && intent.getMatchedKeywords() != null
                && intent.getMatchedKeywords().contains(GOV_KEYWORD)) {
            return true;
        }
        return plan != null && GOV_TABLE.equalsIgnoreCase(safe(plan.getTargetTable()));
    }

    private static String formatRow(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder("{");
        if (row != null) {
            int i = 0;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append("=").append(truncate(String.valueOf(e.getValue())));
                i++;
            }
        }
        return sb.append("}").toString();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 60 ? value.substring(0, 60) + "…" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}