package com.aiagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L2 集成测试：/api/admin/ai-config 模型配置与 Prompt 模板 CRUD（真实 MySQL dev 库，key 可入库但列表不回显）。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiConfigAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String adminToken;
    private static String userToken;
    private static Long modelId;
    private static Long promptId;
    private static final String ADMIN_USER = "adm_ai_" + System.currentTimeMillis();
    private static final String NORMAL_USER = "usr_ai_" + System.currentTimeMillis();
    private static final String MODEL_NAME = "l2-model-" + System.currentTimeMillis();
    private static final String PROMPT_NAME = "L2模板_" + System.currentTimeMillis();

    @Test
    @Order(1)
    void registerUsersAndPromoteAdmin() throws Exception {
        register(ADMIN_USER);
        jdbcTemplate.update("UPDATE sys_user SET role = 'ADMIN' WHERE username = ?", ADMIN_USER);
        register(NORMAL_USER);
    }

    @Test
    @Order(2)
    void loginShouldReturnTokens() throws Exception {
        adminToken = login(ADMIN_USER);
        userToken = login(NORMAL_USER);
        assertNotNull(adminToken);
        assertNotNull(userToken);
    }

    @Test
    @Order(3)
    void listWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/ai-config/models"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void listWithNormalUserShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/ai-config/models")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void modelListShouldContainSeedsWithoutApiKey() throws Exception {
        MvcResult result = mockGet("/api/admin/ai-config/models", adminToken);
        assertTrue(listContains(result, "name", "sql-deepseek"));
        assertTrue(noApiKeyReturned(result), "任何模型配置都不应返回 apiKey");
    }

    @Test
    @Order(6)
    void modelCreateUpdateDeleteShouldSucceed() throws Exception {
        modelId = createId("/api/admin/ai-config/models", adminToken, modelPayload(null, MODEL_NAME));
        assertNotNull(modelId);
        assertTrue(listContains(mockGet("/api/admin/ai-config/models", adminToken), "name", MODEL_NAME));
        Integer apiKeyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_model_config WHERE id = ? AND api_key IS NOT NULL", Integer.class, modelId);
        assertTrue(apiKeyCount != null && apiKeyCount == 1, "api_key 应写入数据库（供 LLM 客户端读取）");
        assertTrue(noApiKeyReturned(mockGet("/api/admin/ai-config/models", adminToken)), "列表不应回显 apiKey");

        Map<String, Object> updated = modelPayload(modelId, MODEL_NAME + "_改");
        mockMvc.perform(put("/api/admin/ai-config/models/" + modelId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/ai-config/models", adminToken), "name", MODEL_NAME + "_改"));

        mockMvc.perform(delete("/api/admin/ai-config/models/" + modelId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/ai-config/models", adminToken), "name", MODEL_NAME + "_改"));
    }

    @Test
    @Order(7)
    void promptCreateUpdateDeleteShouldSucceed() throws Exception {
        promptId = createId("/api/admin/ai-config/prompts", adminToken, promptPayload(null, PROMPT_NAME));
        assertNotNull(promptId);
        assertTrue(listContains(mockGet("/api/admin/ai-config/prompts", adminToken), "name", PROMPT_NAME));

        Map<String, Object> updated = promptPayload(promptId, PROMPT_NAME + "_改");
        mockMvc.perform(put("/api/admin/ai-config/prompts/" + promptId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/ai-config/prompts", adminToken), "name", PROMPT_NAME + "_改"));

        mockMvc.perform(delete("/api/admin/ai-config/prompts/" + promptId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/ai-config/prompts", adminToken), "name", PROMPT_NAME + "_改"));
    }

    @Test
    @Order(8)
    void createModelWithBlankNameShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/admin/ai-config/models")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(modelPayload(null, ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private boolean noApiKeyReturned(MvcResult result) throws Exception {
        JsonNode array = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        for (JsonNode node : array) {
            JsonNode key = node.path("apiKey");
            if (!key.isNull() && !key.asText().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private MvcResult mockGet(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
    }

    private boolean listContains(MvcResult result, String key, String value) throws Exception {
        JsonNode array = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        for (JsonNode node : array) {
            if (value.equals(node.path(key).asText())) {
                return true;
            }
        }
        return false;
    }

    private Long createId(String path, String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asLong();
    }

    private Map<String, Object> modelPayload(Long id, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("modelName", "deepseek-chat");
        body.put("apiKey", "sk-不应入库");
        body.put("endpoint", "https://api.deepseek.com/v1");
        body.put("maxTokens", 2048);
        body.put("temperature", 0.2);
        body.put("status", 1);
        return body;
    }

    private Map<String, Object> promptPayload(Long id, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("type", "SQL");
        body.put("content", "你是资深数据分析师，生成只读 SELECT SQL。");
        body.put("variables", "datasetSchema,userQuestion,originSQL");
        body.put("sort", 5);
        body.put("version", 1);
        body.put("status", 1);
        return body;
    }

    private void register(String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "123456");
        body.put("nickname", username);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private String login(String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "123456");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}