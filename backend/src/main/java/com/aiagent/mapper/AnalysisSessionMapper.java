package com.aiagent.mapper;

import com.aiagent.entity.AnalysisSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalysisSessionMapper {

    int insert(AnalysisSession session);

    AnalysisSession selectById(Long id);

    int update(AnalysisSession session);
}