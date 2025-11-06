package com.example.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;

public class JwtUtil {
    private static final String SECRET_STRING = "REDACTED_JWT_SECRET";
    private static final SecretKey SECRET_KEY;

    static {
        // 不需要指定编码，英文数字不会有编码问题
        SECRET_KEY = Keys.hmacShaKeyFor(SECRET_STRING.getBytes());
    }
    // 生成 token
    public static String generateToken(String openId, Long userId) {
        return Jwts.builder()
                .setSubject(openId)
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 86400_000)) // 1 day
                .signWith(SECRET_KEY)
                .compact();
    }

    // 解析 token
    public static Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}