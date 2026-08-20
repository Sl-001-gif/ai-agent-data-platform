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
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L2 集成测试：/api/analysis/report 报告生成（真实 MySQL dev 库，覆盖式落库）。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisReportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String testToken;
    private static Long testSessionId;
    private static Long testUserId;
    private static final String TEST_USER = "rep_e2e_" + System.currentTimeMillis();

    @BeforeEach
    void setUpDemoData() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS order_info (order_date DATE, region VARCHAR(50), channel VARCHAR(50), category VARCHAR(50), order_count INT, sales_amount DECIMAL(12,2), sales_volume INT)");
        jdbcTemplate.update("DELETE FROM order_info");
        jdbcTemplate.update("INSERT INTO order_info (order_date, region, channel, category, order_count, sales_amount, sales_volume) VALUES (CURDATE(), '华东', '线上', '手机', 120, 120000.00, 140)");
        jdbcTemplate.update("INSERT INTO order_info (order_date, region, channel, category, order_count, sales_amount, sales_volume) VALUES (DATE_SUB(CURDATE(), INTERVAL 1 DAY), '华南', '线下', '家电', 80, 90000.00, 95)");
        jdbcTemplate.update("INSERT INTO order_info (order_date, region, channel, category, order_count, sales_amount, sales_volume) VALUES (DATE_SUB(CURDATE(), INTERVAL 2 DAY), '华东', '线下', '手机', 60, 66000.00, 70)");
    }

    @Test
    @Order(1)
    void register_shouldSucceed() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(TEST_USER);
        request.setPassword("123456");
        request.setNickname("ReportE2E");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        testUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, TEST_USER);
        assertNotNull(testUserId);
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
    void execute_shouldProvideSessionForReport() throws Exception {
        mockMvc.perform(post("/api/analysis/execute")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析销售趋势"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andDo(result -> testSessionId = objectMapper.readTree(result.getResponse().getContentAsString())
                        .path("data").path("sessionId").asLong());

        assertNotNull(testSessionId);
    }

    @Test
    @Order(4)
    void report_shouldGenerateAndPersist() throws Exception {
        mockMvc.perform(post("/api/analysis/report")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sessionId", testSessionId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(testSessionId))
                .andExpect(jsonPath("$.data.report.title").isNotEmpty())
                .andExpect(jsonPath("$.data.report.content").isNotEmpty())
                .andExpect(jsonPath("$.data.report.generatorType").value(anyOf(is("LLM"), is("RULE"))))
                .andExpect(jsonPath("$.data.report.chart.chartType").isNotEmpty())
                .andExpect(jsonPath("$.data.report.chart.columns").isArray());

        Integer reportCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_report WHERE session_id = ?", Integer.class, testSessionId);
        assertNotNull(reportCount);
        assertEquals(1, reportCount, "报告应落库 1 行");
        Integer reportStep = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_step WHERE session_id = ? AND step_type = 'REPORT'",
                Integer.class, testSessionId);
        assertNotNull(reportStep);
        assertTrue(reportStep >= 1, "REPORT 步骤应落库");
    }

    @Test
    @Order(5)
    void report_shouldOverwriteOldReport() throws Exception {
        mockMvc.perform(post("/api/analysis/report")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sessionId", testSessionId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer reportCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_report WHERE session_id = ?", Integer.class, testSessionId);
        assertNotNull(reportCount);
        assertEquals(1, reportCount, "覆盖式：重复生成仍应只有 1 行");
    }

    @Test
    @Order(6)
    void report_shouldFailWithoutSessionId() throws Exception {
        mockMvc.perform(post("/api/analysis/report")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    void report_shouldFailWithoutToken() throws Exception {
        mockMvc.perform(post("/api/analysis/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sessionId", testSessionId))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(8)
    void report_shouldFailForInvalidSession() throws Exception {
        mockMvc.perform(post("/api/analysis/report")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sessionId", 999999999L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    @Order(9)
    void report_shouldFailWhenSessionNeverExecuted() throws Exception {
        jdbcTemplate.update("INSERT INTO analysis_session (user_id, title, status) VALUES (?, '未执行会话', 'ACTIVE')",
                testUserId);
        Long rawSessionId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM analysis_session WHERE user_id = ?", Long.class, testUserId);
        assertNotNull(rawSessionId);

        mockMvc.perform(post("/api/analysis/report")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sessionId", rawSessionId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
    }
}
