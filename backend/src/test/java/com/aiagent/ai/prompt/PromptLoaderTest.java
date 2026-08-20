package com.aiagent.ai.prompt;

import com.aiagent.entity.PromptTemplate;
import com.aiagent.mapper.AiConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L1 单测：Prompt 模板加载（回退 / 内容 / 变量替换 / 异常容错）。 */
class PromptLoaderTest {

    private final AiConfigMapper mapper = mock(AiConfigMapper.class);
    private final PromptLoader loader = new PromptLoader(mapper);

    @Test
    void shouldFallbackWhenNoEnabledTemplate() {
        when(mapper.selectPromptByType("SQL")).thenReturn(null);

        assertEquals("内置 Prompt", loader.load("SQL", "内置 Prompt"));
    }

    @Test
    void shouldLoadTemplateContentTrimmed() {
        PromptTemplate template = new PromptTemplate();
        template.setContent("  模板内容  ");
        when(mapper.selectPromptByType("SQL")).thenReturn(template);

        assertEquals("模板内容", loader.load("SQL", "内置 Prompt"));
    }

    @Test
    void shouldRenderVariablePlaceholders() {
        PromptTemplate template = new PromptTemplate();
        template.setContent("分析 {userQuestion}，元数据 {datasetSchema}");
        when(mapper.selectPromptByType("SQL")).thenReturn(template);

        String out = loader.load("SQL", "内置 Prompt",
                Map.of("userQuestion", "GDP", "datasetSchema", "stat_monthly"));

        assertEquals("分析 GDP，元数据 stat_monthly", out);
    }

    @Test
    void shouldFallbackWhenMapperThrows() {
        when(mapper.selectPromptByType("SQL")).thenThrow(new RuntimeException("db down"));

        assertEquals("内置 Prompt", loader.load("SQL", "内置 Prompt"));
    }
}