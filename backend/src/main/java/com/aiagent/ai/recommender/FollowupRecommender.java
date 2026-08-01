package com.aiagent.ai.recommender;

import com.aiagent.ai.executor.SqlExecutor;
import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
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

    private static final String GOV_KEYWORD = "政务公开";
    private static final String GOV_TABLE = "GOV_INFO_RECORD";
    private static final String DEFAULT_TYPE = "GENERAL";

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

    /** 政务类判断：命中关键词或目标表为 GOV_INFO_RECORD。 */
    static boolean isGov(AnalysisPlan plan, RecognizedIntent intent) {
        if (intent != null && intent.getMatchedKeywords() != null
                && intent.getMatchedKeywords().contains(GOV_KEYWORD)) {
            return true;
        }
        return plan != null && GOV_TABLE.equalsIgnoreCase(safe(plan.getTargetTable()));
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}