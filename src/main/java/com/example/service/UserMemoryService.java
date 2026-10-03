package com.example.service;

/**
 * 用户长期记忆服务（P1b）。
 *
 * <p>与短期会话记忆 {@link SessionMemoryService} 正交：短期记忆按 sessionId 存对话历史/计算参数，
 * 长期记忆按 userId 跨会话累积「用户是谁、关心什么、纠过什么错」。二者互不覆盖——
 * 长期记忆只做 prompt 增强，绝不改写短期 state（calcParams/calcType/retrievedDocs）。</p>
 *
 * <p>设计要点：
 *  - 存储：Redis Hash {@code user:profile:{uid}}，字段见 {@link UserMemoryProfile}。
 *  - 写入：轮次结束时<b>摊销</b>抽取（不是每轮都调 LLM），由调用方异步触发。
 *  - 召回：按相关度召回与当前问题最相关的画像片段，组装成可注入 prompt 的短文。</p>
 */
public interface UserMemoryService {

    /**
     * 读取完整用户长期画像；无则返回空画像（各字段为空集合）。
     */
    UserMemoryProfile getProfile(Long userId);

    /**
     * 轮次结束时调用：按摊销策略（每 3 轮一次，按会话计数）决定是否用 LLM 抽取本轮回合的用户信号，
     * 并合并入画像。内部完全容错，任何异常都降级为「不抽取」，且不应阻塞主问答链路
     * （调用方应以异步方式调用本方法）。
     *
     * @param userId     用户 ID（A-soft 透传，1L 为匿名默认用户）
     * @param sessionId  当前会话 ID（摊销计数器键）
     * @param question   本轮用户问题
     * @param answer     本轮系统答复
     */
    void maybeExtract(Long userId, String sessionId, String question, String answer);

    /**
     * 按相关度召回画像中与当前问题最相关的片段，组装成可直接注入 prompt 的短文。
     * 无画像或不相关时返回空串（保证无关单轮不被污染）。
     */
    String recall(Long userId, String query);

    /**
     * 清空某用户画像（调试/评测隔离用）。
     */
    void clearProfile(Long userId);
}
