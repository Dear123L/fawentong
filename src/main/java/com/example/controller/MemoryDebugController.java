package com.example.controller;

import com.example.service.MemoryMetrics;
import com.example.service.SessionMemoryService;
import com.example.service.UserMemoryProfile;
import com.example.service.UserMemoryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆层调试接口（P2 护栏）。
 *
 * 鉴权：本控制器挂在 {@code /api/memory/**} 下，不属于 {@code /api/rag/} 放行前缀，
 * 因此 {@link com.example.config.SecurityConfig} 的 {@code /api/**} authenticated 规则与
 * {@code JwtAuthenticationFilter} 会要求合法 JWT（{@code Authorization: Bearer <token>}），
 * 未带 token 直接 401。这与开放的评测端点 {@code /api/rag/chatAgent/multiDebug} 形成隔离：
 * 记忆快照（可能含用户对话/参数）不会裸奔。
 *
 * 端点：
 *  - GET  /api/memory/debug/{sessionId}    完整短期记忆快照（history / calcParams / calcType / retrievedDocs + 指标）
 *  - GET  /api/memory/profile/{userId}     某用户的长期画像（P1b）
 *  - DELETE /api/memory/profile/{userId}   清空某用户长期画像（评测隔离用）
 *  - GET  /api/memory/metrics              累计 hit/miss 指标快照
 *  - POST /api/memory/metrics/reset        清零指标（不影响记忆数据）
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryDebugController {

    private final SessionMemoryService memory;
    private final MemoryMetrics metrics;
    private final UserMemoryService userMemoryService;

    public MemoryDebugController(SessionMemoryService memory, MemoryMetrics metrics, UserMemoryService userMemoryService) {
        this.memory = memory;
        this.metrics = metrics;
        this.userMemoryService = userMemoryService;
    }

    /** 某会话的完整短期记忆快照 + 当前指标。 */
    @GetMapping("/debug/{sessionId}")
    public Map<String, Object> debug(@PathVariable String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("history", memory.getHistory(sessionId));
        out.put("summary", memory.getSummary(sessionId));
        out.put("calcParams", memory.getCalcParams(sessionId));
        out.put("calcType", memory.getCalcType(sessionId));
        out.put("retrievedDocs", memory.getRetrievedDocs(sessionId)); // 滚动归档后的「上一轮」条款
        out.put("metrics", metrics.snapshot());
        return out;
    }

    /** 累计 hit/miss 指标快照。 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    /** 某用户的长期画像（P1b 长期记忆）。 */
    @GetMapping("/profile/{userId}")
    public Map<String, Object> profile(@PathVariable Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        UserMemoryProfile p = userMemoryService.getProfile(userId);
        out.put("topics", p.topics);
        out.put("preferences", p.preferences);
        out.put("freqQA", p.freqQA);
        out.put("reviewHistory", p.reviewHistory);
        out.put("corrections", p.corrections);
        out.put("lastActive", p.lastActive);
        return out;
    }

    /** 清空某用户长期画像（评测隔离用）。 */
    @DeleteMapping("/profile/{userId}")
    public Map<String, Object> clearProfile(@PathVariable Long userId) {
        userMemoryService.clearProfile(userId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("userId", userId);
        return r;
    }

    /** 清零累计指标（不影响记忆数据本身）。 */
    @PostMapping("/metrics/reset")
    public Map<String, Object> resetMetrics() {
        metrics.reset();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("metrics", metrics.snapshot());
        return r;
    }
}
