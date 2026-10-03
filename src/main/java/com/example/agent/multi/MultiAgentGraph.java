package com.example.agent.multi;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 多智能体编排图
 *
 * 节点：coordinator（调度） -> retriever（检索）/ calculator（计算） -> answer（综合） -> critic（评审）
 * 条件路由：
 *   _routeCoord：calculate -> calculator，其余 -> retriever
 *   _routeAfterRetriever：both -> calculator，其余 -> answer
 *   _routeAfterCritic：needsMore -> retriever（重规划重检索），否则 -> END
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiAgentGraph {

    private final ScopeCheckNode scopeCheckNode;
    private final RetrieverAgentNode retrieverAgentNode;
    private final AnswerAgentNode answerAgentNode;
    private final CriticNode criticNode;

    private CompiledGraph<MultiAgentState> compiledGraph;

    @PostConstruct
    public void init() throws Exception {
        buildGraph();
    }

    private void buildGraph() throws Exception {
        StateGraph<MultiAgentState> graph = new StateGraph<>(MultiAgentState::new);

        graph.addNode("scopeCheck", scopeCheckNode::execute);
        graph.addNode("retriever", retrieverAgentNode::execute);
        graph.addNode("answer", answerAgentNode::execute);
        graph.addNode("critic", criticNode::execute);

        // 范围判定作为最前置关卡：out_of_scope 直接短路到 END（拒答，省去检索/生成）；否则进入正常链路。
        // scopeCheck 同时完成意图识别并写入 intent，故这里直接路由到 retriever。
        graph.addEdge(START, "scopeCheck");
        graph.addConditionalEdges("scopeCheck",
                (state) -> CompletableFuture.supplyAsync(() -> {
                    if (Boolean.TRUE.equals(state.isRejected())) {
                        log.info("范围判定路由 -> END（out_of_scope 拒答）");
                        return "end";
                    }
                    log.info("范围判定路由 -> retriever");
                    return "retriever";
                }),
                Map.of("retriever", "retriever", "end", END)
        );

        // 计算由 Retriever 内部承担：依据 intent(both/calculate) 决定是否触发（门控）。
        // 入口意图由 ScopeCheck 在 scope 判定后写入 intent。
        // 检索（含可能的计算）完成后直接进入综合。
        graph.addEdge("retriever", "answer");
        // 综合后进入评审
        graph.addEdge("answer", "critic");

        // 评审后路由：判定不足且未达上限 -> 回退重检索（重规划）；否则结束
        graph.addConditionalEdges("critic",
                (state) -> CompletableFuture.supplyAsync(() -> {
                    if (Boolean.TRUE.equals(state.getNeedsMore())) {
                        log.info("评审路由 -> retriever（重规划重检索）");
                        return "retriever";
                    }
                    log.info("评审路由 -> END");
                    return "end";
                }),
                Map.of("retriever", "retriever", "end", END)
        );

        compiledGraph = graph.compile();
        log.info("Multi-Agent RAG 图编译完成（含 Critic 重规划）");
    }

    public MultiAgentState execute(Map<String, Object> input) throws Exception {
        log.info("开始执行 Multi-Agent RAG");
        Optional<MultiAgentState> result = compiledGraph.invoke(input);
        return result.orElseThrow(() -> new RuntimeException("多智能体执行失败"));
    }
}
