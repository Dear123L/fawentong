package com.example.agent.multi;

import com.example.calc.CalcType;
import com.example.service.MemoryMessage;
import com.example.service.SessionMemoryService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 单测：验证 AnswerComposer 在审查意图（rewriteSuggestions 非空）下，
 * 直接把 ClauseReviewer 产出的纯文本审查报告作为最终答复拼装返回。
 *
 * <p>审查分支在 compose 中先于任何 sessionMemoryService 调用即返回，
 * 故 NoopMemory 桩的方法均不会被触发，可全空实现。</p>
 */
class AnswerComposerReviewTest {

    /** 空实现：审查分支不依赖其任何方法。 */
    static class NoopMemory implements SessionMemoryService {
        @Override public List<MemoryMessage> getHistory(String s) { return List.of(); }
        @Override public void appendMessage(String s, String r, String c) {}
        @Override public void appendRound(String s, String q, String a) {}
        @Override public String formatHistory(String s) { return ""; }
        @Override public void saveCalcParams(String s, Map<String, Object> p) {}
        @Override public Map<String, Object> getCalcParams(String s) { return Map.of(); }
        @Override public void saveCalcType(String s, CalcType t) {}
        @Override public CalcType getCalcType(String s) { return null; }
        @Override public void saveRetrievedDocs(String s, List<String> ids) {}
        @Override public List<String> getRetrievedDocs(String s) { return List.of(); }
        @Override public String getSummary(String s) { return null; }
    }

    @Test
    void reviewIntent_assemblesReviewReport() {
        // 注意：AgentState.data() 是不可变视图，setter 内部走 data().put 会抛
        // UnsupportedOperationException（LangGraph4j 已知坑）。单测构造带初始值的状态
        // 必须走构造器 init map，不能用 setter——与 MultiAgentGraphRoutingTest 同一写法。
        Map<String, Object> init = new HashMap<>();
        init.put("question", "请审查这份买卖合同");
        init.put("intent", "review");
        init.put("rewriteSuggestions",
                "【合同审查报告】\n"
                        + "共审查 3 条，发现风险 1 条。\n"
                        + "第3条：违约金日万分之五（合法），无风险。\n"
                        + "第5条：逾期利息约定过高（日1%），建议修改为不超过年24%。");
        MultiAgentState state = new MultiAgentState(init);

        AnswerComposer composer = new AnswerComposer(new NoopMemory());
        String ans = composer.compose(state);

        assertTrue(ans.contains("合同审查报告"), "审查报告应作为最终答复返回");
        assertTrue(ans.contains("第5条"), "应保留条款级审查明细");
        assertTrue(ans.contains("建议修改"), "应保留改写建议");
    }

    @Test
    void nonReview_intentSkipsReviewReport() {
        // 无审查报告 → 走普通 QA 拼装路径（rewriteSuggestions 缺省为空）
        Map<String, Object> init = new HashMap<>();
        init.put("question", "买卖合同违约金怎么算");
        init.put("intent", "retrieve");
        MultiAgentState state = new MultiAgentState(init);

        AnswerComposer composer = new AnswerComposer(new NoopMemory());
        String ans = composer.compose(state);

        assertFalse(ans.contains("合同审查报告"), "无 rewriteSuggestions 时不应走审查分支");
    }
}
