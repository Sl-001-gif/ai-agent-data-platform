package com.aiagent.mapper;

import com.aiagent.entity.AnalysisReport;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AnalysisReportMapper {

    int insert(AnalysisReport report);

    int deleteBySessionId(Long sessionId);

    AnalysisReport selectBySessionId(Long sessionId);

    List<AnalysisReport> selectByUserId(Long userId);

    AnalysisReport selectById(Long id);
}