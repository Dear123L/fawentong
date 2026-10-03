package com.example.service.impl;

import com.example.calc.CalcType;
import com.example.service.MemoryMessage;
import com.example.service.SessionMemoryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话短期记忆的默认实现。
 *
 * <p>当前为本地内存实现（ConcurrentHashMap），保证无 Redis 依赖也能编译运行，便于演示与单测。
 * 滑动窗口：仅保留最近 {@code MAX_ROUNDS} 轮（每轮 = user + assistant 两条），防止 prompt 无限膨胀。</p>
 *
 * <p><b>生产环境替换为 Redis（零改动接口，仅换实现）：</b>
 * <pre>
 * &#64;Service
 * public class RedisSessionMemoryServiceImpl implements SessionMemoryService {
 *     private final StringRedisTemplate redis;
 *     private static final int MAX_MESSAGES = 20; // 10 轮 * 2
 *     private static final String KEY(String sid) { return "memory:" + sid; }
 *
 *     public List&lt;MemoryMessage&gt; getHistory(String sid) {
 *         // LRANGE key -MAX_MESSAGES -1 读取最近窗口
 *         List&lt;String&gt; raw = redis.opsForList().range(KEY(sid), -MAX_MESSAGES, -1);
 *         // 反序列化为 MemoryMessage（JSON）...
 *     }
 *     public void appendMessage(String sid, String role, String content) {
 *         redis.opsForList().rightPush(KEY(sid), toJson(new MemoryMessage(role, content, System.currentTimeMillis())));
 *         redis.opsForList().trim(KEY(sid), -MAX_MESSAGES, -1); // 滑动窗口裁剪
 *         redis.expire(KEY(sid), Duration.ofDays(7));
 *     }
 *     // appendRound / formatHistory 同上
 * }
 * </pre>
 * 切换时把本类的 {@code @Service} 换成 {@code @Service} 之外的限定名，或在 Redis 实现上加
 * {@code @Primary} 即可，调用方（节点 / Service）完全无感。
 * </p>
 */
@Service
public class SessionMemoryServiceImpl implements SessionMemoryService {

    /** 滑动窗口：保留最近 10 轮对话（每轮 = user + assistant 两条消息）。 */
    private static final int MAX_ROUNDS = 10;
    private static final int MAX_MESSAGES = MAX_ROUNDS * 2;

    /**
     * 本地内存存储：sessionId -> 有序消息列表。
     * 使用 ConcurrentHashMap + synchronizedList 保证多请求并发安全。
     * 生产环境替换为 Redis 实现（见类注释）。
     */
    private final Map<String, List<MemoryMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<MemoryMessage> getHistory(String sessionId) {
        List<MemoryMessage> list = store.get(sessionId);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    @Override
    public void appendMessage(String sessionId, String role, String content) {
        if (sessionId == null || content == null || content.isBlank()) {
            return;
        }
        List<MemoryMessage> list = store.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(new MemoryMessage(role, content, System.currentTimeMillis()));
            // 滑动窗口裁剪：仅保留最近 MAX_MESSAGES 条，移除最早的旧消息
            if (list.size() > MAX_MESSAGES) {
                list.subList(0, list.size() - MAX_MESSAGES).clear();
            }
        }
    }

    @Override
    public void appendRound(String sessionId, String userQuestion, String assistantAnswer) {
        appendMessage(sessionId, "user", userQuestion);
        if (assistantAnswer != null && !assistantAnswer.isBlank()) {
            appendMessage(sessionId, "assistant", assistantAnswer);
        }
    }

    @Override
    public String formatHistory(String sessionId) {
        List<MemoryMessage> history = getHistory(sessionId);
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryMessage m : history) {
            sb.append("user".equals(m.role()) ? "用户：" : "助手：")
              .append(m.content())
              .append("\n");
        }
        return sb.toString().strip();
    }

    /** 上一轮计算抽取参数（principal / daily_rate_per_mille / days），用于追问继承。 */
    private final Map<String, Map<String, Object>> calcStore = new ConcurrentHashMap<>();

    @Override
    public void saveCalcParams(String sessionId, Map<String, Object> params) {
        if (sessionId == null || params == null || params.isEmpty()) {
            return;
        }
        calcStore.put(sessionId, new java.util.HashMap<>(params));
    }

    @Override
    public Map<String, Object> getCalcParams(String sessionId) {
        Map<String, Object> m = calcStore.get(sessionId);
        return m == null ? null : new java.util.HashMap<>(m);
    }

    /** 上一轮计算类型（CalcType），用于追问轮继承（保住利率上限等语境）。 */
    private final Map<String, CalcType> calcTypeStore = new ConcurrentHashMap<>();

    @Override
    public void saveCalcType(String sessionId, CalcType calcType) {
        if (sessionId == null || calcType == null) {
            return;
        }
        calcTypeStore.put(sessionId, calcType);
    }

    @Override
    public CalcType getCalcType(String sessionId) {
        return calcTypeStore.get(sessionId);
    }

    /**
     * 检索条款滚动归档：docStore 存「本轮」clauseId，prevDocStore 存「上一轮」clauseId。
     * 每次 save 时把当前 docStore 的旧值滚入 prevDocStore，再写入新值。
     * 这样无论同轮内 Retriever 是否在 Answer 之前执行，getRetrievedDocs 始终返回上一轮已完成条款。
     */
    private final Map<String, List<String>> docStore = new ConcurrentHashMap<>();
    private final Map<String, List<String>> prevDocStore = new ConcurrentHashMap<>();

    @Override
    public void saveRetrievedDocs(String sessionId, List<String> clauseIds) {
        if (sessionId == null || clauseIds == null) {
            return;
        }
        docStore.compute(sessionId, (k, old) -> {
            // ConcurrentHashMap 不允许 null value：首轮 old 为 null 时移除而非 put(null)
            if (old == null) {
                prevDocStore.remove(sessionId);
            } else {
                prevDocStore.put(sessionId, new ArrayList<>(old));
            }
            return new ArrayList<>(clauseIds);
        });
    }

    @Override
    public List<String> getRetrievedDocs(String sessionId) {
        List<String> prev = prevDocStore.get(sessionId);
        return prev == null ? null : new ArrayList<>(prev);
    }

    @Override
    public String getSummary(String sessionId) {
        // 内存回退实现不做压缩，无摘要
        return null;
    }
}
