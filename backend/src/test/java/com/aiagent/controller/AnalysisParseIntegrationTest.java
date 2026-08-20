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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisParseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String testToken;
    private static final String TEST_USER = "parse_e2e_" + System.currentTimeMillis();

    @Test
    @Order(1)
    void register_shouldSucceed() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(TEST_USER);
        request.setPassword("123456");
        request.setNickname("ParseE2E");

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
    void parse_shouldReturnIntentAndPlan() throws Exception {
        mockMvc.perform(post("/api/analysis/parse")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析最近30天的销售趋势"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.intent.intentType").value("SALES_TREND"))
                .andExpect(jsonPath("$.data.intent.confidence").isNumber())
                .andExpect(jsonPath("$.data.plan.targetTable").value("order_info"))
                .andExpect(jsonPath("$.data.plan.chartType").value("line"))
                .andExpect(jsonPath("$.data.plan.steps[0]").value("INTENT"))
                .andExpect(jsonPath("$.data.plan.steps[7]").value("REPORT"));
    }

    @Test
    @Order(4)
    void parse_shouldFailWithoutToken() throws Exception {
        mockMvc.perform(post("/api/analysis/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "分析销售趋势"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void parse_shouldFailWhenTextBlank() throws Exception {
        mockMvc.perform(post("/api/analysis/parse")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "  "))))
                .andExpect(status().isBadRequest());
    }
    @Test
    @Order(6)
    void parse_shouldReturnStructureForRegionShare() throws Exception {
        mockMvc.perform(post("/api/analysis/parse")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "邵阳市不同地区经济占比"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.intent.intentType").value("STRUCTURE"))
                .andExpect(jsonPath("$.data.plan.chartType").value("pie"))
                .andExpect(jsonPath("$.data.plan.metrics[0]").value("地区生产总值（万元）"))
                .andExpect(jsonPath("$.data.plan.dimensions[0]").value("区县"));
    }

    @Test
    @Order(7)
    void parse_shouldRejectGarbageInput() throws Exception {
        mockMvc.perform(post("/api/analysis/parse")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", "12saffg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.confidence").isNumber());
    }
}