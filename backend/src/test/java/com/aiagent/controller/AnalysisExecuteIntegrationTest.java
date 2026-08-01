package com.aiagent.controller;

import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.RegisterRequest;
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

import java.util.Map;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L2 集成测试：/api/analysis/execute 全链路（真实 MySQL dev 库）。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisExecuteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String testToken;
    private static Long testSessionId;
    private static final String TEST_USER = "exec_e2e_" + System.currentTimeMillis();

    @BeforeEach
    void setUpDemoData() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS order_info (order_date DATE, region VARCHAR(50), channel VARCHAR(50), category VARCHAR(50), order_count INT, sales_amount DECIMAL(12,2), sales_volume INT)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS user_info (register_date DATE, age_group VARCHAR(50), city VARCHAR(50), new_user_count INT, active_user_count INT, retention_rate DECIMAL(5,2))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS product_info (category VARCHAR(50), brand VARCHAR(50), sales_volume INT, sales_amount DECIMAL(12,2))");
        jdbcTemplate.update("DELETE FROM order_info");
        jdbcTemplate.update("DELETE FROM user_info");
        jdbcTemplate.update("DELETE FROM product_info");
        jdbcTemplate.update("INSERT INTO order_info (order_date, region, channel, category, order_count, sales_amount, sales_volume) VALUES (CURDATE(), '华东', '线上', '手机', 120, 120000.00, 140)");
        jdbcTemplate.update("INSERT INTO order_info (order_date, region, channel, category, order_count, sales_amount, sales_volume) VALUES (DATE_SUB(CURDATE(), INTERVAL 1 DAY), '华南', '线下', '家电', 80, 90000.00, 95)");
        jdbcTemplate.update("INSERT INTO order_info (order_date, region, channel, category, order_count, sales_amount, sales_volume) VALUES (DATE_SUB(CURDATE(), INTERVAL 2 DAY), '华东', '线下', '手机', 60, 66000.00, 70)");
        jdbcTemplate.update("INSERT INTO user_info (register_date, age_group, city, new_user_count, active_user_count, retention_rate) VALUES (CURDATE(), '18-25岁', '长沙', 300, 900, 45.00)");
        jdbcTemplate.update("INSERT INTO user_info (register_date, age_group, city, new_user_count, active_user_count, retention_rate) VALUES (DATE_SUB(CURDATE(), INTERVAL 1 DAY), '26-35岁', '邵阳', 500, 1500, 52.00)");
        jdbcTemplate.update("INSERT INTO user_info (register_date, age_group, city, new_user_count, active_user_count, retention_rate) VALUES (DATE_SUB(CURDATE(), INTERVAL 2 DAY), '36-45岁', '长沙', 200, 600, 38.00)");
        jdbcTemplate.update("INSERT INTO product_info (category, brand, sales_volume, sales_amount) VALUES ('手机', '品牌A', 1500, 1200000.00)");
        jdbcTemplate.update("INSERT INTO product_info (category, brand, sales_volume, sales_amount) VALUES ('家电', '品牌B', 800, 900000.00)");
        jdbcTemplate.update("INSERT INTO product_info (category, brand, sales_volume, sales_amount) VALUES ('食品', '品牌C', 3000, 300000.00)");
    }

    @Test
    @Order(1)
    void register_shouldSucceed() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(TEST_USER);
        request.setPassword("123456");
        request.setNickname("ExecE2E");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @Order(2)
    void login_shouldReturnToken() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(TEST_USER);
        request.setPassword("123456");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andDo(result -> testToken = objectMapper.readTree(result.getResponse().getContentAsString())
                        .path("data").path("token").asText());
    }

    @Test
    @Order(3)
    void execute_shouldRunEndToEndAndTraceSteps() throws Exception {
        mockMvc.perform(post("/api/analysis/execute")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析销售趋势"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.intent.intentType").value("SALES_TREND"))
                .andExpect(jsonPath("$.data.execution.rowCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.execution.columns").isNotEmpty())
                .andExpect(jsonPath("$.data.chartType").isNotEmpty())
                .andExpect(jsonPath("$.data.interpretation.text").isNotEmpty())
                .andExpect(jsonPath("$.data.interpretation.generatorType").value(anyOf(is("LLM"), is("RULE"))))
                .andExpect(jsonPath("$.data.followups").isArray())
                .andExpect(jsonPath("$.data.followups", hasSize(greaterThanOrEqualTo(2))))
                .andDo(result -> testSessionId = objectMapper.readTree(result.getResponse().getContentAsString())
                        .path("data").path("sessionId").asLong());

        assertNotNull(testSessionId);
        Integer stepCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_step WHERE session_id = ?", Integer.class, testSessionId);
        assertNotNull(stepCount);
        assertTrue(stepCount >= 6);
        Integer interpretCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_step WHERE session_id = ? AND step_type = 'INTERPRET'",
                Integer.class, testSessionId);
        assertNotNull(interpretCount);
        assertTrue(interpretCount >= 1, "execute 应落库 INTERPRET 步骤");
    }

    @Test
    @Order(4)
    void execute_shouldFailWithoutToken() throws Exception {
        mockMvc.perform(post("/api/analysis/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析销售趋势"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void execute_shouldFailWhenTextBlank() throws Exception {
        mockMvc.perform(post("/api/analysis/execute")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "  "))))
                .andExpect(status().isBadRequest());
    }
}