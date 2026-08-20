package com.aiagent.mapper;

import com.aiagent.entity.AnalysisStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AnalysisStepMapper {

    int insert(AnalysisStep step);

    int deleteBySessionId(Long sessionId);

    int deleteBySessionIdAndRound(@Param("sessionId") Long sessionId, @Param("roundNo") Integer roundNo);

    List<AnalysisStep> selectBySessionId(Long sessionId);
}