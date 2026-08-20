package com.aiagent.mapper;

import com.aiagent.entity.AgentPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentPlanMapper {

    int insert(AgentPlan plan);

    int update(AgentPlan plan);

    AgentPlan selectById(Long id);

    List<AgentPlan> selectByUserId(Long userId);

    /** 已生成报告的（用户）计划列表。 */
    List<AgentPlan> selectReportsByUserId(Long userId);

    int deleteById(Long id);
}