package com.aiagent.mapper;

import com.aiagent.entity.AnalysisReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AnalysisReportMapper {

    int insert(AnalysisReport report);

    int deleteBySessionId(Long sessionId);

    int deleteBySessionIdAndRound(@Param("sessionId") Long sessionId, @Param("roundNo") Integer roundNo);

    AnalysisReport selectBySessionId(Long sessionId);

    AnalysisReport selectBySessionIdAndRound(@Param("sessionId") Long sessionId, @Param("roundNo") Integer roundNo);

    long countByUserId(Long userId);

    List<AnalysisReport> selectByUserId(@Param("userId") Long userId, @Param("offset") int offset,
                                        @Param("limit") int limit);

    AnalysisReport selectById(Long id);

    int deleteById(Long id);
}