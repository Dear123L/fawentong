package com.example.agent.multi;

import com.example.agent.graph.AgenticRagGraph;
import com.example.calc.LegalComputeService;
import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import com.example.service.RagVectorService;
import com.example.service.SessionMemoryService;
import com.example.service.UserMemoryService;
import com.example.util.ContractParamParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MultiAgentGraph 路由单测（P3）。
 *
 * <p>用桩节点替掉真实 Retriever/Critic/ScopeCheck，验证图的条件路由：
 *  - intent=review → 走 clauseReviewer（不误入 retriever），并经 critic 到 END；
 *  - intent=retrieve → 走 retriever（不被 review 分支劫持）。</p>
 */
class MultiAgentGraphRoutingTest {

    /** 范围判定桩：原样放行（不拒答、不改 intent）。 */
    static class StubScope extends ScopeCheckNode {
        @Override
        public CompletableFuture<Map<String, Object>> execute(MultiAgentState s) {
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    /** 检索桩：若被执行，写入 retrieverRan 标记。 */
    static class StubRetriever extends RetrieverAgentNode {
        StubRetriever() {
            super((AgenticRagGraph) null, null, (SessionMemoryService) null,
                    (LegalComputeService) null, (UserMemoryService) null);
        }
        @Override
        public CompletableFuture<Map<String, Object>> execute(MultiAgentState s) {
            return CompletableFuture.completedFuture(Map.of("retrieverRan", true));
        }
    }

    /** 评审桩：若被执行，写入 criticRan 标记。 */
    static class StubCritic extends CriticNode {
        StubCritic() {
            super(null, null);
        }
        @Override
        public CompletableFuture<Map<String, Object>> execute(MultiAgentState s) {
            return CompletableFuture.completedFuture(Map.of("criticRan", true));
        }
    }

    /** 审查引擎桩：返回空报告，仅用于验证图路由。 */
    static ClauseReviewEngine stubEngine() {
        return new ClauseReviewEngine(null, null, null, null, null) {
            @Override
            public ClauseReviewEngine.ReviewReport review(String t, Long kb, String label) {
                return new ClauseReviewEngine.ReviewReport(List.of());
            }
        };
    }

    private MultiAgentGraph buildGraph() throws Exception {
        MultiAgentGraph g = new MultiAgentGraph(
                new StubScope(), new StubRetriever(), new ClauseReviewerNode(stubEngine(), null), new StubCritic());
        g.init();
        return g;
    }

    @Test
    void reviewIntent_routesToClauseReviewer_notRetriever() throws Exception {
        MultiAgentGraph g = buildGraph();

        Map<String, Object> input = new HashMap<>();
        input.put("question", "审查以下合同：第一条 甲方应按约定交货。");
        input.put("intent", "review");
        input.put("rejected", false);
        input.put("kbId", 1L);

        MultiAgentState result = g.execute(input);

        // 审查分支应被执行：报告写入 rewriteSuggestions，且经 critic
        assertNotNull(result.data().get("rewriteSuggestions"), "review 意图应路由到 clauseReviewer 并产出报告");
        assertTrue((Boolean) result.data().get("criticRan"), "审查后应进入 critic");
        // 关键：绝不能误入 retriever
        assertFalse(result.data().containsKey("retrieverRan"), "review 意图不得误路由到 retriever");
    }

    @Test
    void retrieveIntent_stillRoutesToRetriever() throws Exception {
        MultiAgentGraph g = buildGraph();

        Map<String, Object> input = new HashMap<>();
        input.put("question", "买卖合同违约金怎么算");
        input.put("intent", "retrieve");
        input.put("rejected", false);
        input.put("kbId", 1L);

        MultiAgentState result = g.execute(input);

        assertTrue((Boolean) result.data().get("retrieverRan"), "retrieve 意图应路由到 retriever");
        assertTrue((Boolean) result.data().get("criticRan"), "retriever 后应进入 critic");
        assertFalse(result.data().containsKey("rewriteSuggestions"), "retrieve 意图不应触发审查");
    }
}
