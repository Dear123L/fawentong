package com.example.service;

/**
 * 单图 AgenticRAG 对话服务（retrieve → grade → rewrite → generate 内层工作流）。
 */
public interface RagAgentService {

    /**
     * 执行单图 AgenticRAG 问答。
     *
     * @param userId    用户 ID
     * @param kbId      知识库 ID
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return 成文答案（含引用）
     */
    String chatStream(Long userId, Long kbId, String sessionId, String question);
}
