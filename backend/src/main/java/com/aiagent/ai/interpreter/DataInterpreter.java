package com.aiagent.ai.interpreter;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.metadata.MetadataService;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.prompt.PromptLoader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 数据解读：LLM 优先，无 key/异常/空输出时回退规则模板结论，接口永不失联。
 * 规则结论基于执行结果的首末值趋势、TOP1 汇总、占比、对比与整体区间。
 */
@Component
public class DataInterpreter {

    private static final String GOV_TABLE = "GOV_INFO_RECORD";
    private static final int SUMMARY_ROW_LIMIT = 20;
    private static final int VALUE_MAX_LENGTH = 60;
    private static final double RISE_THRESHOLD = 1.05;
    private static final double FALL_THRESHOLD = 0.95;

    private static final String SYSTEM_PROMPT =
            "你是资深数据分析师。根据给定的查询结果与指标口径，用中文输出不超过150字的分析结论，"
                    + "包含关键数字与趋势、占比或对比要点；只输出结论正文，不要标题、不要 markdown、不要多余解释。"
                    + "口径规则：period 为累计期别（1-3月/1-6月/1-9月/1-12月），累计值不得跨期别计算增幅，"
                    + "同比增幅须对比上年同期累计；城乡收入比 = 城镇居民÷农村居民，不得用全体居民作分母；"
                    + "金额统一千分位并保留 2 位小数，增速统一保留 1 位小数并加 %。";

    /** 解读结果：text 为结论正文，generatorType 为 LLM 或 RULE。 */
    public record Interpretation(String text, String generatorType) {
    }

    private final LlmClient llmClient;
    private final MetadataService metadataService;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public DataInterpreter(LlmClient llmClient, MetadataService metadataService, ModelRouter modelRouter,
                           PromptLoader promptLoader) {
        this.llmClient = llmClient;
        this.metadataService = metadataService;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    /** 生成解读：LLM 优先，未配置/异常/空输出一律回退规则模板，不向上抛错。 */
    public Interpretation interpret(AnalysisPlan plan, RecognizedIntent intent, SqlExecutor.ExecutionResult result) {
        return interpret(plan, intent, result, null, "", null);
    }

    /** 生成解读：modelConfigId 指定 LLM 模型（null 自动路由），historyContext 为会话历史上下文。 */
    public Interpretation interpret(AnalysisPlan plan, RecognizedIntent intent, SqlExecutor.ExecutionResult result,
                                  Long modelConfigId, String historyContext, Long datasetId) {
        if (!llmClient.isConfigured()) {
            return fallback(plan, intent, result);
        }
        // 0 行结果禁止走 LLM：防止模型凭先验知识编造数值，直接回退规则「无数据」文案
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            return fallback(plan, intent, result);
        }
        try {
            String metadataText = "";
            if (!isGov(plan, intent)) {
                metadataText = datasetId == null ? metadataService.buildMetadataText()
                        : metadataService.buildMetadataText(datasetId);
            }
            String user = buildUserPrompt(metadataText, historyContext, plan, intent, result.columns(), result.rows());
            String text = llmClient.chat(promptLoader.load("INTERPRET", SYSTEM_PROMPT), user, modelRouter.resolve("INTERPRET", modelConfigId));
            if (text == null || text.isBlank()) {
                return fallback(plan, intent, result);
            }
            return new Interpretation(text.trim(), "LLM");
        } catch (RuntimeException e) {
            return fallback(plan, intent, result);
        }
    }

    /** 规则模板结论兜底。 */
    public Interpretation fallback(AnalysisPlan plan, RecognizedIntent intent, SqlExecutor.ExecutionResult result) {
        return new Interpretation(buildRuleConclusion(plan, intent, result), "RULE");
    }

    /** 组装用户 Prompt：指标口径（非政务）+ 意图/计划 + 查询结果摘要（便于单测）。 */
    static String buildUserPrompt(String metadataText, AnalysisPlan plan, RecognizedIntent intent,
                                  List<String> columns, List<Map<String, Object>> rows) {
        return buildUserPrompt(metadataText, "", plan, intent, columns, rows);
    }

    /** 组装用户 Prompt：指标口径（非政务）+ 会话历史上下文 + 意图/计划 + 查询结果摘要（便于单测）。 */
    static String buildUserPrompt(String metadataText, String historyContext, AnalysisPlan plan, RecognizedIntent intent,
                                  List<String> columns, List<Map<String, Object>> rows) {
        String intentName = intent == null ? "" : safe(intent.getIntentName());
        String intentType = intent == null ? "" : safe(intent.getIntentType());
        String targetTable = plan == null ? "" : safe(plan.getTargetTable());
        String tableComment = plan == null ? "" : safe(plan.getTableComment());
        String metrics = plan == null || plan.getMetrics() == null ? "" : String.valueOf(plan.getMetrics());
        String dimensions = plan == null || plan.getDimensions() == null ? "" : String.valueOf(plan.getDimensions());
        String timeRange = plan == null ? "" : safe(plan.getTimeRange());
        StringBuilder sb = new StringBuilder();
        if (metadataText != null && !metadataText.isBlank()) {
            sb.append(metadataText).append("\n");
        }
        if (historyContext != null && !historyContext.isBlank()) {
            sb.append(historyContext).append("\n");
        }
        sb.append("分析意图: ").append(intentName).append("（").append(intentType)
                .append("），目标表: ").append(targetTable).append("（").append(tableComment)
                .append("），指标: ").append(metrics)
                .append("，维度: ").append(dimensions)
                .append("，时间范围: ").append(timeRange).append("\n");
        sb.append("查询结果摘要:\n").append(buildRowsSummary(columns, rows));
        return sb.toString();
    }

    /** 行数据摘要：行数 + 列名 + 前 20 行（值超 60 字符截断），超行数注明仅展示前 20 行（便于单测）。 */
    static String buildRowsSummary(List<String> columns, List<Map<String, Object>> rows) {
        List<String> cols = columns == null ? List.of() : columns;
        List<Map<String, Object>> data = rows == null ? List.of() : rows;
        StringBuilder sb = new StringBuilder();
        sb.append("共").append(data.size()).append("行，列: ").append(cols).append("\n");
        int limit = Math.min(data.size(), SUMMARY_ROW_LIMIT);
        for (int i = 0; i < limit; i++) {
            sb.append(i).append(": ").append(formatRow(data.get(i))).append("\n");
        }
        if (data.size() > SUMMARY_ROW_LIMIT) {
            sb.append("…（共").append(data.size()).append("行，仅展示前").append(SUMMARY_ROW_LIMIT).append("行）");
        }
        return sb.toString().trim();
    }

    /** 按意图类型拼装确定性规则结论。 */
    static String buildRuleConclusion(AnalysisPlan plan, RecognizedIntent intent, SqlExecutor.ExecutionResult result) {
        List<Map<String, Object>> rows = result == null ? List.of() : result.rows();
        if (rows == null || rows.isEmpty()) {
            return "查询结果为空：当前指标/时间/区域条件在数据集中无记录，可能是该统计期间尚未发布或指标口径不匹配，请核对后重试。";
        }
        String type = intent == null || intent.getIntentType() == null ? "GENERAL" : intent.getIntentType();
        boolean gov = isGov(plan, intent);
        return switch (type) {
            case "SALES_TREND" -> gov ? trendConclusion(rows, "发文量") : trendConclusion(rows, metricName(plan, "销售额"));
            case "RETENTION" -> trendConclusion(rows, "留存率");
            case "RANKING" -> gov ? top1Conclusion(rows, "发文量") : top1Conclusion(rows, "销量");
            case "STRUCTURE" -> shareConclusion(rows);
            case "COMPARISON" -> comparisonConclusion(rows);
            case "USER_PROFILE" -> profileConclusion(rows);
            case "ANOMALY" -> anomalyConclusion(rows);
            default -> gov ? govGeneralConclusion(rows) : generalConclusion(rows);
        };
    }

    private static String trendConclusion(List<Map<String, Object>> rows, String metricName) {
        Map<String, Object> first = rows.get(0);
        Map<String, Object> last = rows.get(rows.size() - 1);
        Double firstVal = lastNumeric(first);
        Double lastVal = lastNumeric(last);
        if (firstVal == null || lastVal == null) {
            return "共" + rows.size() + "期数据，" + metricName + "整体保持平稳。";
        }
        String trend;
        if (lastVal > firstVal * RISE_THRESHOLD) {
            trend = "呈上升趋势";
        } else if (lastVal < firstVal * FALL_THRESHOLD) {
            trend = "呈下降趋势";
        } else {
            trend = "整体平稳";
        }
        return "共" + rows.size() + "期数据，" + metricName + "由首期" + formatNum(firstVal)
                + "变化至末期" + formatNum(lastVal) + "，" + trend + "。";
    }

    private static String top1Conclusion(List<Map<String, Object>> rows, String label) {
        Map<String, Object> top1 = maxRow(rows);
        String name = nameOf(top1);
        Double value = lastNumeric(top1);
        if (rows.size() > 1 && value != null) {
            Double second = secondMaxValue(rows);
            if (second != null) {
                return label + "最高的是「" + name + "」（" + formatNum(value) + "），领先第二名约"
                        + formatNum(value - second) + "。";
            }
        }
        return label + "最高的是「" + name + "」" + (value == null ? "" : "（" + formatNum(value) + "）") + "。";
    }

    private static String shareConclusion(List<Map<String, Object>> rows) {
        Map<String, Object> top1 = maxRow(rows);
        String name = nameOf(top1);
        Double top1Val = lastNumeric(top1);
        double total = 0;
        for (Map<String, Object> row : rows) {
            Double v = lastNumeric(row);
            if (v != null) {
                total += v;
            }
        }
        if (top1Val != null && total > 0) {
            return "「" + name + "」占比最高，约占整体的" + formatNum(top1Val * 100 / total) + "%。";
        }
        return "「" + name + "」占比最高。";
    }

    private static String comparisonConclusion(List<Map<String, Object>> rows) {
        if (rows.size() < 2) {
            return generalConclusion(rows);
        }
        Map<String, Object> maxRow = maxRow(rows);
        Map<String, Object> minRow = minRow(rows);
        Double vMax = lastNumeric(maxRow);
        Double vMin = lastNumeric(minRow);
        if (vMax == null || vMin == null) {
            return generalConclusion(rows);
        }
        double base = Math.max(Math.abs(vMax), Math.abs(vMin));
        double diffPct = base == 0 ? 0 : Math.abs(vMax - vMin) / base * 100;
        return "对比「" + nameOf(maxRow) + "」与「" + nameOf(minRow) + "」：指标分别为 " + formatNum(vMax)
                + " 与 " + formatNum(vMin) + "，差异约 " + formatNum(diffPct) + "%。";
    }

    private static String profileConclusion(List<Map<String, Object>> rows) {
        Map<String, Object> top1 = maxRow(rows);
        Double value = lastNumeric(top1);
        return "「" + nameOf(top1) + "」群体规模最大" + (value == null ? "" : "（" + formatNum(value) + "）") + "。";
    }

    private static String anomalyConclusion(List<Map<String, Object>> rows) {
        Double avg = averageOf(rows);
        if (avg == null) {
            return generalConclusion(rows);
        }
        Map<String, Object> maxRow = rows.get(0);
        double maxDev = -1;
        for (Map<String, Object> row : rows) {
            Double v = lastNumeric(row);
            if (v == null) {
                continue;
            }
            double dev = Math.abs(v - avg);
            if (dev > maxDev) {
                maxDev = dev;
                maxRow = row;
            }
        }
        Double maxVal = lastNumeric(maxRow);
        return "「" + nameOf(maxRow) + "」的指标值 " + (maxVal == null ? "" : formatNum(maxVal))
                + " 与整体均值 " + formatNum(avg) + " 偏差最大，建议重点关注。";
    }

    private static String generalConclusion(List<Map<String, Object>> rows) {
        Double min = null;
        Double max = null;
        for (Map<String, Object> row : rows) {
            Double v = lastNumeric(row);
            if (v == null) {
                continue;
            }
            min = min == null ? v : Math.min(min, v);
            max = max == null ? v : Math.max(max, v);
        }
        if (min != null) {
            return "共查询到 " + rows.size() + " 条记录，指标区间为 " + formatNum(min) + " ~ " + formatNum(max) + "。";
        }
        return "共查询到 " + rows.size() + " 条记录。";
    }

    private static String govGeneralConclusion(List<Map<String, Object>> rows) {
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : rows) {
            seen.add(nameOf(row));
        }
        return "共查询到 " + rows.size() + " 条政务公开记录，覆盖 " + seen.size() + " 个类目/单位。";
    }

    private static Double averageOf(List<Map<String, Object>> rows) {
        double sum = 0;
        int count = 0;
        for (Map<String, Object> row : rows) {
            Double v = lastNumeric(row);
            if (v != null) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    /** 政务类判断：仅按目标表为 GOV_INFO_RECORD 判定（关键词「政务公开」不再误伤统计查询）。 */
    static boolean isGov(AnalysisPlan plan, RecognizedIntent intent) {
        return plan != null && GOV_TABLE.equalsIgnoreCase(safe(plan.getTargetTable()));
    }

    private static String metricName(AnalysisPlan plan, String fallback) {
        if (plan != null && plan.getMetrics() != null && !plan.getMetrics().isEmpty()) {
            return plan.getMetrics().get(plan.getMetrics().size() - 1);
        }
        return fallback;
    }

    /** 数值最大的行（与行序无关，便于单测）。 */
    private static Map<String, Object> maxRow(List<Map<String, Object>> rows) {
        Map<String, Object> best = rows.get(0);
        double bestVal = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> row : rows) {
            Double v = lastNumeric(row);
            if (v != null && v > bestVal) {
                bestVal = v;
                best = row;
            }
        }
        return best;
    }

    /** 数值最小的行（与行序无关）。 */
    private static Map<String, Object> minRow(List<Map<String, Object>> rows) {
        Map<String, Object> best = rows.get(0);
        double bestVal = Double.POSITIVE_INFINITY;
        for (Map<String, Object> row : rows) {
            Double v = lastNumeric(row);
            if (v != null && v < bestVal) {
                bestVal = v;
                best = row;
            }
        }
        return best;
    }

    /** 次大数值（用于 TOP1 领先差距），无则返回 null。 */
    private static Double secondMaxValue(List<Map<String, Object>> rows) {
        Double max = null;
        Double second = null;
        for (Map<String, Object> row : rows) {
            Double v = lastNumeric(row);
            if (v == null) {
                continue;
            }
            if (max == null || v > max) {
                second = max;
                max = v;
            } else if (second == null || v > second) {
                second = v;
            }
        }
        return second;
    }
    /** 行首值作为名称（如品类/单位），空行返回空串。 */
    private static String nameOf(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() != null) {
                return String.valueOf(e.getValue());
            }
        }
        return "";
    }

    /** 行内最后一个可解析数值（指标列通常在末尾）。 */
    private static Double lastNumeric(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(row.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Double v = toDouble(entries.get(i).getValue());
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatNum(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String formatRow(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append("=").append(truncateValue(e.getValue()));
        }
        return sb.append("}").toString();
    }

    private static String truncateValue(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return s.length() > VALUE_MAX_LENGTH ? s.substring(0, VALUE_MAX_LENGTH) + "…" : s;
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
