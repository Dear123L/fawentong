package com.example.agent.state;

import org.bsc.langgraph4j.state.AgentStateFactory;

import java.util.Map;

/**
 * {@link AgentStateFactory} 实现：为每次图执行创建初始 {@link AgenticState}。
 *
 * <p>LangGraph4j 1.6.0-rc4 的 {@code AgentStateFactory} 是
 * {@code Function<Map<String,Object>, State>}（注意入参是普通 Map，不是 MapAgentState——
 * 后者在本版本不存在），故此处直接接收初始数据 Map 构造状态。
 */
public class AgenticStateFactory implements AgentStateFactory<AgenticState> {

    @Override
    public AgenticState apply(Map<String, Object> initData) {
        return new AgenticState(initData);
    }
}
