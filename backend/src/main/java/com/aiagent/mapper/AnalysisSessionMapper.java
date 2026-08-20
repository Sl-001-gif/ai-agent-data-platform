package com.aiagent.mapper;

import com.aiagent.entity.AnalysisSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AnalysisSessionMapper {

    int insert(AnalysisSession session);

    AnalysisSession selectById(Long id);

    int update(AnalysisSession session);

    long countByUserId(@Param("userId") Long userId, @Param("keyword") String keyword,
                       @Param("datasetId") Long datasetId);

    List<AnalysisSession> selectByUserId(@Param("userId") Long userId, @Param("keyword") String keyword,
                                         @Param("datasetId") Long datasetId, @Param("offset") int offset,
                                         @Param("limit") int limit);

    int deleteById(Long id);

    int deleteBatch(@Param("ids") List<Long> ids);
}