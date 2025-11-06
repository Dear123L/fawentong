package com.example.security;

import com.example.util.JwtUtil;
import com.example.util.JsonResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 跳过登录接口的JWT验证
        if (request.getRequestURI().equals("/api/wechat/login")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = request.getHeader("Authorization");

        // 如果没有token，直接返回JSON错误
        if (token == null || token.trim().isEmpty()) {
            sendAuthError(response, "请提供有效的Authorization头");
            return;
        }

        // 支持两种格式：有Bearer前缀和没有Bearer前缀
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        try {
            Claims claims = JwtUtil.parseToken(token);
            String openId = claims.getSubject();
            Long userId = claims.get("userId", Long.class);

            System.out.println("JWT认证成功 - openId: " + openId + ", userId: " + userId);

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(openId, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);

            // 认证成功，继续执行
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            System.out.println("JWT解析失败: " + e.getMessage());
            // 在控制台打印详细错误，但给用户友好提示
            e.printStackTrace();

            // 给用户统一的友好提示，不暴露具体错误信息
            sendAuthError(response, "Token无效或已过期");
        }
    }

    // 统一返回认证错误的方法
    private void sendAuthError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        JsonResponse jsonResponse = JsonResponse.authError(message);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(jsonResponse);
        response.getWriter().write(json);
        response.getWriter().flush();
    }
}