package com.example.agent.graph;

import com.example.agent.node.GenerateNode;
import com.example.agent.node.GradeNode;
import com.example.agent.node.RetrieveNode;
import com.example.agent.node.RewriteNode;
import com.example.agent.state.AgenticState;
import com.example.agent.state.AgenticStateFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 单图 AgenticRAG 工作流：retrieve → grade →（全灭时 rewrite 后重检一次）→ generate。
 *
 * <p>内层闭环与外层多智能体编排的分工：外层 {@code RetrieverAgentNode} 复用本图
 * 作为「检索 + 成文」能力，本图不感知 userId / 会话 / 范围判定。
 *
 * <p>改写重检最多一次（有界）：评分门全灭说明原查询召回不到可用片段，
 * 改写查询再试一轮，仍失败则由生成节点拒答，避免无限回环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgenticRagGraph {

    /** 改写重试上限：评分门全灭时最多改写 2 次（即最多 3 轮检索） */
    private static final int MAX_REWRITE_RETRY = 2;

    private final RetrieveNode retrieveNode;
    private final GradeNode gradeNode;
    private final RewriteNode rewriteNode;
    private final GenerateNode generateNode;

    private CompiledGraph<AgenticState> compiledGraph;

    @PostConstruct
    public void init() throws Exception {
        buildGraph();
    }

    private void buildGraph() throws Exception {
        StateGraph<AgenticState> graph = new StateGraph<>(new AgenticStateFactory());

        graph.addNode("retrieve", retrieveNode::execute);
        graph.addNode("grade", gradeNode::execute);
        graph.addNode("rewrite", rewriteNode::execute);
        graph.addNode("generate", generateNode::execute);

        graph.addEdge(START, "retrieve");
        graph.addEdge("retrieve", "grade");

        // 评分门：有片段 -> generate；全灭且未达重试上限 -> rewrite；否则 -> generate（由其拒答）
        graph.addConditionalEdges("grade",
                (state) -> CompletableFuture.supplyAsync(() -> {
                    if (state.isRelevant()) {
                        log.info("评分门 -> generate（过门 {} 条）", state.getGradedDocs().size());
                        return "generate";
                    }
                    if (state.getRetryCount() < MAX_REWRITE_RETRY) {
                        log.info("评分门全灭 -> rewrite（第 {} 次改写）", state.getRetryCount() + 1);
                        return "rewrite";
                    }
                    log.info("评分门全灭且已达重试上限 {} -> generate（将拒答）", MAX_REWRITE_RETRY);
                    return "generate";
                }),
                Map.of("generate", "generate", "rewrite", "rewrite")
        );

        // 改写后重检（硬编码 rewrite -> retrieve，评分门全灭时可再次回到 rewrite，直至上限）
        graph.addEdge("rewrite", "retrieve");
        graph.addEdge("generate", END);

        compiledGraph = graph.compile();
        log.info("单图 AgenticRAG 图编译完成（retrieve→grade→(rewrite)→generate）");
    }

    /**
     * 执行单图工作流。
     *
     * @param input 至少包含 question（或 originalQuestion/currentQuery），可选 kbId
     * @return 终态（含 finalAnswer / retrievedDocs）
     */
    public AgenticState execute(Map<String, Object> input) throws Exception {
        log.info("开始执行单图 AgenticRAG");
        Optional<AgenticState> result = compiledGraph.invoke(input);
        return result.orElseThrow(() -> new RuntimeException("单图 AgenticRAG 执行失败"));
    }

    /** 暴露已编译图，便于调试与评测直连。 */
    public CompiledGraph<AgenticState> getCompiledGraph() {
        return compiledGraph;
    }

    /** 供上层复用：把列表字段安全取出。 */
    static List<Map<String, Object>> docsOf(AgenticState state) {
        return state.getRetrievedDocs();
    }
}
