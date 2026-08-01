package com.aiagent.controller;

import com.aiagent.ai.intent.RecognizedIntent;
import com.aiagent.ai.planner.AnalysisPlan;
import com.aiagent.ai.sql.SqlGenerator;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L2 集成测试：SQL 校验失败时 /execute 返回 422 且不执行。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisExecuteInvalidSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SqlGenerator sqlGenerator;

    private static String testToken;
    private static final String TEST_USER = "exec_inv_e2e_" + System.currentTimeMillis();

    @BeforeEach
    void stubSqlGenerator() {
        when(sqlGenerator.generate(any(AnalysisPlan.class), any(RecognizedIntent.class)))
                .thenReturn(new SqlGenerator.GeneratedSql("SELECT * FROM sys_user", "RULE"));
    }

    @Test
    @Order(1)
    void register_shouldSucceed() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(TEST_USER);
        request.setPassword("123456");
        request.setNickname("ExecInvalidE2E");

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
    void execute_shouldReturn422WhenValidationFails() throws Exception {
        mockMvc.perform(post("/api/analysis/execute")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析销售趋势"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.execution").doesNotExist())
                .andExpect(jsonPath("$.data.validation").exists())
                .andExpect(jsonPath("$.data.validation.valid").value(false));
    }
}