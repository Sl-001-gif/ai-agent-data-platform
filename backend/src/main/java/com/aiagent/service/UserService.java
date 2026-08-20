package com.aiagent.service;

import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.PageResult;
import com.aiagent.dto.LoginResponse;
import com.aiagent.dto.RegisterRequest;
import com.aiagent.entity.User;
import com.aiagent.mapper.UserMapper;
import java.util.ArrayList;
import java.util.List;
import com.aiagent.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new RuntimeException("账号已被禁用");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getNickname(), user.getRole());
    }

    public void register(RegisterRequest request) {
        if (userMapper.countByUsername(request.getUsername()) > 0) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setRole("USER");
        user.setStatus(1);

        userMapper.insert(user);
    }

    public User getById(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }

    public void updateProfile(Long userId, User update) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        user.setNickname(update.getNickname());
        user.setEmail(update.getEmail());
        user.setPhone(update.getPhone());
        userMapper.update(user);
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.update(user);
    }

    // ---------- 管理端用户管理 ----------

    private static final int MAX_PAGE_SIZE = 1000;

    public PageResult<User> listUsers(String keyword, int page, int pageSize) {
        List<User> users = userMapper.findAll();
        List<User> result = new ArrayList<>();
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        for (User user : users) {
            if (kw != null && !kw.isEmpty()
                    && !user.getUsername().toLowerCase().contains(kw)
                    && (user.getNickname() == null || !user.getNickname().toLowerCase().contains(kw))) {
                continue;
            }
            user.setPassword(null);
            result.add(user);
        }
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int offset = (int) Math.min(Math.max((long) (page - 1) * size, 0L), result.size());
        int end = Math.min(offset + size, result.size());
        List<User> rows = offset >= result.size() ? new ArrayList<>()
                : new ArrayList<>(result.subList(offset, end));
        return PageResult.of(rows, result.size());
    }

    public User createUser(User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new RuntimeException("账号不能为空");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new RuntimeException("密码不能为空");
        }
        if (userMapper.countByUsername(user.getUsername()) > 0) {
            throw new RuntimeException("用户名已存在");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            user.setNickname(user.getUsername());
        }
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("USER");
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        userMapper.insert(user);
        return user;
    }

    public void updateUser(Long id, User update) {
        User existing = userMapper.findById(id);
        if (existing == null) {
            throw new RuntimeException("用户不存在");
        }
        existing.setNickname(update.getNickname());
        existing.setEmail(update.getEmail());
        existing.setPhone(update.getPhone());
        existing.setRole(update.getRole());
        existing.setStatus(update.getStatus());
        if (update.getPassword() != null && !update.getPassword().isBlank()) {
            existing.setPassword(passwordEncoder.encode(update.getPassword()));
        }
        userMapper.update(existing);
    }

    public void deleteUser(Long id, Long currentUserId) {
        if (id.equals(currentUserId)) {
            throw new RuntimeException("不能删除当前登录账号");
        }
        User existing = userMapper.findById(id);
        if (existing == null) {
            throw new RuntimeException("用户不存在");
        }
        userMapper.deleteById(id);
    }
}