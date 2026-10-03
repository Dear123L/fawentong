package com.example.service.impl;

import com.example.entity.RagConversation;
import com.example.mapper.RagConversationMapper;
import com.example.service.RagChatService;
import com.example.service.RagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单轮 RAG 对话实现：检索 → 拼引用 → LLM 成文 → 落历史。
 */
@Slf4j
@Service
public class RagChatServiceImpl implements RagChatService {

    private final RagVectorService ragVectorService;
    private final RagConversationMapper conversationMapper;

    @Value("${dashscope.api.key}")
    private String dashscopeApiKey;

    @Value("${dashscope.api.model:deepseek-v3}")
    private String model;

    private static final int TOP_K = 5;

    public RagChatServiceImpl(RagVectorService ragVectorService, RagConversationMapper conversationMapper) {
        this.ragVectorService = ragVectorService;
        this.conversationMapper = conversationMapper;
    }

    @Override
    public Flux<String> chatStream(Long userId, Long kbId, String sessionId, String question) {
        // TODO: 需要按原实现校对——原实现为真正的 SSE 逐 token 透传（DashScope stream + Flux 桥接），
        //      当前实现为"整段生成后一次性吐出"，前端体验相同但非真流式。
        return Flux.defer(() -> {
            String answer = answer(userId, kbId, sessionId, question);
            return Flux.just(answer);
        });
    }

    private String answer(Long userId, Long kbId, String sessionId, String question) {
        List<Map<String, Object>> docs = ragVectorService.hybridSearch(kbId, question, TOP_K);
        if (docs == null || docs.isEmpty()) {
            return "抱歉，知识库中未检索到相关合同条款，无法作答。";
        }
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Object> d = docs.get(i);
            ctx.append("[").append(i + 1).append("] ")
               .append(d.get("content"))
               .append("\n");
        }
        // TODO: 需要按原实现校对——原实现的 system prompt 与引用格式（是否要求输出 [n] 标注）
        String prompt = "你是法律助手。请仅依据以下检索到的条款回答问题，"
                + "并在引用处标注条款序号（如 [1]）。若条款不足以回答，请明确说明。\n\n"
                + "检索到的条款：\n" + ctx + "\n问题：" + question;

        String result = callLlm(prompt);
        saveHistory(userId, kbId, sessionId, question, result, docs);
        return result;
    }

    private String callLlm(String prompt) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", Map.of("messages", List.of(
                    Map.of("role", "system", "content", "你是一个严谨的中国法律助手，只依据给定材料回答。"),
                    Map.of("role", "user", "content", prompt))));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(dashscopeApiKey);
            Map<?, ?> resp = new RestTemplate().postForObject(
                    "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation",
                    new HttpEntity<>(body, headers), Map.class);

            Map<?, ?> output = resp == null ? null : (Map<?, ?>) resp.get("output");
            if (output != null) {
                Object choices = output.get("choices");
                if (choices instanceof List<?> list && !list.isEmpty()) {
                    Map<?, ?> first = (Map<?, ?>) list.get(0);
                    Map<?, ?> message = (Map<?, ?>) first.get("message");
                    if (message != null && message.get("content") != null) {
                        return String.valueOf(message.get("content"));
                    }
                }
            }
            log.warn("DashScope 响应结构异常, 返回空答案");
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
        }
        return "抱歉，服务暂时不可用，请稍后重试。";
    }

    private void saveHistory(Long userId, Long kbId, String sessionId, String question,
                             String answer, List<Map<String, Object>> docs) {
        try {
            List<String> clauseIds = new ArrayList<>();
            for (Map<String, Object> d : docs) {
                Object cid = d.get("clauseId");
                if (cid != null) {
                    clauseIds.add(String.valueOf(cid));
                }
            }
            RagConversation conv = new RagConversation();
            conv.setSessionId(sessionId);
            conv.setUserId(userId);
            conv.setKbId(kbId);
            conv.setTurnIndex(conversationMapper.maxTurnIndex(sessionId) + 1);
            conv.setQuestion(question);
            conv.setAnswer(answer);
            conv.setClauseIds(String.join(",", clauseIds));
            conversationMapper.insert(conv);
        } catch (Exception e) {
            // 历史落库失败不阻断主问答链路
            log.warn("会话历史落库失败: {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, String>> getConversationHistory(Long userId, String sessionId) {
        List<Map<String, String>> out = new ArrayList<>();
        List<RagConversation> list = conversationMapper.selectBySessionId(sessionId);
        for (RagConversation c : list) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("question", c.getQuestion());
            m.put("answer", c.getAnswer());
            out.add(m);
        }
        return out;
    }
}
