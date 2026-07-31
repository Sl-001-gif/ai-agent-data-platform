package com.aiagent.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private static final String SECRET = "test-secret-key-for-unit-test-1234567890";

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 60000);
    }

    @Test
    void generateToken_shouldReturnValidToken() {
        String token = jwtUtil.generateToken(1L, "admin", "ADMIN");
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void parseToken_shouldReturnCorrectClaims() {
        String token = jwtUtil.generateToken(42L, "testuser", "USER");
        Claims claims = jwtUtil.parseToken(token);
        assertEquals("42", claims.getSubject());
        assertEquals("testuser", claims.get("username"));
        assertEquals("USER", claims.get("role"));
    }

    @Test
    void validateToken_shouldRejectGarbageToken() {
        assertFalse(jwtUtil.validateToken("garbage.token.value"));
    }

    @Test
    void validateToken_shouldRejectTamperedToken() {
        String token = jwtUtil.generateToken(1L, "admin", "ADMIN");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertFalse(jwtUtil.validateToken(tampered));
    }

    @Test
    void validateToken_shouldRejectExpiredToken() throws InterruptedException {
        JwtUtil shortExpiry = new JwtUtil(SECRET, 100);
        String token = shortExpiry.generateToken(1L, "admin", "ADMIN");
        Thread.sleep(200);
        assertFalse(shortExpiry.validateToken(token));
    }

    @Test
    void getUserId_shouldReturnSubject() {
        String token = jwtUtil.generateToken(99L, "user", "USER");
        assertEquals(99L, jwtUtil.getUserIdFromToken(token));
    }
}