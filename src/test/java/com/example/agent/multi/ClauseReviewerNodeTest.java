package com.example.agent.multi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ClauseReviewerNode 针对性单测（P3）。桩掉 ClauseReviewEngine，验证节点把报告写入
 * state 的 rewriteSuggestions 键，且审查分支 needsMore 强制 false。
 */
class ClauseReviewerNodeTest {

    @Test
    void storesReportAndForcesNeedsMoreFalse() throws Exception {
        // 桩引擎：直接返回空报告，避免依赖 ES / 真实 LLM
        ClauseReviewEngine stubEngine = new ClauseReviewEngine(null, null, null, null) {
            @Override
            public ReviewReport review(String contractText, Long kbId, String sourceLabel) {
                return new ReviewReport(List.of());
            }
        };
        ClauseReviewerNode node = new ClauseReviewerNode(stubEngine);

        // 注意：AgentState.data() 是不可变视图，setter 内部走 data().put 会抛
        // UnsupportedOperationException（LangGraph4j 已知坑）。单测构造带初始值的状态
        // 必须走构造器 init map，不能用 setter——与 MultiAgentGraphRoutingTest 同一写法。
        Map<String, Object> init = new java.util.HashMap<>();
        init.put("question", "审查以下合同：第一条 甲方应按约定交付货物。");
        init.put("kbId", 7L);
        init.put("sessionId", "sessA");
        init.put("intent", "review");
        MultiAgentState state = new MultiAgentState(init);

        Map<String, Object> updates = node.execute(state).get();

        assertNotNull(updates.get("rewriteSuggestions"), "审查报告应写入 rewriteSuggestions");
        assertFalse((Boolean) updates.get("needsMore"), "审查分支 needsMore 必须强制 false");
        assertEquals("chat_sessA", "chat_" + state.getSessionId());
    }
}
