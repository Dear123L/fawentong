package com.example.agent.multi;

import lombok.EqualsAndHashCode;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多智能体工作流状态：承载问题、知识库、路由决策与重试计数，并提供节点间消息通道。
 *
 * 设计：
 *  - 控制字段：question / kbId / intent（调度路由用）/ retryCount（重规划计数）/ needsMore（Critic 判定）
 *  - 消息通道：handoffs（List<String>，agent 间以 "from::content" 形式传递消息，替代共享业务字段）
 *  - 最终结果：answer
 */
@EqualsAndHashCode(callSuper = true)
public class MultiAgentState extends AgentState {

    public MultiAgentState(Map<String, Object> initData) {
        super(initData);
    }

    // question
    public String getQuestion() {
        return (String) super.data().get("question");
    }

    public void setQuestion(String value) {
        super.data().put("question", value);
    }

    // sessionId（短期记忆键，用于按会话读写对话历史；缺省空串）
    public String getSessionId() {
        Object value = super.data().get("sessionId");
        return value instanceof String ? (String) value : "";
    }

    public void setSessionId(String value) {
        super.data().put("sessionId", value == null ? "" : value);
    }

    // kbId
    public Long getKbId() {
        Object value = super.data().get("kbId");
        return value instanceof Number ? ((Number) value).longValue() : 1L;
    }

    public void setKbId(Long value) {
        super.data().put("kbId", value);
    }

    // userId（A-soft 透传的真实用户 ID，长期记忆画像键；缺省 1L 匿名）
    public Long getUserId() {
        Object value = super.data().get("userId");
        return value instanceof Number ? ((Number) value).longValue() : 1L;
    }

    public void setUserId(Long value) {
        super.data().put("userId", value == null ? 1L : value);
    }

    // userProfile：Retriever 入口召回的长期画像文本（软注入 Answer；不覆盖短期 state）
    public String getUserProfile() {
        Object value = super.data().get("userProfile");
        return value instanceof String ? (String) value : "";
    }

    public void setUserProfile(String value) {
        super.data().put("userProfile", value == null ? "" : value);
    }

    // intent: retrieve / calculate / both（调度路由用，保留为共享控制字段）
    public String getIntent() {
        return (String) super.data().get("intent");
    }

    public void setIntent(String value) {
        super.data().put("intent", value);
    }

    // retryCount（重规划计数，Critic 与路由共用）
    public int getRetryCount() {
        Object value = super.data().get("retryCount");
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    public void setRetryCount(int value) {
        super.data().put("retryCount", value);
    }

    // needsMore（Critic 判定是否需要回退重检索）
    public boolean getNeedsMore() {
        Object value = super.data().get("needsMore");
        return Boolean.TRUE.equals(value);
    }

    public void setNeedsMore(boolean value) {
        super.data().put("needsMore", value);
    }

    // handoffs：agent 间消息通道，以 "from::content" 形式传递，避免共享可变业务字段
    @SuppressWarnings("unchecked")
    public List<String> getHandoffs() {
        Object value = super.data().get("handoffs");
        if (value instanceof List) {
            return (List<String>) value;
        }
        return new ArrayList<>();
    }

    public void setHandoffs(List<String> value) {
        super.data().put("handoffs", value);
    }

    /**
     * 返回追加后的 handoffs 列表（不修改 data()——AgentState.data() 是不可变视图，直接 put 会抛 UnsupportedOperationException）。
     * 调用方应把返回值放入节点返回的 updates Map，由框架合入状态。
     */
    public List<String> appendHandoff(String from, String content) {
        List<String> list = new ArrayList<>(getHandoffs());
        list.add(from + "::" + (content == null ? "" : content));
        return list;
    }

    // answer（最终结果）
    public String getAnswer() {
        return (String) super.data().get("answer");
    }

    public void setAnswer(String value) {
        super.data().put("answer", value);
    }

    // ===== 评测元数据桥接字段（供 /chatAgent/multiDebug 回传，不影响生产链路） =====

    // retrievedDocs：来自内层 AgenticRagGraph 检索结果（RetrieverAgentNode 桥接）
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRetrievedDocs() {
        Object v = super.data().get("retrievedDocs");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    public void setRetrievedDocs(List<Map<String, Object>> value) {
        super.data().put("retrievedDocs", value);
    }

    // extractedParams：计算智能体抽取的结构化参数（CalculatorAgentNode 桥接）
    @SuppressWarnings("unchecked")
    public Map<String, Object> getExtractedParams() {
        Object v = super.data().get("extractedParams");
        return v instanceof Map ? (Map<String, Object>) v : new HashMap<>();
    }

    public void setExtractedParams(Map<String, Object> value) {
        super.data().put("extractedParams", value);
    }

    // computedPenalty：PenaltyCalculator 算出的违约金（CalculatorAgentNode 桥接）
    public Double getComputedPenalty() {
        Object v = super.data().get("computedPenalty");
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    public void setComputedPenalty(Double value) {
        super.data().put("computedPenalty", value);
    }

    // rejected：是否应拒答（GenerateNode 无相关片段 -> "无法作答"，RetrieverAgentNode 桥接）
    public boolean isRejected() {
        return Boolean.TRUE.equals(super.data().get("rejected"));
    }

    public void setRejected(boolean value) {
        super.data().put("rejected", value);
    }

    public static MultiAgentState create() {
        return new MultiAgentState(new HashMap<>());
    }
}
