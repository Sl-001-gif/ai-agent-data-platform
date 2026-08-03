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

/** L2 集成测试：/api/admin/dataset|data-table|field-semantic|metric CRUD（真实 MySQL dev 库）。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetadataAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String adminToken;
    private static String userToken;
    private static Long datasetId;
    private static Long tableId;
    private static Long fieldId;
    private static Long metricId;
    private static final String ADMIN_USER = "adm_meta_" + System.currentTimeMillis();
    private static final String NORMAL_USER = "usr_meta_" + System.currentTimeMillis();
    private static final String DS_NAME = "L2数据集_" + System.currentTimeMillis();
    private static final String TABLE_NAME = "l2_table_" + System.currentTimeMillis();
    private static final String FIELD_NAME = "l2_field_" + System.currentTimeMillis();
    private static final String METRIC_NAME = "L2指标_" + System.currentTimeMillis();

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
        mockMvc.perform(get("/api/admin/dataset"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void listWithNormalUserShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/dataset")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void datasetListShouldContainSeededGovData() throws Exception {
        assertTrue(listContains(mockGet("/api/admin/dataset", adminToken), "name", "邵阳政务信息公开数据"));
    }

    @Test
    @Order(6)
    void datasetCreateUpdateDeleteShouldSucceed() throws Exception {
        datasetId = createId("/api/admin/dataset", adminToken,
                datasetPayload(null, DS_NAME, "政务公开", "gov_info_record"));
        assertNotNull(datasetId);
        assertTrue(listContains(mockGet("/api/admin/dataset", adminToken), "name", DS_NAME));

        Map<String, Object> updated = datasetPayload(datasetId, DS_NAME + "_改", "政务公开", "gov_info_record");
        mockMvc.perform(put("/api/admin/dataset/" + datasetId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/dataset", adminToken), "name", DS_NAME + "_改"));

        mockMvc.perform(delete("/api/admin/dataset/" + datasetId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/dataset", adminToken), "name", DS_NAME + "_改"));
    }

    @Test
    @Order(7)
    void tableCreateUpdateDeleteShouldSucceed() throws Exception {
        tableId = createId("/api/admin/data-table", adminToken,
                tablePayload(null, 1L, TABLE_NAME, "测试表说明"));
        assertNotNull(tableId);
        assertTrue(listContains(mockGet("/api/admin/data-table", adminToken), "tableName", TABLE_NAME));

        Map<String, Object> updated = tablePayload(tableId, 1L, TABLE_NAME + "_改", "测试表说明_改");
        mockMvc.perform(put("/api/admin/data-table/" + tableId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/data-table", adminToken), "tableName", TABLE_NAME + "_改"));

        mockMvc.perform(delete("/api/admin/data-table/" + tableId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/data-table", adminToken), "tableName", TABLE_NAME + "_改"));
    }

    @Test
    @Order(8)
    void fieldCreateUpdateDeleteShouldSucceed() throws Exception {
        fieldId = createId("/api/admin/field-semantic", adminToken,
                fieldPayload(null, 1L, FIELD_NAME, "测试字段说明", "维度"));
        assertNotNull(fieldId);
        assertTrue(listContains(mockGet("/api/admin/field-semantic", adminToken), "fieldName", FIELD_NAME));

        Map<String, Object> updated = fieldPayload(fieldId, 1L, FIELD_NAME + "_改", "测试字段说明_改", "指标");
        mockMvc.perform(put("/api/admin/field-semantic/" + fieldId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/field-semantic", adminToken), "fieldName", FIELD_NAME + "_改"));

        mockMvc.perform(delete("/api/admin/field-semantic/" + fieldId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/field-semantic", adminToken), "fieldName", FIELD_NAME + "_改"));
    }

    @Test
    @Order(9)
    void metricCreateUpdateDeleteShouldSucceed() throws Exception {
        metricId = createId("/api/admin/metric", adminToken,
                metricPayload(null, 1L, METRIC_NAME, "metric_l2", "基础指标"));
        assertNotNull(metricId);
        assertTrue(listContains(mockGet("/api/admin/metric", adminToken), "name", METRIC_NAME));

        Map<String, Object> updated = metricPayload(metricId, 1L, METRIC_NAME + "_改", "metric_l2", "计算指标");
        mockMvc.perform(put("/api/admin/metric/" + metricId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(listContains(mockGet("/api/admin/metric", adminToken), "name", METRIC_NAME + "_改"));

        mockMvc.perform(delete("/api/admin/metric/" + metricId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(listContains(mockGet("/api/admin/metric", adminToken), "name", METRIC_NAME + "_改"));
    }

    @Test
    @Order(10)
    void createDatasetWithBlankNameShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/admin/dataset")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(datasetPayload(null, "", "政务公开", "gov_info_record"))))
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

    private Map<String, Object> datasetPayload(Long id, String name, String scene, String tableName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("businessScene", scene);
        body.put("tableName", tableName);
        body.put("description", "L2测试数据集");
        body.put("status", 1);
        body.put("sort", 0);
        return body;
    }

    private Map<String, Object> tablePayload(Long id, Long datasetId, String tableName, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("datasetId", datasetId);
        body.put("tableName", tableName);
        body.put("tableComment", comment);
        body.put("relationDesc", "L2测试");
        body.put("status", 1);
        body.put("sort", 0);
        return body;
    }

    private Map<String, Object> fieldPayload(Long id, Long tableId, String fieldName, String comment, String semanticType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("tableId", tableId);
        body.put("fieldName", fieldName);
        body.put("fieldComment", comment);
        body.put("fieldType", "varchar");
        body.put("semanticType", semanticType);
        body.put("businessMeaning", "L2测试字段");
        body.put("canQuery", 1);
        body.put("canAgg", 0);
        body.put("sort", 0);
        return body;
    }

    private Map<String, Object> metricPayload(Long id, Long datasetId, String name, String code, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("datasetId", datasetId);
        body.put("name", name);
        body.put("metricCode", code);
        body.put("metricType", type);
        body.put("calculationFormula", "L2测试口径");
        body.put("sqlExpression", "SELECT 1");
        body.put("description", "L2测试指标");
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
