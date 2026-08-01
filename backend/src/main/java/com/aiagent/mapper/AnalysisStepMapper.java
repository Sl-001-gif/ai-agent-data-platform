package com.aiagent.mapper;

import com.aiagent.entity.AnalysisStep;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AnalysisStepMapper {

    int insert(AnalysisStep step);

    int deleteBySessionId(Long sessionId);

    List<AnalysisStep> selectBySessionId(Long sessionId);
}