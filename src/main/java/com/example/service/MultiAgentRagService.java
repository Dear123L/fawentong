package com.example.service;

/**
 * 多智能体 RAG 问答服务
 */
public interface MultiAgentRagService {

    /**
     * 端到端多智能体问答：调度 -> 检索/计算 -> 综合。
     *
     * @param kbId     知识库 ID（用于条款溯源）
     * @param sessionId 会话 ID（短期记忆键，用于跨轮上下文）
     * @param question 用户问题
     * @param userId   用户 ID（A-soft：由 JwtAuthenticationFilter 解析/回落，透传到 input Map，供 P1b 长期记忆主键使用）
     * @return 最终答复
     */
    String ask(Long kbId, String sessionId, String question, Long userId);

    /**
     * 评测用：在 ask 基础上额外回传 5 个元数据（retrieved/extracted/computed_penalty/rejected/replanned），
     * 供 RAG 量化评测打分。生产链路不受影响。
     *
     * @param kbId     知识库 ID
     * @param sessionId 会话 ID（短期记忆键）
     * @param question 用户问题
     * @param userId   用户 ID（A-soft 透传）
     * @return {answer, meta}
     */
    MultiAgentRagResult askWithMeta(Long kbId, String sessionId, String question, Long userId);
}
