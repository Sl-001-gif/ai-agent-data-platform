package com.aiagent.controller;

import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String testToken;
    private static final String TEST_USER = "sql_e2e_" + System.currentTimeMillis();

    @Test
    @Order(1)
    void register_shouldSucceed() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(TEST_USER);
        request.setPassword("123456");
        request.setNickname("SqlE2E");

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
    void sql_shouldReturnGeneratedSqlForSalesTrend() throws Exception {
        mockMvc.perform(post("/api/analysis/sql")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析最近30天的销售趋势"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.intent.intentType").value("SALES_TREND"))
                .andExpect(jsonPath("$.data.plan.targetTable").value("order_info"))
                .andExpect(jsonPath("$.data.sql").isNotEmpty())
                .andExpect(jsonPath("$.data.sql", startsWith("SELECT")))
                .andExpect(jsonPath("$.data.validation.valid").value(true))
                .andExpect(jsonPath("$.data.validation.errors").isArray())
                .andExpect(jsonPath("$.data.validation.errors").isEmpty());
    }

    @Test
    @Order(4)
    void sql_shouldMapTargetTablesPerIntent() throws Exception {
        mockMvc.perform(post("/api/analysis/sql")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "看看我们的用户画像"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent.intentType").value("USER_PROFILE"))
                .andExpect(jsonPath("$.data.sql", containsString("user_info")));

        mockMvc.perform(post("/api/analysis/sql")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "哪个商品卖得最好，排个Top10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent.intentType").value("RANKING"))
                .andExpect(jsonPath("$.data.sql", containsString("product_info")));

        mockMvc.perform(post("/api/analysis/sql")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "各品类销售占比"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent.intentType").value("STRUCTURE"))
                .andExpect(jsonPath("$.data.sql", containsString("order_info")));
    }

    @Test
    @Order(5)
    void sql_shouldFailWithoutToken() throws Exception {
        mockMvc.perform(post("/api/analysis/sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析销售趋势"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void sql_shouldFailWhenTextBlank() throws Exception {
        mockMvc.perform(post("/api/analysis/sql")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "  "))))
                .andExpect(status().isBadRequest());
    }
}