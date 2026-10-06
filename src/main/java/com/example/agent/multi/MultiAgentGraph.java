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
 * 节点：scopeCheck（范围判定+意图路由） -> { retriever（检索+计算） | clauseReviewer（合同审查） } -> critic（拼装+评审）
 * 条件路由：
 *   scopeCheck：out_of_scope -> END（拒答短路）；intent=review -> clauseReviewer；否则 -> retriever
 *   critic：needsMore -> retriever（重规划重检索），否则 -> END；审查分支 needsMore 恒为 false
 *
 * <p>原为 4 节点（answer 为独立的综合节点）。答案拼装已降级为纯函数组件
 * {@link AnswerComposer} 并入 {@link CriticNode}，图拓扑变为 3 主线 + 1 审查分支。
 * 改动理由与影响见 {@code 结构精简评估.md}。</p>
 *
 * <p>审查分支（P2-P3 新增）：ScopeCheck 命中审查锚词 -> intent="review" -> clauseReviewer
 * 调 {@link ClauseReviewEngine} 跑完整审查 -> 报告存 state.rewriteSuggestions -> critic 拼装。
 * 审查逻辑只存在于 ClauseReviewEngine 一处，Agent 外的旧审查模块不动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiAgentGraph {

    private final ScopeCheckNode scopeCheckNode;
    private final RetrieverAgentNode retrieverAgentNode;
    private final ClauseReviewerNode clauseReviewerNode;
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
        graph.addNode("clauseReviewer", clauseReviewerNode::execute);
        graph.addNode("critic", criticNode::execute);

        // 范围判定作为最前置关卡：out_of_scope 直接短路到 END（拒答，省去检索/生成）；否则进入正常链路。
        // scopeCheck 同时完成意图识别并写入 intent：review 意图路由到 clauseReviewer，其余路由到 retriever。
        graph.addEdge(START, "scopeCheck");
        graph.addConditionalEdges("scopeCheck",
                (state) -> CompletableFuture.supplyAsync(() -> {
                    if (Boolean.TRUE.equals(state.isRejected())) {
                        log.info("范围判定路由 -> END（out_of_scope 拒答）");
                        return "end";
                    }
                    if ("review".equals(state.getIntent())) {
                        log.info("范围判定路由 -> clauseReviewer（审查意图）");
                        return "clauseReviewer";
                    }
                    log.info("范围判定路由 -> retriever");
                    return "retriever";
                }),
                Map.of("retriever", "retriever", "clauseReviewer", "clauseReviewer", "end", END)
        );

        // 计算由 Retriever 内部承担：依据 intent(both/calculate) 决定是否触发（门控）。
        // 入口意图由 ScopeCheck 在 scope 判定后写入 intent。
        // 检索（含可能的计算）完成后直接进入评审——答案拼装已并入 CriticNode（见 AnswerComposer）。
        graph.addEdge("retriever", "critic");

        // 审查分支：ClauseReviewer 产出审查报告后进入评审；审查不需要回退重检索（needsMore 强制 false）。
        graph.addEdge("clauseReviewer", "critic");

        // 评审后路由：判定不足且未达上限 -> 回退重规划；否则结束。
        // 回退目标按意图选择：审查系意图回到 ClauseReviewer 重审，其余回 Retriever 重检索。
        // 当前 Critic 对审查意图强制 needsMore=false，此分支是为意图扩展预留的——
        // 若将来出现需要重审的审查子任务（如"只审第五条"），无需再改路由。
        graph.addConditionalEdges("critic",
                (state) -> CompletableFuture.supplyAsync(() -> {
                    if (Boolean.TRUE.equals(state.getNeedsMore())) {
                        String intent = state.getIntent();
                        if ("review".equals(intent)) {
                            log.info("评审路由 -> clauseReviewer（审查意图，重审）");
                            return "clauseReviewer";
                        }
                        log.info("评审路由 -> retriever（重规划重检索）");
                        return "retriever";
                    }
                    log.info("评审路由 -> END");
                    return "end";
                }),
                Map.of("retriever", "retriever", "clauseReviewer", "clauseReviewer", "end", END)
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
