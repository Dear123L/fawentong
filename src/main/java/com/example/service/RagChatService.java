package com.example.service;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 单轮 RAG 对话服务（SSE 流式 + 历史查询）。
 */
public interface RagChatService {

    /**
     * 流式对话。
     *
     * @param userId    用户 ID
     * @param kbId      知识库 ID
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return SSE 文本流，逐段吐出答案
     */
    Flux<String> chatStream(Long userId, Long kbId, String sessionId, String question);

    /**
     * 查询会话历史轮次（question/answer 对）。
     */
    List<Map<String, String>> getConversationHistory(Long userId, String sessionId);
}
