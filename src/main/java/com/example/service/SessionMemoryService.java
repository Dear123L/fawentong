package com.example.service;

import com.example.calc.CalcType;

import java.util.List;

/**
 * 会话级短期记忆服务（多智能体「记忆管理」能力）。
 *
 * 职责：
 *  - getHistory(sessionId)：读取该会话最近 N 轮的对话历史（user + assistant）。
 *  - appendMessage(sessionId, role, content)：写入一条消息。
 *  - appendRound(...) / formatHistory(...)：便捷封装，供 Service 层与 Agent 节点使用。
 *
 * 设计参考 Spring AI 的 ChatMemory（conversationId + 滑动窗口 + 读写分离）范式，
 * 但为契合本项目 LangGraph4j 编排、直调 DashScope 无 Advisor 挂载点的现状，手写轻量实现。
 */
public interface SessionMemoryService {

    /**
     * 读取指定会话的历史消息（已按滑动窗口裁剪，最多最近 MAX_ROUNDS 轮）。
     */
    List<MemoryMessage> getHistory(String sessionId);

    /**
     * 追加一条消息。role 建议为 "user" 或 "assistant"。
     */
    void appendMessage(String sessionId, String role, String content);

    /**
     * 一次性追加「用户问题 + 助手答复」一对（一整轮）。
     */
    void appendRound(String sessionId, String userQuestion, String assistantAnswer);

    /**
     * 将历史格式化为可直接拼进 prompt 的文本；无历史时返回空串。
     */
    String formatHistory(String sessionId);

    /**
     * 持久化上一轮「计算抽取参数」（principal / daily_rate_per_mille / days），
     * 供追问类问题（如"改成60天呢"）继承缺失字段，实现跨轮数值连贯。
     */
    void saveCalcParams(String sessionId, java.util.Map<String, Object> params);

    /**
     * 读取上一轮计算抽取参数；无则返回 null。
     */
    java.util.Map<String, Object> getCalcParams(String sessionId);

    /**
     * 持久化上一轮「计算类型」（CalcType：PENALTY / LOAN_INTEREST 等），
     * 供追问轮（如"改成60天呢"）继承上轮 calcType，保住利率上限封顶等语境。
     */
    void saveCalcType(String sessionId, CalcType calcType);

    /**
     * 读取上一轮计算类型；无则返回 null。
     */
    CalcType getCalcType(String sessionId);

    /**
     * 持久化本轮回合检索到的条款 clauseId 列表。
     * 实现内部做滚动归档：{@link #getRetrievedDocs} 返回的是「上一轮已完成」的条款，
     * 因此追问轮（即使本轮不检索）也能锚定到前一轮的正确条款，抑制无关重检索带偏。
     */
    void saveRetrievedDocs(String sessionId, java.util.List<String> clauseIds);

    /**
     * 读取「上一轮」检索到的条款 clauseId 列表（滚动归档后的上一轮）；无则返回 null。
     */
    java.util.List<String> getRetrievedDocs(String sessionId);

    /**
     * 读取该会话的「滚动摘要」（记忆压缩 P1a 产物）。无则返回 null。
     * 摘要由超窗老轮次经 LLM 压缩合并而成，含更早轮次的约束/条款，供 prompt 注入。
     */
    String getSummary(String sessionId);
}
