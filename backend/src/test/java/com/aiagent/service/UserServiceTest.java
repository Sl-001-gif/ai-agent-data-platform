package com.aiagent.service;

import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.LoginResponse;
import com.aiagent.dto.PageResult;
import com.aiagent.dto.RegisterRequest;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void listUsers_shouldSlicePagesAndMaskPassword() {
        List<User> all = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            User u = new User();
            u.setId((long) i);
            u.setUsername("user" + i);
            u.setNickname("用户" + i);
            u.setPassword("secret" + i);
            all.add(u);
        }
        when(userMapper.findAll()).thenReturn(all);

        PageResult<User> page1 = userService.listUsers(null, 1, 10);
        assertEquals(25, page1.getTotal());
        assertEquals(10, page1.getRows().size());
        assertEquals(1L, page1.getRows().get(0).getId());
        assertNull(page1.getRows().get(0).getPassword(), "分页行不应返回密码");

        PageResult<User> page3 = userService.listUsers(null, 3, 10);
        assertEquals(5, page3.getRows().size());
        assertEquals(21L, page3.getRows().get(0).getId());

        PageResult<User> beyond = userService.listUsers(null, 5, 10);
        assertEquals(0, beyond.getRows().size());
        assertEquals(25, beyond.getTotal());
    }

    @Test
    void listUsers_shouldFilterByKeywordThenPaginate() {
        List<User> all = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            User u = new User();
            u.setId((long) i);
            u.setUsername("admin" + i);
            u.setPassword("p");
            all.add(u);
        }
        User other = new User();
        other.setId(99L);
        other.setUsername("zhang");
        other.setPassword("p");
        all.add(other);
        when(userMapper.findAll()).thenReturn(all);

        PageResult<User> hit = userService.listUsers("zhang", 1, 10);
        assertEquals(1, hit.getTotal());
        assertEquals("zhang", hit.getRows().get(0).getUsername());

        PageResult<User> page2 = userService.listUsers("admin", 2, 10);
        assertEquals(20, page2.getTotal());
        assertEquals(10, page2.getRows().size());
        assertEquals("admin11", page2.getRows().get(0).getUsername());
    }

    @Test
    void listUsers_shouldClampPageSizeAndPageBounds() {
        List<User> all = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            User u = new User();
            u.setId((long) i);
            u.setUsername("user" + i);
            u.setPassword("p");
            all.add(u);
        }
        when(userMapper.findAll()).thenReturn(all);

        PageResult<User> big = userService.listUsers(null, 1, 9999);
        assertEquals(15, big.getTotal());
        assertEquals(15, big.getRows().size(), "pageSize 超上限应钳到 1000（数据量小则全量返回）");

        PageResult<User> zeroPage = userService.listUsers(null, 0, 10);
        assertEquals(10, zeroPage.getRows().size(), "page<=0 应钳为第 1 页");

        PageResult<User> zeroSize = userService.listUsers(null, 1, 0);
        assertEquals(1, zeroSize.getRows().size(), "pageSize<=0 应钳为 1");

        PageResult<User> hugePage = userService.listUsers(null, Integer.MAX_VALUE, 10);
        assertEquals(0, hugePage.getRows().size(), "超大页码应返回空 rows 不报错");
        assertEquals(15, hugePage.getTotal());
    }
}