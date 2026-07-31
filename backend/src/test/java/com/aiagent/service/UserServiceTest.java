package com.aiagent.service;

import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.LoginResponse;
import com.aiagent.dto.RegisterRequest;
import com.aiagent.entity.User;
import com.aiagent.mapper.UserMapper;
import com.aiagent.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, passwordEncoder, jwtUtil);
    }

    @Test
    void login_shouldReturnTokenWhenCredentialsValid() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("encoded-password");
        user.setRole("ADMIN");
        user.setStatus(1);

        when(userMapper.findByUsername("admin")).thenReturn(user);
        when(passwordEncoder.matches("123456", "encoded-password")).thenReturn(true);
        when(jwtUtil.generateToken(1L, "admin", "ADMIN")).thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("123456");

        LoginResponse response = userService.login(request);

        assertNotNull(response);
        assertEquals("jwt-token", response.getToken());
        assertEquals("admin", response.getUsername());
        assertEquals("ADMIN", response.getRole());
    }

    @Test
    void login_shouldThrowWhenUserNotFound() {
        when(userMapper.findByUsername("nobody")).thenReturn(null);

        LoginRequest request = new LoginRequest();
        request.setUsername("nobody");
        request.setPassword("123456");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> userService.login(request));
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    void login_shouldThrowWhenPasswordWrong() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("encoded-password");
        user.setStatus(1);

        when(userMapper.findByUsername("admin")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false);

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        assertThrows(RuntimeException.class, () -> userService.login(request));
    }

    @Test
    void login_shouldThrowWhenAccountDisabled() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("encoded");
        user.setStatus(0);

        when(userMapper.findByUsername("admin")).thenReturn(user);
        when(passwordEncoder.matches("123456", "encoded")).thenReturn(true);

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("123456");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> userService.login(request));
        assertEquals("账号已被禁用", ex.getMessage());
    }

    @Test
    void register_shouldInsertUser() {
        when(userMapper.countByUsername("newuser")).thenReturn(0);
        when(passwordEncoder.encode("123456")).thenReturn("encoded");

        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("123456");
        request.setNickname("新用户");

        userService.register(request);

        verify(userMapper, times(1)).insert(any(User.class));
    }

    @Test
    void register_shouldThrowWhenUsernameExists() {
        when(userMapper.countByUsername("dup")).thenReturn(1);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("dup");
        request.setPassword("123456");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> userService.register(request));
        assertEquals("用户名已存在", ex.getMessage());
    }

    @Test
    void changePassword_shouldThrowWhenOldPasswordWrong() {
        User user = new User();
        user.setId(1L);
        user.setPassword("encoded-old");

        when(userMapper.findById(1L)).thenReturn(user);
        when(passwordEncoder.matches("wrong-old", "encoded-old")).thenReturn(false);

        assertThrows(RuntimeException.class, () -> userService.changePassword(1L, "wrong-old", "new-pass"));
    }

    @Test
    void changePassword_shouldUpdateWhenOldPasswordCorrect() {
        User user = new User();
        user.setId(1L);
        user.setPassword("encoded-old");

        when(userMapper.findById(1L)).thenReturn(user);
        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-pass")).thenReturn("encoded-new");

        userService.changePassword(1L, "old-pass", "new-pass");

        verify(userMapper, times(1)).update(any(User.class));
        assertEquals("encoded-new", user.getPassword());
    }
}