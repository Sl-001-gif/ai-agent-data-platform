package com.aiagent.mapper;

import com.aiagent.entity.AiDataSource;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AiDataSourceMapper {

    int insert(AiDataSource dataSource);

    int update(AiDataSource dataSource);

    int deleteById(Long id);

    AiDataSource selectById(Long id);

    List<AiDataSource> selectList();
}