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
}