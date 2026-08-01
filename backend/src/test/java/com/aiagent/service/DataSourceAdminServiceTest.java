package com.aiagent.service;

import com.aiagent.dto.ConnectionTestResult;
import com.aiagent.dto.DataSourceRequest;
import com.aiagent.entity.AiDataSource;
import com.aiagent.mapper.AiDataSourceMapper;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 单测：数据源 CRUD 委托、JDBC URL 拼接、连接测试成功/失败不抛错、请求校验。 */
class DataSourceAdminServiceTest {

    private final AiDataSourceMapper mapper = mock(AiDataSourceMapper.class);
    private final DataSourceAdminService service = new DataSourceAdminService(mapper);

    private DataSourceRequest request;

    @BeforeEach
    void setUp() {
        request = new DataSourceRequest();
        request.setName("测试库");
        request.setHost("localhost");
        request.setPort(3306);
        request.setDatabaseName("ai_agent_data");
        request.setUsername("root");
        request.setPassword("Admin@123456");
    }

    @Test
    void buildJdbcUrl_shouldAssembleUrl() {
        String url = service.buildJdbcUrl(request);
        assertTrue(url.startsWith("jdbc:mysql://localhost:3306/ai_agent_data?useSSL=false"));
        assertTrue(url.contains("serverTimezone=Asia/Shanghai"));
        assertTrue(url.contains("characterEncoding=utf8"));
        assertTrue(url.contains("connectTimeout=3000"));
        assertTrue(url.contains("socketTimeout=3000"));
    }

    @Test
    void create_shouldMapRequestAndInsert() {
        AiDataSource created = service.create(request, 7L);

        ArgumentCaptor<AiDataSource> captor = ArgumentCaptor.forClass(AiDataSource.class);
        verify(mapper).insert(captor.capture());
        AiDataSource saved = captor.getValue();
        assertEquals("测试库", saved.getName());
        assertEquals("MYSQL", saved.getDbType());
        assertEquals("localhost", saved.getHost());
        assertEquals(3306, saved.getPort());
        assertEquals("ai_agent_data", saved.getDatabaseName());
        assertEquals("root", saved.getUsername());
        assertEquals("Admin@123456", saved.getPassword());
        assertEquals(7L, saved.getCreateBy());
        assertEquals(created.getId(), saved.getId());
    }

    @Test
    void update_shouldMapRequestOnExisting() {
        AiDataSource existing = new AiDataSource();
        existing.setId(10L);
        when(mapper.selectById(10L)).thenReturn(existing);
        request.setName("改名库");

        service.update(10L, request);

        ArgumentCaptor<AiDataSource> captor = ArgumentCaptor.forClass(AiDataSource.class);
        verify(mapper).update(captor.capture());
        assertEquals(10L, captor.getValue().getId());
        assertEquals("改名库", captor.getValue().getName());
        assertEquals("localhost", captor.getValue().getHost());
    }

    @Test
    void update_shouldThrowWhenMissing() {
        when(mapper.selectById(99L)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.update(99L, request));
        assertEquals("数据源不存在", ex.getMessage());
        verify(mapper, never()).update(any());
    }

    @Test
    void delete_shouldDelegate() {
        when(mapper.deleteById(5L)).thenReturn(1);
        service.delete(5L);
        verify(mapper).deleteById(5L);
    }

    @Test
    void delete_shouldThrowWhenMissing() {
        when(mapper.deleteById(5L)).thenReturn(0);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.delete(5L));
        assertEquals("数据源不存在", ex.getMessage());
    }

    @Test
    void list_shouldDelegate() {
        when(mapper.selectList()).thenReturn(List.of(new AiDataSource()));

        assertEquals(1, service.list().size());
        verify(mapper).selectList();
    }

    @Test
    void testConnection_shouldReturnSuccess() {
        DataSourceAdminService svc = new DataSourceAdminService(mapper) {
            @Override
            Connection openConnection(String url, String username, String password) {
                return mock(Connection.class);
            }
        };

        ConnectionTestResult result = svc.testConnection(request);
        assertTrue(result.success());
        assertNotNull(result.message());
        assertTrue(result.latencyMs() >= 0);
    }

    @Test
    void testConnection_shouldReturnFailureReason() {
        DataSourceAdminService svc = new DataSourceAdminService(mapper) {
            @Override
            Connection openConnection(String url, String username, String password) throws SQLException {
                throw new SQLException("Access denied for user 'root'@'localhost' (using password: YES)");
            }
        };

        ConnectionTestResult result = svc.testConnection(request);
        assertFalse(result.success());
        assertTrue(result.message().contains("Access denied"));
        assertNotNull(result.latencyMs());
    }

    @Test
    void testConnection_shouldNeverThrow() {
        DataSourceAdminService svc = new DataSourceAdminService(mapper) {
            @Override
            Connection openConnection(String url, String username, String password) throws SQLException {
                throw new SQLException("Communications link failure");
            }
        };

        ConnectionTestResult result = svc.testConnection(request);
        assertNotNull(result);
        assertFalse(result.success());
    }

    @Test
    void dataSourceRequest_shouldFailValidationWhenBlank() {
        Validator validator = Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator();

        assertFalse(validator.validate(new DataSourceRequest()).isEmpty());
    }

    @Test
    void dataSourceRequest_shouldFailValidationWhenPortOutOfRange() {
        Validator validator = Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator();

        DataSourceRequest outOfRange = new DataSourceRequest();
        outOfRange.setName("测试库");
        outOfRange.setHost("localhost");
        outOfRange.setPort(70000);
        outOfRange.setDatabaseName("ai_agent_data");
        outOfRange.setUsername("root");
        outOfRange.setPassword("pwd");
        assertFalse(validator.validate(outOfRange).isEmpty());

        DataSourceRequest nullPort = new DataSourceRequest();
        nullPort.setName("测试库");
        nullPort.setHost("localhost");
        nullPort.setPort(null);
        nullPort.setDatabaseName("ai_agent_data");
        nullPort.setUsername("root");
        nullPort.setPassword("pwd");
        assertFalse(validator.validate(nullPort).isEmpty());
    }
}