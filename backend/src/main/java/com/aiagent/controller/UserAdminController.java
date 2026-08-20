package com.aiagent.controller;

import com.aiagent.dto.ApiResponse;
import com.aiagent.dto.PageResult;
import com.aiagent.entity.User;
import com.aiagent.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户管理端：用户列表/新增/编辑/删除（仅 ADMIN，由 SecurityConfig 统一控制）。 */
@RestController
@RequestMapping("/api/admin/user")
public class UserAdminController {

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<User>>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(ApiResponse.success(userService.listUsers(keyword, page, pageSize)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<User>> createUser(@RequestBody User request) {
        return ResponseEntity.ok(ApiResponse.success("新增成功", userService.createUser(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> updateUser(@PathVariable Long id, @RequestBody User request) {
        userService.updateUser(id, request);
        return ResponseEntity.ok(ApiResponse.success("更新成功", null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id, Authentication authentication) {
        Long currentUserId = (Long) authentication.getPrincipal();
        userService.deleteUser(id, currentUserId);
        return ResponseEntity.ok(ApiResponse.success("删除成功", null));
    }
}