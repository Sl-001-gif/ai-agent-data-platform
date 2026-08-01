package com.aiagent.mapper;

import com.aiagent.entity.AnalysisReport;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalysisReportMapper {

    int insert(AnalysisReport report);

    int deleteBySessionId(Long sessionId);

    AnalysisReport selectBySessionId(Long sessionId);
}