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

/** L2 集成测试：管理端用户管理 CRUD（真实 MySQL dev 库）。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String adminToken;
    private static String userToken;
    private static Long createdId;
    private static final String ADMIN_USER = "adm_usr_" + System.currentTimeMillis();
    private static final String NORMAL_USER = "usr_mg_" + System.currentTimeMillis();
    private static final String TARGET_USER = "tgt_usr_" + System.currentTimeMillis();

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
        mockMvc.perform(get("/api/admin/user"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void listWithNormalUserShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/user")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void createUpdateDeleteShouldSucceed() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/admin/user")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(userPayload(null, TARGET_USER, "123456", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        createdId = objectMapper.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asLong();
        assertTrue(listContains(mockGet("/api/admin/user", adminToken), "username", TARGET_USER));
        assertTrue(noPasswordReturned(mockGet("/api/admin/user", adminToken)), "列表不应返回密码");
        JsonNode pageData = objectMapper.readTree(
                mockGet("/api/admin/user?page=1&pageSize=5", adminToken).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        assertTrue(pageData.path("total").asLong() >= 3, "用户分页 total 应 >= 3");
        assertTrue(pageData.path("rows").size() <= 5, "用户分页行数不超过 pageSize");

        mockMvc.perform(put("/api/admin/user/" + createdId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(userPayload(createdId, TARGET_USER, null, "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/user", adminToken), "role", "ADMIN"));

        mockMvc.perform(delete("/api/admin/user/" + createdId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/user", adminToken), "username", TARGET_USER));
    }

    @Test
    @Order(6)
    void deleteSelfShouldReturn400() throws Exception {
        Long myId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN_USER);
        mockMvc.perform(delete("/api/admin/user/" + myId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @Order(7)
    void createWithBlankUsernameShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/admin/user")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(userPayload(null, "", "123456", "USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private boolean noPasswordReturned(MvcResult result) throws Exception {
        JsonNode array = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("rows");
        for (JsonNode node : array) {
            JsonNode pwd = node.path("password");
            if (!pwd.isNull() && !pwd.asText().isEmpty()) {
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
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("rows");
        for (JsonNode node : array) {
            if (value.equals(node.path(key).asText())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> userPayload(Long id, String username, String password, String role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("username", username);
        body.put("password", password);
        body.put("nickname", username);
        body.put("email", "t@example.com");
        body.put("phone", "13800000000");
        body.put("role", role);
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