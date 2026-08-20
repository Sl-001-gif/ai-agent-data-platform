package com.aiagent.ai.prompt;

import com.aiagent.entity.PromptTemplate;
import com.aiagent.mapper.AiConfigMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Prompt 模板加载器：按任务类型读取启用的 prompt_template（sort 升序、版本最新优先），
 * 支持 {变量名} 占位替换；表不存在/停用/内容空白/异常时回退内置 Prompt，接口永不失联。
 */
@Component
public class PromptLoader {

    private final AiConfigMapper aiConfigMapper;

    public PromptLoader(AiConfigMapper aiConfigMapper) {
        this.aiConfigMapper = aiConfigMapper;
    }

    /** 按类型加载启用模板；无匹配回退内置。 */
    public String load(String type, String fallback) {
        return load(type, fallback, null);
    }

    /** 按类型加载启用模板并替换 {varName} 占位；无匹配回退内置。 */
    public String load(String type, String fallback, Map<String, String> vars) {
        try {
            PromptTemplate template = aiConfigMapper.selectPromptByType(type);
            if (template != null && template.getContent() != null && !template.getContent().isBlank()) {
                return render(template.getContent().trim(), vars);
            }
        } catch (RuntimeException e) {
            // 表不存在/查询失败时回退内置
        }
        return fallback;
    }

    private static String render(String content, Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) {
            return content;
        }
        String result = content;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getValue() != null) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }
}