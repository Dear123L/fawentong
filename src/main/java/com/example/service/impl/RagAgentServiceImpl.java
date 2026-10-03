package com.example.service.impl;

import com.example.agent.graph.AgenticRagGraph;
import com.example.agent.state.AgenticState;
import com.example.service.RagAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 单图 AgenticRAG 对话实现：把请求交给 retrieve→grade→rewrite→generate 内层工作流。
 *
 * <p>与 {@code MultiAgentRagService}（多智能体外层编排）的区别：本服务只跑单图，
 * 不做范围判定 / 计算 / 评审，外层 {@code RetrieverAgentNode} 会复用它作为检索能力。
 */
@Slf4j
@Service
public class RagAgentServiceImpl implements RagAgentService {

    private final AgenticRagGraph agenticRagGraph;

    public RagAgentServiceImpl(AgenticRagGraph agenticRagGraph) {
        this.agenticRagGraph = agenticRagGraph;
    }

    @Override
    public String chatStream(Long userId, Long kbId, String sessionId, String question) {
        Map<String, Object> input = new HashMap<>();
        input.put("question", question);
        input.put("currentQuery", question);
        input.put("kbId", kbId);
        input.put("retryCount", 0);
        input.put("needRetrieval", true);

        try {
            AgenticState state = agenticRagGraph.execute(input);
            String answer = state.getFinalAnswer();
            return (answer != null && !answer.isBlank()) ? answer : "（未检索到可引用的条款）";
        } catch (Exception e) {
            log.error("单图 AgenticRAG 执行失败: {}", e.getMessage(), e);
            throw new RuntimeException("AgenticRAG 执行失败: " + e.getMessage(), e);
        }
    }
}
