package com.aiagent.ai.llm;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aiagent.ai.model.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI 兼容 Chat Completions 客户端。apiKey 优先取环境变量 AI_API_KEY，其次取 yml ai.model.api-key。 */
@Service
public class LlmClient {

    /** yml 默认占位 Key，视为未配置，避免无 Key 环境误发真实 HTTP 请求。 */
    private static final String PLACEHOLDER_API_KEY = "your-api-key";
    private static final int TIMEOUT_MS = 30_000;
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";

    @Value("${ai.model.api-key:}")
    private String apiKey;

    @Value("${ai.model.model-name:gpt-4o}")
    private String modelName;

    @Value("${ai.model.endpoint:https://api.openai.com/v1}")
    private String endpoint;

    @Value("${ai.model.max-retries:3}")
    private int maxRetries;

    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;

    public LlmClient(ObjectMapper objectMapper, ModelRouter modelRouter) {
        this.objectMapper = objectMapper;
        this.modelRouter = modelRouter;
    }

    /** 是否已配置可用 Key：环境变量 AI_API_KEY 优先；其次 DB 存在启用且带 Key 的模型配置；占位值视为未配置。 */
    public boolean isConfigured() {
        String env = System.getenv("AI_API_KEY");
        String key = notBlank(env) ? env : apiKey;
        return usableKey(key) || modelRouter.hasUsableConfig();
    }

    /** 使用 yml/环境变量默认配置调用。 */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null);
    }

    /** 使用按任务路由的模型配置调用；config 为 null 时回退默认配置。 */
    public String chat(String systemPrompt, String userPrompt, ModelRouter.ModelConfig config) {
        String key = resolveApiKey(config);
        if (!usableKey(key)) {
            throw new IllegalStateException("未配置 AI API Key");
        }
        String model = config != null && notBlank(config.modelName()) ? config.modelName() : safe(modelName, "gpt-4o");
        String baseEndpoint = config != null && notBlank(config.endpoint()) ? config.endpoint() : safe(endpoint, DEFAULT_ENDPOINT);
        String url = trimTrailingSlash(baseEndpoint) + "/chat/completions";
        double temperature = config != null ? config.temperature() : 0.2;
        int maxTokens = config != null ? config.maxTokens() : 0;

        int retries = Math.max(1, maxRetries);
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                return doChat(url, key, model, systemPrompt, userPrompt, temperature, maxTokens);
            } catch (RuntimeException e) {
                if (attempt >= retries) {
                    throw e;
                }
            }
        }
        throw new RuntimeException("LLM 调用失败");
    }

    /** 单次 chat completions 请求，失败抛出 RuntimeException 由上层重试。 */
    private String doChat(String url, String apiKey, String model, String systemPrompt, String userPrompt,
                          double temperature, int maxTokens) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            body.put("messages", messages);
            if (supportsTemperature(model)) {
                body.put("temperature", temperature);
            }
            if (maxTokens > 0) {
                body.put("max_tokens", maxTokens);
            }
            String jsonBody = objectMapper.writeValueAsString(body);
            try (HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(jsonBody)
                    .timeout(TIMEOUT_MS)
                    .execute()) {
                JsonNode root = objectMapper.readTree(response.body());
                String apiError = root.path("error").path("message").asText(null);
                if (apiError != null && !apiError.isBlank()) {
                    throw new RuntimeException("LLM 调用失败: " + apiError);
                }
                String content = root.path("choices").path(0).path("message").path("content").asText(null);
                if (content == null || content.isBlank()) {
                    throw new RuntimeException("LLM 响应缺少 content");
                }
                return content;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /** 环境变量优先，其次按任务路由配置，最后回退 yml。 */
    private String resolveApiKey(ModelRouter.ModelConfig config) {
        String env = System.getenv("AI_API_KEY");
        if (notBlank(env)) {
            return env.trim();
        }
        if (config != null && notBlank(config.apiKey())) {
            return config.apiKey().trim();
        }
        return apiKey == null ? "" : apiKey.trim();
    }

    private boolean usableKey(String key) {
        return notBlank(key) && !PLACEHOLDER_API_KEY.equals(key);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String safe(String s, String fallback) {
        return notBlank(s) ? s : fallback;
    }

    /** K2 系列推理模型仅允许 temperature=1，省略该参数即用默认值 1；其余模型按配置传参。 */
    static boolean supportsTemperature(String model) {
        return model == null || !model.toLowerCase(Locale.ROOT).contains("k2");
    }

    private static String trimTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }
}