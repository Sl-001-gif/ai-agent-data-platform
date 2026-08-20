package com.aiagent.mapper;

import com.aiagent.entity.StatCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 统计月报指标分类树数据访问（stat_indicator_category）。 */
@Mapper
public interface StatCategoryMapper {

    List<StatCategory> selectTree();

    StatCategory selectById(Long id);

    StatCategory selectByCode(String code);

    StatCategory selectByNameAndParent(@Param("name") String name, @Param("parentId") Long parentId);

    int insert(StatCategory node);

    int update(StatCategory node);

    int delete(Long id);

    int countChildren(Long id);

    int maxSort(Long parentId);
}
