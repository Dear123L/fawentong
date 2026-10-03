package com.example.service.impl;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.example.service.UserMemoryProfile;
import com.example.service.UserMemoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户长期记忆的 Redis 实现（P1b）。
 *
 * 存储：Redis Hash {@code user:profile:{uid}}，字段 contracts/preferences/freqQA/corrections/lastActive，
 * 均 30 天 TTL（长期，比 7 天会话记忆更长）。
 *
 * 摊销写入：每会话维护计数器 {@code user:extractcnt:{sid}}，仅当计数达到「每 3 轮」时调用一次 LLM 抽取。
 * 评测脚本每条问题都是独立 1 轮会话 → 永不触发抽取 → 评测与存量行为零回归。
 *
 * 召回：{@link #recall} 用中文二元文法（bigram）重叠度对画像各字段做相关度打分，仅回退与 query 有重叠的片段，
 * 无关查询返回空串，避免污染单轮 prompt。
 *
 * 容错：所有方法均 try/catch，Redis 不可用或 LLM 失败都降级为「无记忆/不抽取」，绝不拖垮主链路。
 */
@Slf4j
@Service
public class RedisUserMemoryServiceImpl implements UserMemoryService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dashscope.api.key:}")
    private String apiKey;

    @Value("${memory.compress.model:qwen-flash}")
    private String extractModel;

    /** 摊销：每 3 轮抽取一次用户信号（按会话计数）。 */
    private static final int EXTRACT_EVERY = 3;

    private static final String PROFILE_PREFIX = "user:profile:";
    private static final String EXTRACT_CNT_PREFIX = "user:extractcnt:";
    private static final long PROFILE_TTL_DAYS = 30;
    private static final long PROFILE_TTL_SECONDS = PROFILE_TTL_DAYS * 24L * 3600L;

    private static final String F_CONTRACTS = "contracts";
    private static final String F_PREFERENCES = "preferences";
    private static final String F_FREQQA = "freqQA";
    private static final String F_CORRECTIONS = "corrections";
    private static final String F_LASTACTIVE = "lastActive";

    public RedisUserMemoryServiceImpl(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ------------------------- 读取画像 -------------------------

    @Override
    public UserMemoryProfile getProfile(Long userId) {
        UserMemoryProfile p = new UserMemoryProfile();
        if (userId == null) {
            return p;
        }
        try {
            Map<Object, Object> h = redis.opsForHash().entries(PROFILE_PREFIX + userId);
            if (h == null || h.isEmpty()) {
                return p;
            }
            p.contracts = readList(h.get(F_CONTRACTS), new TypeReference<List<String>>() {});
            p.preferences = readMap(h.get(F_PREFERENCES), new TypeReference<Map<String, String>>() {});
            p.freqQA = readList(h.get(F_FREQQA), new TypeReference<List<Map<String, String>>>() {});
            p.corrections = readList(h.get(F_CORRECTIONS), new TypeReference<List<Map<String, String>>>() {});
            Object la = h.get(F_LASTACTIVE);
            p.lastActive = la != null ? la.toString() : null;
        } catch (Exception e) {
            log.warn("读取用户画像失败（Redis 不可用？），降级为空", e);
        }
        return p;
    }

    // ------------------------- 召回（相关度） -------------------------

    @Override
    public String recall(Long userId, String query) {
        if (userId == null || query == null || query.isBlank()) {
            return "";
        }
        UserMemoryProfile p = getProfile(userId);
        if (p.isEmpty()) {
            return "";
        }
        // 组装候选片段
        List<String> pieces = new ArrayList<>();
        if (p.contracts != null && !p.contracts.isEmpty()) {
            pieces.add("已关注/提及合同：" + String.join("、", p.contracts));
        }
        if (p.preferences != null && !p.preferences.isEmpty()) {
            pieces.add("偏好：" + p.preferences);
        }
        if (p.freqQA != null) {
            for (Map<String, String> f : p.freqQA) {
                String q = f.getOrDefault("q", "");
                String clause = f.get("clause");
                pieces.add("高频关注：" + q + (clause != null ? "（" + clause + "）" : ""));
            }
        }
        if (p.corrections != null) {
            for (Map<String, String> c : p.corrections) {
                pieces.add("纠错：" + c.getOrDefault("wrong", "") + "→" + c.getOrDefault("right", ""));
            }
        }
        // 二元文法重叠度打分，仅回退有重叠的片段
        Set<String> qgrams = bigrams(query);
        if (qgrams.isEmpty()) {
            return "";
        }
        List<String> matched = new ArrayList<>();
        for (String pc : pieces) {
            if (overlapCount(qgrams, bigrams(pc)) > 0) {
                matched.add(pc);
            }
        }
        if (matched.isEmpty()) {
            return "";
        }
        String joined = String.join("；", matched);
        if (joined.length() > 500) {
            joined = joined.substring(0, 500);
        }
        return joined;
    }

    // ------------------------- 摊销抽取 -------------------------

    @Override
    public void maybeExtract(Long userId, String sessionId, String question, String answer) {
        if (userId == null || sessionId == null) {
            return;
        }
        try {
            // 摊销：仅当本会话轮次计数达到每 EXTRACT_EVERY 轮时抽取一次
            Long cnt = redis.opsForValue().increment(EXTRACT_CNT_PREFIX + sessionId);
            redis.expire(EXTRACT_CNT_PREFIX + sessionId, PROFILE_TTL_SECONDS, TimeUnit.SECONDS);
            if (cnt == null || cnt % EXTRACT_EVERY != 0) {
                return;
            }
            if (apiKey == null || apiKey.isBlank()) {
                return; // 无 key 时不抽取（降级）
            }
            Map<String, Object> signals = extractViaLlm(question, answer);
            if (signals == null || signals.isEmpty()) {
                return;
            }
            mergeAndSave(userId, signals);
        } catch (Exception e) {
            log.warn("用户画像摊销抽取异常，降级为不抽取", e);
        }
    }

    /** 调用 qwen-flash 从一轮问答中抽取用户信号，返回结构化 Map；失败返回 null。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractViaLlm(String question, String answer) {
        try {
            String prompt = buildExtractPrompt(question, answer);
            Message userMsg = Message.builder().role(Role.USER.getValue()).content(prompt).build();
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(extractModel)
                    .messages(java.util.List.of(userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .temperature(0.0f)
                    .build();
            GenerationResult result = new Generation().call(param);
            if (result == null || result.getOutput() == null
                    || result.getOutput().getChoices() == null
                    || result.getOutput().getChoices().isEmpty()) {
                return null;
            }
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            return parseSignals(content);
        } catch (Exception e) {
            log.warn("画像抽取 LLM 调用失败，降级为不抽取: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 LLM 返回的 JSON（容错去除 ```json 围栏与多余文字）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSignals(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = content.trim();
        int s = json.indexOf('{');
        int e = json.lastIndexOf('}');
        if (s < 0 || e < 0 || e <= s) {
            return null;
        }
        json = json.substring(s, e + 1);
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("画像信号 JSON 解析失败，降级为不抽取", ex);
            return null;
        }
    }

    /** 把抽取信号合并进已有画像并写回 Redis。 */
    private void mergeAndSave(Long userId, Map<String, Object> signals) {
        UserMemoryProfile p = getProfile(userId);
        if (p.contracts == null) {
            p.contracts = new ArrayList<>();
        }
        if (p.preferences == null) {
            p.preferences = new HashMap<>();
        }
        if (p.freqQA == null) {
            p.freqQA = new ArrayList<>();
        }
        if (p.corrections == null) {
            p.corrections = new ArrayList<>();
        }

        mergeStringList(p.contracts, signals.get("contracts"));
        mergeStringMap(p.preferences, signals.get("preferences"));
        mergeFreqQA(p.freqQA, signals.get("freqQA"));
        mergeCorrections(p.corrections, signals.get("corrections"));

        String now = Instant.now().toString();
        p.lastActive = now;

        String key = PROFILE_PREFIX + userId;
        try {
            redis.opsForHash().put(key, F_CONTRACTS, objectMapper.writeValueAsString(p.contracts));
            redis.opsForHash().put(key, F_PREFERENCES, objectMapper.writeValueAsString(p.preferences));
            redis.opsForHash().put(key, F_FREQQA, objectMapper.writeValueAsString(p.freqQA));
            redis.opsForHash().put(key, F_CORRECTIONS, objectMapper.writeValueAsString(p.corrections));
            redis.opsForHash().put(key, F_LASTACTIVE, now);
            redis.expire(key, PROFILE_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("用户 {} 长期画像已更新（摊销抽取合并）", userId);
        } catch (Exception e) {
            log.warn("写入用户画像失败（Redis 不可用？），跳过", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeStringList(List<String> target, Object src) {
        if (!(src instanceof List)) {
            return;
        }
        Set<String> seen = new HashSet<>(target);
        for (Object o : (List<?>) src) {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty() && seen.add(s)) {
                target.add(s);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeStringMap(Map<String, String> target, Object src) {
        if (!(src instanceof Map)) {
            return;
        }
        for (Map.Entry<String, Object> en : ((Map<String, Object>) src).entrySet()) {
            target.put(en.getKey(), String.valueOf(en.getValue()));
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeFreqQA(List<Map<String, String>> target, Object src) {
        if (!(src instanceof List)) {
            return;
        }
        Set<String> seenQ = new HashSet<>();
        for (Map<String, String> f : target) {
            seenQ.add(f.getOrDefault("q", "").trim());
        }
        for (Object o : (List<?>) src) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            String q = String.valueOf(m.get("q")).trim();
            if (q.isEmpty() || seenQ.contains(q)) {
                continue;
            }
            Map<String, String> entry = new HashMap<>();
            entry.put("q", q);
            entry.put("clause", m.get("clause") != null ? String.valueOf(m.get("clause")) : "");
            target.add(entry);
            seenQ.add(q);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeCorrections(List<Map<String, String>> target, Object src) {
        if (!(src instanceof List)) {
            return;
        }
        for (Object o : (List<?>) src) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, String> entry = new HashMap<>();
            entry.put("wrong", m.get("wrong") != null ? String.valueOf(m.get("wrong")) : "");
            entry.put("right", m.get("right") != null ? String.valueOf(m.get("right")) : "");
            target.add(entry);
        }
    }

    @Override
    public void clearProfile(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redis.delete(PROFILE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("清空用户画像失败", e);
        }
    }

    // ------------------------- 序列化辅助 -------------------------

    @SuppressWarnings("unchecked")
    private <T> T readList(Object raw, TypeReference<T> ref) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw.toString(), ref);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T readMap(Object raw, TypeReference<T> ref) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw.toString(), ref);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------- 中文二元文法（bigram） -------------------------

    /** 生成中文二元文法集合（含标点/空白跳过后的字符二元组）。 */
    private Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return grams;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c) || Character.toString(c).matches("[\\u4e00-\\u9fa5]")) {
                sb.append(c);
            }
        }
        String s = sb.toString();
        for (int i = 0; i + 1 < s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        if (s.length() == 1) {
            grams.add(s);
        }
        return grams;
    }

    private int overlapCount(Set<String> a, Set<String> b) {
        int n = 0;
        for (String g : a) {
            if (b.contains(g)) {
                n++;
            }
        }
        return n;
    }

    private String buildExtractPrompt(String question, String answer) {
        return "你是法律问答系统的用户画像抽取器。给定一轮问答，抽取可长期记住的用户信号，只输出一个 JSON，不要解释。\n"
                + "JSON 结构：\n"
                + "{\n"
                + "  \"contracts\": [\"用户在对话中提及或上传的合同名称/类型，如 买卖合同、借款合同\"],\n"
                + "  \"preferences\": {\"偏好键\": \"偏好值\"},\n"
                + "  \"freqQA\": [{\"q\": \"用户高频关心的合同问题主题\", \"clause\": \"相关条款类型如 违约金/定金/生效/保密\"}],\n"
                + "  \"corrections\": [{\"wrong\": \"用户纠正过的系统错误说法\", \"right\": \"正确说法\"}]\n"
                + "}\n"
                + "规则：若某类无信号则给空数组或空对象；不编造；只基于给定问答抽取。\n\n"
                + "【用户问题】" + (question == null ? "" : question) + "\n"
                + "【系统回答】" + (answer == null ? "" : answer) + "\n\n"
                + "【输出 JSON】";
    }
}
