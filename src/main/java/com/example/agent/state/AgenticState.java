package com.example.agent.state;

import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单图 AgenticRAG 工作流状态。
 *
 * <p><b>注意</b>：LangGraph4j 的 {@link AgentState#data()} 是 final 且返回<b>不可变</b>视图，
 * 节点内调用 {@code state.data().put(...)} 会抛 UnsupportedOperationException。
 * 因此节点必须返回「增量 Map」，由框架通过 {@code updateState} 合入。
 *
 * <p>承载三类数据：
 * <ul>
 *   <li>控制字段：question / originalQuestion / currentQuery / kbId / retryCount / needRetrieval</li>
 *   <li>中间产物：retrievedDocs / gradedDocs / ragAnswer / finalAnswer</li>
 *   <li>控制标志：relevant（评分门是否有相关片段）、retryCount（已改写次数）</li>
 * </ul>
 */
public class AgenticState extends AgentState {

    public AgenticState() {
        super(new java.util.HashMap<>());
    }

    public AgenticState(Map<String, Object> initData) {
        super(new java.util.HashMap<>(initData == null ? Map.of() : initData));
    }

    /** 用户原始提问（改写与生成阶段使用） */
    public String getQuestion() {
        Object v = data().get("question");
        return v == null ? null : String.valueOf(v);
    }

    /** 兼容外层节点以 originalQuestion 传入的键名 */
    public String getOriginalQuestion() {
        Object v = data().get("originalQuestion");
        return v != null ? String.valueOf(v) : getQuestion();
    }

    /** 当前查询（可能被 RewriteNode 改写后覆盖） */
    public String getCurrentQuery() {
        Object v = data().get("currentQuery");
        return v != null ? String.valueOf(v) : getQuestion();
    }

    public Long getKbId() {
        Object v = data().get("kbId");
        if (v instanceof Number n) {
            return n.longValue();
        }
        return v != null ? Long.parseLong(String.valueOf(v)) : 1L;
    }

    public int getRetryCount() {
        Object v = data().get("retryCount");
        return v instanceof Number n ? n.intValue() : 0;
    }

    public boolean isNeedRetrieval() {
        return !Boolean.FALSE.equals(data().get("needRetrieval"));
    }

    /**
     * 评分门判定：本次检索是否存在相关片段。
     *
     * <p>与 {@code gradedDocs} 非空等价，落为独立布尔字段便于图路由直接读取
     * （LangGraph4j 条件边需要明确的路由信号）。
     */
    public boolean isRelevant() {
        Object v = data().get("relevant");
        if (v != null) {
            return Boolean.parseBoolean(String.valueOf(v));
        }
        return !getGradedDocs().isEmpty();
    }

    /** 已改写次数（对应 {@code retryCount}，由 RewriteNode 累加） */
    public int getRewriteCount() {
        return getRetryCount();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRetrievedDocs() {
        Object v = data().get("retrievedDocs");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getGradedDocs() {
        Object v = data().get("gradedDocs");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    /** 生成节点产出的成文答案（带引用），外层 Retriever 直接复用 */
    public String getRagAnswer() {
        Object v = data().get("ragAnswer");
        return v == null ? null : String.valueOf(v);
    }

    /** 最终答案；与 ragAnswer 合并为一个出口，供 AgenticRagGraph.execute 返回 */
    public String getFinalAnswer() {
        Object v = data().get("finalAnswer");
        if (v != null) {
            return String.valueOf(v);
        }
        return getRagAnswer();
    }

    /** 兼容别名 */
    public String getAnswer() {
        return getFinalAnswer();
    }
}
