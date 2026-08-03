package com.aiagent.mapper;

import com.aiagent.entity.AnalysisIntentRule;
import com.aiagent.entity.AnalysisPlanConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 分析配置数据访问：意图规则与计划配置读写。 */
@Mapper
public interface AnalysisConfigMapper {

    List<AnalysisIntentRule> selectIntentRules();

    AnalysisIntentRule selectIntentRuleById(Long id);

    int insertIntentRule(AnalysisIntentRule rule);

    int updateIntentRule(AnalysisIntentRule rule);

    int deleteIntentRule(Long id);

    List<AnalysisPlanConfig> selectPlanConfigs();

    AnalysisPlanConfig selectPlanConfigById(Long id);

    int insertPlanConfig(AnalysisPlanConfig config);

    int updatePlanConfig(AnalysisPlanConfig config);

    int deletePlanConfig(Long id);
}