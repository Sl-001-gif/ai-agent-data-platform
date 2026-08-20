package com.aiagent.ai.recommender;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.llm.LlmClient;
import com.aiagent.ai.model.ModelRouter;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.prompt.PromptLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐追问：按意图类型规则生成 2~3 条追问（政务类单独模板），
 * 去重并过滤与原文相同的项；空结果返回数据补充类追问。
 */
@Component
public class FollowupRecommender {

    private static final String GOV_TABLE = "GOV_INFO_RECORD";
    private static final String DEFAULT_TYPE = "GENERAL";
    private static final String SYSTEM_PROMPT =
            "你是数据分析助手。根据分析意图、指标维度与查询结果摘要，给出 2~3 条与当前分析上下文相关的推荐追问，"
                    + "只输出 JSON 数组（每项为一句中文问题），不要任何解释、不要 markdown。";
    private static final int SUMMARY_ROW_LIMIT = 8;
    private static final int VALUE_MAX_LENGTH = 40;

    private final LlmClient llmClient;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    /** 测试兜底：规则模板模式（不访问 LLM/数据库）。 */
    public FollowupRecommender() {
        this(null, null, null);
    }

    @Autowired
    public FollowupRecommender(LlmClient llmClient, ModelRouter modelRouter, PromptLoader promptLoader) {
        this.llmClient = llmClient;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
        this.objectMapper = new ObjectMapper();
    }

    private static final Map<String, List<String>> TEMPLATES = new LinkedHashMap<>();
    private static final Map<String, List<String>> GOV_TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("SALES_TREND", List.of(
                "哪个品类的销售额增长最快？",
                "不同区域的销售趋势有何差异？",
                "线上与线下渠道的结构占比如何？"));
        TEMPLATES.put("USER_PROFILE", List.of(
                "哪个年龄段的活跃用户占比最高？",
                "新增用户主要来自哪些城市？",
                "活跃用户与新增用户的差异有多大？"));
        TEMPLATES.put("COMPARISON", List.of(
                "各区域的客单价差异有多大？",
                "线上与线下渠道的订单量占比如何？",
                "不同品类的利润率有何差异？"));
        TEMPLATES.put("RANKING", List.of(
                "销量 TOP10 的完整明细是什么？",
                "垫底品类与榜首的差距有多大？",
                "各品牌近期的排名变化趋势如何？"));
        TEMPLATES.put("STRUCTURE", List.of(
                "各类目销售额的完整占比明细是什么？",
                "类目结构随时间如何变化？",
                "哪些类目的客单价更高？"));
        TEMPLATES.put("RETENTION", List.of(
                "留存率随时间的变化趋势如何？",
                "哪个年龄段的留存率最低？",
                "不同城市的次月留存有何差异？"));
        TEMPLATES.put("ANOMALY", List.of(
                "异常时段的具体明细是什么？",
                "哪个区域的波动幅度最大？",
                "异常与渠道或品类是否相关？"));
        TEMPLATES.put("GENERAL", List.of(
                "按日期维度的趋势如何？",
                "按区域维度的对比如何？",
                "各类目的占比结构如何？"));

        GOV_TEMPLATES.put("SALES_TREND", List.of(
                "按月发文量的趋势如何变化？",
                "哪些单位发文量增长最快？",
                "发文高峰集中在什么时间？"));
        GOV_TEMPLATES.put("RANKING", List.of(
                "发文量 TOP10 的单位明细是什么？",
                "各单位发文的月度变化如何？",
                "发文量最少的单位有哪些？"));
        GOV_TEMPLATES.put("STRUCTURE", List.of(
                "各类目发文的完整占比明细是什么？",
                "公开类目结构随时间如何变化？",
                "规范性文件与动态类发文占比如何？"));
        GOV_TEMPLATES.put("GENERAL", List.of(
                "发文量按月趋势如何？",
                "哪些单位发文最多？",
                "各类目发文占比如何？"));
    }

    /** 推荐 2~3 条追问：空结果走数据补充；其余按意图/政务模板，去重并过滤与原文相同项。 */
    public List<String> recommend(String question, RecognizedIntent intent, AnalysisPlan plan,
                                  SqlExecutor.ExecutionResult result) {
        List<Map<String, Object>> rows = result == null ? List.of() : result.rows();
        if (rows == null || rows.isEmpty()) {
            return List.of(
                    "扩大时间范围或调整筛选条件后，结果会如何变化？",
                    "当前数据为空的原因是什么？",
                    "换一个分析维度重新分析");
        }
        List<String> llmPicked = recommendByLlm(question, intent, plan, result);
        if (!llmPicked.isEmpty()) {
            return llmPicked;
        }
        String type = intent == null || intent.getIntentType() == null ? DEFAULT_TYPE : intent.getIntentType();
        Map<String, List<String>> templates = isGov(plan, intent) ? GOV_TEMPLATES : TEMPLATES;
        List<String> base = templates.getOrDefault(type, templates.get(DEFAULT_TYPE));
        Set<String> seen = new LinkedHashSet<>();
        List<String> picked = new ArrayList<>();
        for (String q : base) {
            if (q.equals(question == null ? "" : question.trim())) {
                continue;
            }
            if (seen.add(q)) {
                picked.add(q);
            }
        }
        return picked.isEmpty() ? new ArrayList<>(base) : picked;
    }

    /** LLM 推荐追问：输出 JSON 数组，解析失败/异常/空结果一律回退规则；未配置 Key 直接跳过。 */
    private List<String> recommendByLlm(String question, RecognizedIntent intent, AnalysisPlan plan,
                                        SqlExecutor.ExecutionResult result) {
        if (llmClient == null || !llmClient.isConfigured()) {
            return List.of();
        }
        try {
            String type = intent == null || intent.getIntentType() == null ? DEFAULT_TYPE : intent.getIntentType();
            String user = "分析问题：" + safe(question)
                    + "\n意图：" + (intent == null ? "" : safe(intent.getIntentName()) + "（" + type + "）")
                    + "\n指标：" + (plan == null || plan.getMetrics() == null ? "" : String.join("、", plan.getMetrics()))
                    + "\n维度：" + (plan == null || plan.getDimensions() == null ? "" : String.join("、", plan.getDimensions()))
                    + "\n结果摘要：" + buildSummary(result.columns(), result.rows());
            String raw = llmClient.chat(promptLoader == null ? SYSTEM_PROMPT : promptLoader.load("RECOMMEND", SYSTEM_PROMPT),
                    user, modelRouter == null ? null : modelRouter.resolve("RECOMMEND"));
            return parseQuestionList(raw, question);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 解析 LLM 输出的 JSON 数组；去重、过滤与原文相同项、截取前 3 条。 */
    private List<String> parseQuestionList(String raw, String question) {
        if (raw == null) {
            return List.of();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            if (!node.isArray()) {
                return List.of();
            }
            Set<String> seen = new LinkedHashSet<>();
            List<String> picked = new ArrayList<>();
            for (JsonNode item : node) {
                String q = item.asText("");
                if (q.isBlank() || q.equals(safe(question).trim())) {
                    continue;
                }
                if (seen.add(q)) {
                    picked.add(q);
                }
                if (picked.size() >= 3) {
                    break;
                }
            }
            return picked;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 结果摘要：前 N 行、值截断，供 LLM 生成上下文相关追问。 */
    private static String buildSummary(List<String> columns, List<Map<String, Object>> rows) {
        if (columns == null || rows == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("共 ").append(rows.size()).append(" 行，列：").append(String.join(",", columns)).append("；");
        int limit = Math.min(SUMMARY_ROW_LIMIT, rows.size());
        for (int i = 0; i < limit; i++) {
            StringBuilder row = new StringBuilder();
            for (String column : columns) {
                Object value = rows.get(i).get(column);
                String cell = value == null ? "" : String.valueOf(value);
                if (cell.length() > VALUE_MAX_LENGTH) {
                    cell = cell.substring(0, VALUE_MAX_LENGTH) + "…";
                }
                row.append(column).append('=').append(cell).append(';');
            }
            sb.append('\n').append(row);
        }
        return sb.toString();
    }

    /** 政务类判断：仅按目标表为 GOV_INFO_RECORD 判定（关键词「政务公开」不再误伤统计查询）。 */
    static boolean isGov(AnalysisPlan plan, RecognizedIntent intent) {
        return plan != null && GOV_TABLE.equalsIgnoreCase(safe(plan.getTargetTable()));
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
