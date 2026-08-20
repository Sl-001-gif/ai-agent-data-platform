package com.aiagent.controller;

import com.aiagent.ai.model.ModelRouter;
import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @org.springframework.boot.test.mock.mockito.MockBean
    private ModelRouter modelRouter;

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

    private void ensureGovTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS gov_info_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(500), doc_no VARCHAR(100), publish_unit VARCHAR(200), category VARCHAR(100), publish_date DATE, source_url VARCHAR(500) UNIQUE, summary TEXT, create_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
        Integer cnt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gov_info_record", Integer.class);
        if (cnt == null || cnt == 0) {
            jdbcTemplate.update("INSERT INTO gov_info_record (title, category, publish_date, source_url) VALUES "
                    + "('邵阳市政务公开年度报告','工作动态',CURDATE(),'https://shaoyang.gov.cn/xxgk/l2seed1'),"
                    + "('邵阳市财政信息','财政信息',DATE_SUB(CURDATE(), INTERVAL 1 DAY),'https://shaoyang.gov.cn/xxgk/l2seed2'),"
                    + "('邵阳市统计信息','统计信息',DATE_SUB(CURDATE(), INTERVAL 2 DAY),'https://shaoyang.gov.cn/xxgk/l2seed3')");
        }
    }

    private String executeGov(String text) throws Exception {
        return mockMvc.perform(post("/api/analysis/execute")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private boolean lastSqlWasRule(Long sessionId) {
        String out = jdbcTemplate.queryForObject(
                "SELECT output_data FROM analysis_step WHERE session_id = ? AND step_type = 'SQL' ORDER BY id DESC LIMIT 1",
                String.class, sessionId);
        return out != null && out.contains("\"RULE\"");
    }

    @Test
    @Order(6)
    void execute_govTrend_shouldReturnRealRows() throws Exception {
        ensureGovTable();
        JsonNode data = objectMapper.readTree(executeGov("邵阳近3年按月发文量趋势")).path("data");
        assertEquals("SALES_TREND", data.path("intent").path("intentType").asText());
        assertEquals("GOV_INFO_RECORD", data.path("plan").path("targetTable").asText());
        assertEquals("line", data.path("chartType").asText());
        JsonNode execution = data.path("execution");
        assertTrue(execution.path("rowCount").asLong() > 0, "政务趋势结果行数大于 0（真实数据）");
        assertTrue(execution.path("columns").size() >= 2, "趋势结果含月份与发文量两列");
    }

    @Test
    @Order(7)
    void execute_govStructure_shouldSumToTotalRecords() throws Exception {
        ensureGovTable();
        JsonNode data = objectMapper.readTree(executeGov("各公开类目发文量分布占比")).path("data");
        assertEquals("STRUCTURE", data.path("intent").path("intentType").asText());
        assertEquals("GOV_INFO_RECORD", data.path("plan").path("targetTable").asText());
        JsonNode execution = data.path("execution");
        Long sessionId = data.path("sessionId").asLong();
        long sum = 0;
        for (JsonNode row : execution.path("rows")) {
            var it = row.fields();
            while (it.hasNext()) {
                var entry = it.next();
                if (entry.getValue().isNumber()) {
                    sum += entry.getValue().asLong();
                }
            }
        }
        assertTrue(sum > 0, "类目分布各行发文量之和大于 0");
        if (lastSqlWasRule(sessionId)) {
            Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gov_info_record", Integer.class);
            assertNotNull(total);
            assertEquals(total.longValue(), sum, "类目分布各行之和应等于全表记录数（占比口径分母=100%）");
        }
    }

    @Test
    @Order(8)
    void execute_govRanking_shouldReturnNonEmptyUnits() throws Exception {
        ensureGovTable();
        JsonNode data = objectMapper.readTree(executeGov("各部门/单位发文量排名Top10")).path("data");
        assertEquals("RANKING", data.path("intent").path("intentType").asText());
        assertEquals("GOV_INFO_RECORD", data.path("plan").path("targetTable").asText());
        JsonNode execution = data.path("execution");
        Long sessionId = data.path("sessionId").asLong();
        long rowCount = execution.path("rowCount").asLong();
        assertTrue(rowCount >= 1 && rowCount <= 10, "排名行数应在 1~10");
        if (lastSqlWasRule(sessionId)) {
            for (JsonNode row : execution.path("rows")) {
                String unit = row.path("unit").asText("");
                if (unit.isBlank()) {
                    unit = row.path("publish_unit").asText("");
                }
                assertTrue(!unit.isBlank(), "排名单位非空（COALESCE 回退类目）");
                assertTrue(row.path("doc_count").asLong() > 0, "排名发文量大于 0");
            }
        }
    }
}
