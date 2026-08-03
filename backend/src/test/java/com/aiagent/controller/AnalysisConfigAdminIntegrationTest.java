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

/** L2 集成测试：/api/admin/analysis-config 意图规则与计划配置 CRUD（真实 MySQL dev 库）。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisConfigAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String adminToken;
    private static String userToken;
    private static Long ruleId;
    private static Long planId;
    private static final String ADMIN_USER = "adm_cfg_" + System.currentTimeMillis();
    private static final String NORMAL_USER = "usr_cfg_" + System.currentTimeMillis();
    private static final String RULE_CODE = "L2_INTENT_" + System.currentTimeMillis();
    private static final String PLAN_CODE = "L2_PLAN_" + System.currentTimeMillis();

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
        mockMvc.perform(get("/api/admin/analysis-config/intent-rules"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void listWithNormalUserShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/analysis-config/intent-rules")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void intentRuleListShouldContainSeededRules() throws Exception {
        assertTrue(listContains(mockGet("/api/admin/analysis-config/intent-rules", adminToken), "intentCode", "SALES_TREND"));
        assertTrue(listContains(mockGet("/api/admin/analysis-config/plan-configs", adminToken), "tableName", "GOV_INFO_RECORD"));
    }

    @Test
    @Order(6)
    void intentRuleCreateUpdateDeleteShouldSucceed() throws Exception {
        ruleId = createId("/api/admin/analysis-config/intent-rules", adminToken,
                rulePayload(null, RULE_CODE, "L2测试意图", "测试关键词1,测试关键词2"));
        assertNotNull(ruleId);
        assertTrue(listContains(mockGet("/api/admin/analysis-config/intent-rules", adminToken), "intentCode", RULE_CODE));

        Map<String, Object> updated = rulePayload(ruleId, RULE_CODE, "L2测试意图_改", "测试关键词1,测试关键词2,新词");
        mockMvc.perform(put("/api/admin/analysis-config/intent-rules/" + ruleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/analysis-config/intent-rules", adminToken), "intentName", "L2测试意图_改"));

        mockMvc.perform(delete("/api/admin/analysis-config/intent-rules/" + ruleId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/analysis-config/intent-rules", adminToken), "intentCode", RULE_CODE));
    }

    @Test
    @Order(7)
    void planConfigCreateUpdateDeleteShouldSucceed() throws Exception {
        planId = createId("/api/admin/analysis-config/plan-configs", adminToken,
                planPayload(null, PLAN_CODE, 1, "GOV_INFO_RECORD", "发文量", "公开单位", "bar"));
        assertNotNull(planId);
        assertTrue(listContains(mockGet("/api/admin/analysis-config/plan-configs", adminToken), "intentCode", PLAN_CODE));

        Map<String, Object> updated = planPayload(planId, PLAN_CODE, 1, "GOV_INFO_RECORD", "发文量,日均发文量", "公开单位", "line");
        mockMvc.perform(put("/api/admin/analysis-config/plan-configs/" + planId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/analysis-config/plan-configs", adminToken), "chartType", "line"));

        mockMvc.perform(delete("/api/admin/analysis-config/plan-configs/" + planId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/analysis-config/plan-configs", adminToken), "intentCode", PLAN_CODE));
    }

    @Test
    @Order(8)
    void createRuleWithBlankCodeShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/admin/analysis-config/intent-rules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(rulePayload(null, "", "空编码", "关键词"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
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

    private Map<String, Object> rulePayload(Long id, String code, String name, String keywords) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("intentCode", code);
        body.put("intentName", name);
        body.put("keywords", keywords);
        body.put("priority", 0);
        body.put("status", 1);
        return body;
    }

    private Map<String, Object> planPayload(Long id, String code, int gov, String table, String metrics,
                                            String dimensions, String chartType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("intentCode", code);
        body.put("isGov", gov);
        body.put("tableName", table);
        body.put("metrics", metrics);
        body.put("dimensions", dimensions);
        body.put("chartType", chartType);
        body.put("timeRange", "近30天");
        body.put("sqlTemplate", "SELECT COUNT(*) FROM gov_info_record WHERE publish_date >= {timeRange}");
        body.put("status", 1);
        body.put("sort", 0);
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