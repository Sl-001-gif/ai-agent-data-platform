package com.aiagent.mapper;

import com.aiagent.entity.AnalysisIntentRule;
import com.aiagent.entity.AnalysisPlanType;
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

    List<AnalysisPlanType> selectPlanTypes();

    AnalysisPlanType selectPlanTypeById(Long id);

    AnalysisPlanType selectPlanTypeByCode(String typeCode);

    int insertPlanType(AnalysisPlanType type);

    int updatePlanType(AnalysisPlanType type);

    int deletePlanType(Long id);

    /** 删除类型时把引用它的计划配置重置回普通类型。 */
    int clearPlanTypeRefs(String typeCode);
}