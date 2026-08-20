package com.aiagent.mapper;

import com.aiagent.entity.AiModelConfig;
import com.aiagent.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** AI 能力配置数据访问：模型配置与 Prompt 模板读写。 */
@Mapper
public interface AiConfigMapper {

    List<AiModelConfig> selectModelList();

    AiModelConfig selectModelById(Long id);

    int insertModel(AiModelConfig model);

    int updateModel(AiModelConfig model);

    int deleteModel(Long id);

    List<PromptTemplate> selectPromptList();

    PromptTemplate selectPromptByType(String type);

    PromptTemplate selectPromptById(Long id);

    int insertPrompt(PromptTemplate prompt);

    int updatePrompt(PromptTemplate prompt);

    int deletePrompt(Long id);
}