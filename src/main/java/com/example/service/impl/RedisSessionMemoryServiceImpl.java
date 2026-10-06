package com.example.service.impl;

import com.example.calc.CalcType;
import com.example.service.MemoryMessage;
import com.example.service.MemoryMetrics;
import com.example.service.SessionMemoryService;
import com.example.service.impl.HistoryCompressor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 会话短期记忆的 Redis 持久化实现（P0：生产级）。
 *
 * 完全实现 {@link SessionMemoryService} 接口，行为与原 {@code SessionMemoryServiceImpl}
 * （进程内 ConcurrentHashMap）保持一致，只是存储后端换成 Redis，从而：
 *  - 重启不丢记忆（持久化的核心验证点）；
 *  - 支持多实例负载均衡（记忆集中存储，不再每实例各一份）；
 *  - 所有键带 7 天 TTL，避免无限增长。
 *
 * 本类标注 {@code @Primary}，Spring 注入时自动选用本实现，调用方（ScopeCheck / Retriever /
 * Answer / MultiAgentRagServiceImpl）零改动。原内存实现保留为可回退的二级 Bean。
 *
 * 容错：每个方法均 try/catch，Redis 不可用时降级为「无记忆」（get 返回空/null，save 静默丢弃），
 * 不会因缓存抖动拖垮主链路——单轮评测在无记忆时也能正常跑。
 */
@Slf4j
@Service
@Primary
public class RedisSessionMemoryServiceImpl implements SessionMemoryService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryMetrics metrics;
    private final HistoryCompressor compressor;

    /** 滑动窗口：保留最近 10 轮对话，每轮为 user 与 assistant 两条消息。 */
    private static final int MAX_ROUNDS = 10;
    private static final int MAX_MESSAGES = MAX_ROUNDS * 2;

    /** 所有键统一 7 天过期。 */
    private static final long TTL_DAYS = 7;
    private static final long TTL_SECONDS = TTL_DAYS * 24L * 3600L;

    private static final String HISTORY_PREFIX = "memory:history:";
    private static final String CALC_PARAMS_PREFIX = "memory:calcparams:";
    private static final String CALC_TYPE_PREFIX = "memory:calctype:";
    private static final String DOCS_CUR_PREFIX = "memory:docscur:";
    private static final String DOCS_PREV_PREFIX = "memory:docsprev:";

    /** 最近保留轮数（原文不压缩）；超出部分滚动合并进 summary 键。 */
    private static final int RECENT_KEEP_ROUNDS = 4;
    private static final int RECENT_KEEP_MESSAGES = RECENT_KEEP_ROUNDS * 2;
    private static final String SUMMARY_PREFIX = "memory:summary:";

    /**
     * 检索条款滚动归档（原子 Lua）：prev = cur；cur = 新值。
     * KEYS[1]=curKey, KEYS[2]=prevKey；ARGV[1]=新值JSON, ARGV[2]=TTL秒。
     * cur 为空时删除 prev（首轮无上轮可衔接）。
     */
    private final RedisScript<Void> rollDocsScript = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1])\n" +
            "if cur then redis.call('SET', KEYS[2], cur, 'EX', ARGV[2]) else redis.call('DEL', KEYS[2]) end\n" +
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])\n" +
            "return nil", Void.class);

    public RedisSessionMemoryServiceImpl(StringRedisTemplate redis, MemoryMetrics metrics, HistoryCompressor compressor) {
        this.redis = redis;
        this.metrics = metrics;
        this.compressor = compressor;
    }

    // ------------------------- 对话历史（List + 滑窗 + TTL） -------------------------

    @Override
    public List<MemoryMessage> getHistory(String sessionId) {
        if (sessionId == null) {
            metrics.recordHistory(false);
            return Collections.emptyList();
        }
        try {
            List<String> raw = redis.opsForList().range(histKey(sessionId), 0, -1);
            if (raw == null || raw.isEmpty()) {
                metrics.recordHistory(false);
                return Collections.emptyList();
            }
            List<MemoryMessage> out = new ArrayList<>(raw.size());
            for (String s : raw) {
                MemoryMessage m = deserializeMsg(s);
                if (m != null) {
                    out.add(m);
                }
            }
            metrics.recordHistory(!out.isEmpty());
            return out;
        } catch (Exception e) {
            log.warn("getHistory 失败（Redis 不可用？），降级为空", e);
            metrics.recordHistory(false);
            return Collections.emptyList();
        }
    }

    @Override
    public void appendMessage(String sessionId, String role, String content) {
        if (sessionId == null || content == null || content.isBlank()) {
            return;
        }
        try {
            String key = histKey(sessionId);
            redis.opsForList().rightPush(key, serializeMsg(new MemoryMessage(role, content, System.currentTimeMillis())));
            // 记忆压缩（P1a）：在 trim 之前，把超出「最近保留窗口」的最老轮次滚动合并进 summary 键
            maybeCompress(sessionId, key);
            redis.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("appendMessage 失败（Redis 不可用？），静默丢弃", e);
        }
    }

    /**
     * 记忆压缩（P1a）。当窗口超过「最近保留消息数」时，取最老的溢出部分经 LLM 滚动压缩成一条摘要，
     * 写入 {@code memory:summary:{sid}}；随后把列表裁剪到仅留最近保留消息。
     *
     * 三道防约束条款误摘要兜底：
     *  1) 结构化存储兜底（最强）：calcParams/calcType/retrievedDocs 是独立键，G3 跨轮计算继承走结构化存储，
     *     不依赖本自由文本摘要，即便摘要丢失数值也不影响计算正确性。
     *  2) prompt 强制保留数值/条款（见 HistoryCompressor 的 buildPrompt）。
     *  3) 近轮不压：仅压缩超出 RECENT_KEEP_MESSAGES 的最老部分，最近 4 轮原文始终保留。
     *
     * 任何异常都降级为「不压缩」（保留到 MAX_MESSAGES 的旧行为），绝不拖垮主链路。
     */
    private void maybeCompress(String sessionId, String key) {
        try {
            Long size = redis.opsForList().size(key);
            if (size == null || size <= RECENT_KEEP_MESSAGES) {
                redis.opsForList().trim(key, -MAX_MESSAGES, -1);
                return;
            }
            int overflow = (int) (size - RECENT_KEEP_MESSAGES);
            if (overflow < 2) {
                // 半轮溢出（仅 user 消息刚压入）：等 assistant 到齐再压，避免拆散一个完整轮
                redis.opsForList().trim(key, -MAX_MESSAGES, -1);
                return;
            }
            List<String> evictedRaw = redis.opsForList().range(key, 0, overflow - 1);
            if (evictedRaw == null || evictedRaw.isEmpty()) {
                redis.opsForList().trim(key, -MAX_MESSAGES, -1);
                return;
            }
            String evictedText = formatEvicted(evictedRaw);
            String existing = readSummary(sessionId);
            String newSummary = compressor.summarize(existing, evictedText);
            if (newSummary != null && !newSummary.isBlank()) {
                writeSummary(sessionId, newSummary);
                // 仅保留最近 RECENT_KEEP_MESSAGES 条（从 overflow 起到末尾）
                redis.opsForList().trim(key, overflow, -1);
                log.info("会话 {} 记忆压缩生效：压缩 {} 条历史，summary 键已更新", sessionId, overflow);
            } else {
                // 压缩失败（如 LLM 限流）→ 降级为旧行为（保留到 MAX_MESSAGES，不做压缩）
                redis.opsForList().trim(key, -MAX_MESSAGES, -1);
            }
        } catch (Exception e) {
            log.warn("记忆压缩异常，降级为不压缩", e);
            try {
                redis.opsForList().trim(key, -MAX_MESSAGES, -1);
            } catch (Exception ignored) { }
        }
    }

    private String formatEvicted(List<String> raw) {
        StringBuilder sb = new StringBuilder();
        for (String s : raw) {
            MemoryMessage m = deserializeMsg(s);
            if (m == null) {
                continue;
            }
            sb.append("user".equals(m.role()) ? "用户：" : "助手：").append(m.content()).append("\n");
        }
        return sb.toString();
    }

    private String readSummary(String sid) {
        try {
            return redis.opsForValue().get(SUMMARY_PREFIX + sid);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeSummary(String sid, String summary) {
        try {
            redis.opsForValue().set(SUMMARY_PREFIX + sid, summary, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入 summary 失败（Redis 不可用？），跳过", e);
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
        String summary = readSummary(sessionId);
        if (history.isEmpty() && (summary == null || summary.isBlank())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append("【历史摘要（更早轮次经 LLM 压缩所得，含关键约束与条款编号）】\n")
              .append(summary)
              .append("\n\n");
        }
        for (MemoryMessage m : history) {
            sb.append("user".equals(m.role()) ? "用户：" : "助手：")
              .append(m.content())
              .append("\n");
        }
        return sb.toString().strip();
    }

    // ------------------------- 计算参数 / 类型（String(JSON) + TTL） -------------------------

    @Override
    public void saveCalcParams(String sessionId, Map<String, Object> params) {
        if (sessionId == null || params == null || params.isEmpty()) {
            return;
        }
        try {
            redis.opsForValue().set(CALC_PARAMS_PREFIX + sessionId,
                    objectMapper.writeValueAsString(params), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("saveCalcParams 失败（Redis 不可用？），静默丢弃", e);
        }
    }

    @Override
    public Map<String, Object> getCalcParams(String sessionId) {
        if (sessionId == null) {
            metrics.recordCalcParams(false);
            return null;
        }
        try {
            String v = redis.opsForValue().get(CALC_PARAMS_PREFIX + sessionId);
            if (v == null) {
                metrics.recordCalcParams(false);
                return null;
            }
            Map<String, Object> result = objectMapper.readValue(v, new TypeReference<Map<String, Object>>() {});
            metrics.recordCalcParams(result != null && !result.isEmpty());
            return result;
        } catch (Exception e) {
            log.warn("getCalcParams 失败（Redis 不可用？），降级为 null", e);
            metrics.recordCalcParams(false);
            return null;
        }
    }

    @Override
    public void saveCalcType(String sessionId, CalcType calcType) {
        if (sessionId == null || calcType == null) {
            return;
        }
        try {
            redis.opsForValue().set(CALC_TYPE_PREFIX + sessionId,
                    calcType.name(), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("saveCalcType 失败（Redis 不可用？），静默丢弃", e);
        }
    }

    @Override
    public CalcType getCalcType(String sessionId) {
        if (sessionId == null) {
            metrics.recordCalcType(false);
            return null;
        }
        try {
            String v = redis.opsForValue().get(CALC_TYPE_PREFIX + sessionId);
            if (v == null) {
                metrics.recordCalcType(false);
                return null;
            }
            CalcType result = CalcType.valueOf(v);
            metrics.recordCalcType(result != null);
            return result;
        } catch (Exception e) {
            log.warn("getCalcType 失败（Redis 不可用？），降级为 null", e);
            metrics.recordCalcType(false);
            return null;
        }
    }

    // ------------------------- 检索条款（滚动归档：cur/prev 双键） -------------------------

    @Override
    public void saveRetrievedDocs(String sessionId, List<String> clauseIds) {
        if (sessionId == null || clauseIds == null) {
            return;
        }
        try {
            String newVal = objectMapper.writeValueAsString(clauseIds);
            List<String> keys = Arrays.asList(DOCS_CUR_PREFIX + sessionId, DOCS_PREV_PREFIX + sessionId);
            redis.execute(rollDocsScript, keys, newVal, String.valueOf(TTL_SECONDS));
        } catch (Exception e) {
            log.warn("saveRetrievedDocs 失败（Redis 不可用？），静默丢弃", e);
        }
    }

    @Override
    public List<String> getRetrievedDocs(String sessionId) {
        if (sessionId == null) {
            metrics.recordDocs(false);
            return null;
        }
        try {
            String v = redis.opsForValue().get(DOCS_PREV_PREFIX + sessionId);
            if (v == null) {
                metrics.recordDocs(false);
                return null;
            }
            List<String> result = objectMapper.readValue(v, new TypeReference<List<String>>() {});
            metrics.recordDocs(result != null && !result.isEmpty());
            return result;
        } catch (Exception e) {
            log.warn("getRetrievedDocs 失败（Redis 不可用？），降级为 null", e);
            metrics.recordDocs(false);
            return null;
        }
    }

    @Override
    public String getSummary(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return readSummary(sessionId);
    }

    // ------------------------- 序列化（手写 Map，规避 record 构造器命名匹配问题） -------------------------

    private String histKey(String sid) {
        return HISTORY_PREFIX + sid;
    }

    private String serializeMsg(MemoryMessage m) throws Exception {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("role", m.role());
        o.put("content", m.content());
        o.put("timestamp", m.timestamp());
        return objectMapper.writeValueAsString(o);
    }

    private MemoryMessage deserializeMsg(String s) {
        try {
            Map<String, Object> o = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
            return new MemoryMessage(
                    (String) o.get("role"),
                    (String) o.get("content"),
                    ((Number) o.get("timestamp")).longValue());
        } catch (Exception e) {
            log.warn("MemoryMessage 反序列化失败，跳过该条", e);
            return null;
        }
    }
}
