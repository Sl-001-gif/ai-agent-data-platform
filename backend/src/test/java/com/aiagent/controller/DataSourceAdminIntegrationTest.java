package com.aiagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

/** L2 集成测试：/api/admin/datasource CRUD 与连接测试（真实 MySQL dev 库，管理员用临时提升的测试用户）。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataSourceAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String adminToken;
    private static String userToken;
    private static Long createdId;
    private static final String ADMIN_USER = "adm_e2e_" + System.currentTimeMillis();
    private static final String NORMAL_USER = "usr_e2e_" + System.currentTimeMillis();
    private static final String DS_NAME = "集成测试库";

    @BeforeEach
    void ensureTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ai_data_source ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, "
                + "db_type VARCHAR(20) DEFAULT 'MYSQL', host VARCHAR(100) NOT NULL, "
                + "port INT NOT NULL DEFAULT 3306, database_name VARCHAR(100) NOT NULL, "
                + "username VARCHAR(100) NOT NULL, password VARCHAR(200) NOT NULL, "
                + "remark VARCHAR(255), create_by BIGINT, "
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Test
    @Order(1)
    void registerUsersAndPromoteAdmin() throws Exception {
        register(ADMIN_USER);
        jdbcTemplate.update("UPDATE sys_user SET role = 'ADMIN' WHERE username = ?", ADMIN_USER);
        register(NORMAL_USER);
    }

    @Test
    @Order(2)
    void loginShouldReturnAdminToken() throws Exception {
        adminToken = login(ADMIN_USER);
        assertNotNull(adminToken);
    }

    @Test
    @Order(3)
    void loginShouldReturnUserToken() throws Exception {
        userToken = login(NORMAL_USER);
        assertNotNull(userToken);
    }

    @Test
    @Order(4)
    void listWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/datasource"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void listWithNormalUserShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/datasource")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(6)
    void createShouldSucceed() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/datasource")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(DS_NAME, "localhost", 3306, "ai_agent_data", "root", "Admin@123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        createdId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertNotNull(createdId);
    }

    @Test
    @Order(7)
    void listShouldContainCreated() throws Exception {
        assertTrue(listContainsName(adminToken, DS_NAME));
    }

    @Test
    @Order(8)
    void testConnectionShouldSucceedForLocalMysql() throws Exception {
        mockMvc.perform(post("/api/admin/datasource/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("连接测试", "localhost", 3306, "ai_agent_data", "root", "Admin@123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    @Order(9)
    void testConnectionShouldFailWithBadPassword() throws Exception {
        mockMvc.perform(post("/api/admin/datasource/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("连接测试", "localhost", 3306, "ai_agent_data", "root", "WrongPass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").isNotEmpty());
    }

    @Test
    @Order(10)
    void testConnectionShouldFailWithBadPort() throws Exception {
        mockMvc.perform(post("/api/admin/datasource/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("连接测试", "localhost", 1, "ai_agent_data", "root", "Admin@123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(false));
    }

    @Test
    @Order(11)
    void updateShouldSucceed() throws Exception {
        Map<String, Object> body = payload(DS_NAME + "_改", "localhost", 3306, "ai_agent_data", "root", "Admin@123456");
        mockMvc.perform(put("/api/admin/datasource/" + createdId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContainsName(adminToken, DS_NAME + "_改"));
    }

    @Test
    @Order(12)
    void deleteShouldSucceed() throws Exception {
        mockMvc.perform(delete("/api/admin/datasource/" + createdId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContainsName(adminToken, DS_NAME + "_改"));
    }

    @Test
    @Order(13)
    void createWithBlankFieldsShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/admin/datasource")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @Order(14)
    void createWithInvalidPortShouldReturn400() throws Exception {
        Map<String, Object> body = payload(DS_NAME, "localhost", 70000, "ai_agent_data", "root", "Admin@123456");
        mockMvc.perform(post("/api/admin/datasource")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
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
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private boolean listContainsName(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/datasource")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        for (JsonNode node : array) {
            if (name.equals(node.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> payload(String name, String host, int port, String db, String user, String pass) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("dbType", "MYSQL");
        body.put("host", host);
        body.put("port", port);
        body.put("databaseName", db);
        body.put("username", user);
        body.put("password", pass);
        body.put("remark", "L2测试");
        return body;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}