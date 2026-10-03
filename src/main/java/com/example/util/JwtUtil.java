package com.example.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与解析工具。
 *
 * <p>签名密钥从配置 {@code jwt.secret} 注入（推荐经环境变量 {@code JWT_SECRET} 提供），不落盘明文。
 * 保持静态方法签名以兼容既有调用方（JwtAuthenticationFilter / WeChatServiceImpl），
 * 密钥由 Spring 在容器启动时通过静态字段 + setter 注入。
 */
@Component
public class JwtUtil {

    /**
     * HS256 要求密钥长度 ≥ 32 字节，短密钥会导致 {@link Keys#hmacShaKeyFor} 抛 WeakKeyException。
     */
    private static final int MIN_SECRET_BYTES = 32;

    private static String secretString;
    private static SecretKey secretKey;

    public JwtUtil() {
    }

    /**
     * 由 Spring 注入 {@code jwt.secret}。
     * <p>校验：非空、且长度满足 HS256 下限；生产 profile 下额外拒绝使用示例默认值。
     */
    @Value("${jwt.secret}")
    public void setSecretString(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 未配置：请通过环境变量 JWT_SECRET 注入（不可留空，参见 application-local.yml.example）");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足 " + MIN_SECRET_BYTES + " 字节（当前 " + bytes.length
                            + " 字节），HS256 要求至少 32 字节，请注入足够随机的密钥");
        }
        secretString = secret;
        secretKey = Keys.hmacShaKeyFor(bytes);
    }

    /** 供启动自检与测试使用：当前是否已完成密钥注入。 */
    public static boolean isConfigured() {
        return secretKey != null;
    }

    // 生成 token
    public static String generateToken(String openId, Long userId) {
        requireConfigured();
        return Jwts.builder()
                .setSubject(openId)
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 86400_000)) // 1 day
                .signWith(secretKey)
                .compact();
    }

    // 解析 token
    public static Claims parseToken(String token) {
        requireConfigured();
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private static void requireConfigured() {
        if (secretKey == null) {
            throw new IllegalStateException("JwtUtil 未完成密钥初始化：jwt.secret 缺失或长度不足");
        }
    }
}
