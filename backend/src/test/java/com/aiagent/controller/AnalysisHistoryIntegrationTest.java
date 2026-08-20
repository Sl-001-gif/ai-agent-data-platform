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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L2 集成测试：分析历史（会话列表/步骤/删除、报告列表/详情）真实 MySQL dev 库。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisHistoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String token;
    private static Long sessionId;
    private static final String USERNAME = "usr_his_" + System.currentTimeMillis();

    @Test
    @Order(1)
    void registerAndLogin() throws Exception {
        register(USERNAME);
        token = login(USERNAME);
        assertNotNull(token);
    }

    @Test
    @Order(2)
    void listWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/analysis/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void executeCreatesSessionThenHistoryListsIt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/analysis/execute")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "邵阳市各单位发文量排名Top10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        sessionId = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("sessionId").asLong();
        assertNotNull(sessionId);
        assertTrue(listContains(mockGet("/api/analysis/sessions", token), "id", String.valueOf(sessionId)));
        JsonNode pageData = objectMapper.readTree(
                mockGet("/api/analysis/sessions?page=1&pageSize=3", token).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        assertTrue(pageData.path("total").asLong() >= 1, "分页 total 应 >= 1");
        assertTrue(pageData.path("rows").size() <= 3, "分页行数不超过 pageSize");
    }

    @Test
    @Order(4)
    void stepsShouldReflectExecutedPipeline() throws Exception {
        MvcResult result = mockGet("/api/analysis/session/" + sessionId + "/steps", token);
        JsonNode array = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertTrue(array.size() >= 5, "应至少有 INTENT/PLAN/SQL/VALIDATE/EXECUTE 五步");
        boolean hasExecute = false;
        for (JsonNode node : array) {
            if ("EXECUTE".equals(node.path("stepType").asText())) {
                hasExecute = true;
            }
        }
        assertTrue(hasExecute, "步骤中应含 EXECUTE");
    }

    @Test
    @Order(5)
    void reportShouldAppearInHistory() throws Exception {
        mockMvc.perform(post("/api/analysis/report")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sessionId", sessionId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        MvcResult list = mockGet("/api/analysis/reports", token);
        JsonNode array = objectMapper.readTree(
                list.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("rows");
        assertTrue(array.size() >= 1, "报告列表应有记录");
        long reportId = array.get(0).path("id").asLong();

        MvcResult detail = mockGet("/api/analysis/report/" + reportId, token);
        JsonNode content = objectMapper.readTree(
                detail.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("content");
        assertTrue(!content.isNull() && content.asText().length() > 0, "报告详情 content 应非空");

        MvcResult bySession = mockGet("/api/analysis/session/" + sessionId + "/report", token);
        JsonNode roundContent = objectMapper.readTree(
                bySession.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("content");
        assertTrue(!roundContent.isNull() && roundContent.asText().length() > 0, "按会话取报告 content 应非空");
    }

    @Test
    @Order(6)
    void deleteSessionShouldRemoveFromHistory() throws Exception {
        mockMvc.perform(delete("/api/analysis/session/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/analysis/sessions", token), "id", String.valueOf(sessionId)));
    }

    private MvcResult mockGet(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
    }

    private boolean listContains(MvcResult result, String key, String value) throws Exception {
        JsonNode array = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("rows");
        for (JsonNode node : array) {
            if (value.equals(node.path(key).asText())) {
                return true;
            }
        }
        return false;
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