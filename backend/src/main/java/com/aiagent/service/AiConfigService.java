package com.aiagent.service;

import com.aiagent.entity.AiModelConfig;
import com.aiagent.entity.PromptTemplate;
import com.aiagent.mapper.AiConfigMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** AI 能力配置管理：模型配置（key 不入库）与 Prompt 模板 CRUD。 */
@Service
public class AiConfigService {

    private final AiConfigMapper aiConfigMapper;

    public AiConfigService(AiConfigMapper aiConfigMapper) {
        this.aiConfigMapper = aiConfigMapper;
    }

    // ---------- 模型配置 ----------

    public List<AiModelConfig> listModels() {
        List<AiModelConfig> rows = aiConfigMapper.selectModelList();
        List<AiModelConfig> result = new ArrayList<>();
        for (AiModelConfig row : rows) {
            row.setApiKey(null);
            result.add(row);
        }
        return result;
    }

    public AiModelConfig createModel(AiModelConfig model) {
        requireNotBlank(model.getName(), "配置名称不能为空");
        requireNotBlank(model.getModelName(), "模型名称不能为空");
        if (model.getEndpoint() == null || model.getEndpoint().isBlank()) {
            model.setEndpoint("https://api.deepseek.com/v1");
        }
        if (model.getMaxTokens() == null) {
            model.setMaxTokens(2048);
        }
        if (model.getTemperature() == null) {
            model.setTemperature(0.2);
        }
        if (model.getStatus() == null) {
            model.setStatus(1);
        }
        model.setApiKey(null);
        aiConfigMapper.insertModel(model);
        return model;
    }

    public void updateModel(Long id, AiModelConfig model) {
        requireExisting(aiConfigMapper.selectModelById(id), "模型配置不存在");
        model.setId(id);
        model.setApiKey(null);
        aiConfigMapper.updateModel(model);
    }

    public void deleteModel(Long id) {
        requireRows(aiConfigMapper.deleteModel(id), "模型配置不存在");
    }

    // ---------- Prompt 模板 ----------

    public List<PromptTemplate> listPrompts() {
        return aiConfigMapper.selectPromptList();
    }

    public PromptTemplate createPrompt(PromptTemplate prompt) {
        requireNotBlank(prompt.getName(), "模板名称不能为空");
        requireNotBlank(prompt.getContent(), "模板内容不能为空");
        if (prompt.getType() == null || prompt.getType().isBlank()) {
            prompt.setType("SQL");
        }
        if (prompt.getVersion() == null) {
            prompt.setVersion(1);
        }
        if (prompt.getStatus() == null) {
            prompt.setStatus(1);
        }
        aiConfigMapper.insertPrompt(prompt);
        return prompt;
    }

    public void updatePrompt(Long id, PromptTemplate prompt) {
        requireExisting(aiConfigMapper.selectPromptById(id), "模板不存在");
        prompt.setId(id);
        aiConfigMapper.updatePrompt(prompt);
    }

    public void deletePrompt(Long id) {
        requireRows(aiConfigMapper.deletePrompt(id), "模板不存在");
    }

    private static void requireExisting(Object existing, String message) {
        if (existing == null) {
            throw new RuntimeException(message);
        }
    }

    private static void requireRows(int rows, String message) {
        if (rows == 0) {
            throw new RuntimeException(message);
        }
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException(message);
        }
    }
}