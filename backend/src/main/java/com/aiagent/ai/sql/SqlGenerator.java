package com.aiagent.ai.sql;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;

/** 文本生成 SQL 的生成器接口（预留 LLM 实现位）。 */
public interface SqlGenerator {

    /** 生成结果：SQL 文本 + 生成器类型（RULE/LLM）。 */
    record GeneratedSql(String sql, String generatorType) {
    }

    /** 依据分析计划与识别意图生成可执行 SQL。 */
    GeneratedSql generate(AnalysisPlan plan, RecognizedIntent intent);

    /** 带用户原问题生成可执行 SQL（question 供 LLM 理解期别/口径/区域语义）；默认忽略，规则生成器无需实现。 */
    default GeneratedSql generate(AnalysisPlan plan, RecognizedIntent intent, String question) {
        return generate(plan, intent);
    }

    /** 带纠错提示重新生成（如执行空结果/口径不符时由调用方驱动）；默认与 generate 等价。 */
    default GeneratedSql generateWithHint(AnalysisPlan plan, RecognizedIntent intent, String question, String hint) {
        return generate(plan, intent, question);
    }
}