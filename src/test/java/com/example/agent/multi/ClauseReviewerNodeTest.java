package com.example.agent.multi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClauseReviewerNode 针对性单测（P3）。桩掉 ClauseReviewEngine，验证节点把报告写入
 * state 的 rewriteSuggestions 键，且审查分支 needsMore 强制 false。
 */
class ClauseReviewerNodeTest {

    /** 记录 saveRetrievedDocs 调用，便于断言跨轮锚定是否写入。 */
    static class RecordingMemory implements com.example.service.SessionMemoryService {
        String lastSessionId;
        List<String> lastClauseIds;

        @Override
        public void saveRetrievedDocs(String sessionId, List<String> clauseIds) {
            this.lastSessionId = sessionId;
            this.lastClauseIds = clauseIds;
        }
        @Override public List<String> getRetrievedDocs(String s) { return lastClauseIds; }
        String lastAppend;
        @Override public void appendMessage(String s, String r, String c) { this.lastAppend = c; }
        @Override public void appendRound(String s, String q, String a) {}
        @Override public String formatHistory(String s) { return ""; }
        @Override public List<com.example.service.MemoryMessage> getHistory(String s) { return List.of(); }
        @Override public void saveCalcParams(String s, java.util.Map<String, Object> p) {}
        @Override public java.util.Map<String, Object> getCalcParams(String s) { return null; }
        @Override public void saveCalcType(String s, com.example.calc.CalcType t) {}
        @Override public com.example.calc.CalcType getCalcType(String s) { return null; }
        @Override public String getSummary(String s) { return null; }
    }

    private static final RecordingMemory stubMemory = new RecordingMemory();

    @Test
    void storesReportAndForcesNeedsMoreFalse() throws Exception {
        // 桩引擎：直接返回空报告，避免依赖 ES / 真实 LLM
        ClauseReviewEngine stubEngine = new ClauseReviewEngine(null, null, null, null, null) {
            @Override
            public ReviewReport review(String contractText, Long kbId, String sourceLabel) {
                return new ReviewReport(List.of());
            }
        };
        ClauseReviewerNode node = new ClauseReviewerNode(stubEngine, stubMemory);

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

    /** 被判定有风险的条款原文应写入 retrievedDocs，供追问轮锚定。 */
    @Test
    void persistsRiskyClauseText_forCrossTurnAnchoring() throws Exception {
        // 桩引擎：一条有风险、一条无风险
        java.util.Map<String, Object> hit = new java.util.HashMap<>();
        hit.put("clauseId", "民法典-第585条");
        ClauseReviewEngine.ClauseReviewItem risky = new ClauseReviewEngine.ClauseReviewItem(
                "第三条", "第三条 违约方应按合同总价的百分之五十支付违约金。",
                List.of(hit), null, true, true, false,
                "违约金比例畸高：约定为50%，超过30%上限", "建议降至20%", null);
        ClauseReviewEngine.ClauseReviewItem safe = new ClauseReviewEngine.ClauseReviewItem(
                "第四条", "第四条 双方发生争议可向有管辖权的人民法院起诉。",
                List.of(), null, false, false, false, null, null, null);
        ClauseReviewEngine engine = new ClauseReviewEngine(null, null, null, null, null) {
            @Override
            public ReviewReport review(String contractText, Long kbId, String sourceLabel) {
                return new ReviewReport(List.of(risky, safe));
            }
        };
        RecordingMemory mem = new RecordingMemory();
        ClauseReviewerNode node = new ClauseReviewerNode(engine, mem);

        Map<String, Object> init = new java.util.HashMap<>();
        init.put("question", "审查这份合同。");
        init.put("kbId", 1L);
        init.put("sessionId", "sessAnchor");
        MultiAgentState st = new MultiAgentState(init);

        node.execute(st).join();

        assertNotNull(mem.lastAppend, "应把风险条款原文写入会话历史");
        assertTrue(mem.lastAppend.contains("百分之五十"),
                "锚定内容应为条款原文，实际=" + mem.lastAppend);
        assertFalse(mem.lastAppend.contains("人民法院"),
                "无风险条款不应锚定，实际=" + mem.lastAppend);
    }
}
