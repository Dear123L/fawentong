package com.example.agent.multi;

import com.example.service.SessionMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 答案拼装器：从已落库的状态字段拼出最终答复，**不调用任何 LLM**。
 *
 * <p>原为 {@code AnswerAgentNode}（图中的一个节点）。现已降级为纯函数式组件，
 * 由 {@link CriticNode} 在评审前调用——这样做的原因：
 * <ul>
 *   <li><b>职责内聚</b>：拼装是纯确定性字符串操作，评审是 LLM 判断 + 路由决策，
 *       两者性质不同，合并成一个智能体会让节点承担三件事（拼装/检查/决策）。</li>
 *   <li><b>保持评审独立性</b>：Critic 检查的是"外部传入的答案"。若由 Critic 自己先拼装再评审，
 *       等于让 LLM 评价自己刚写的内容（self-approval bias），评审质量会下降。</li>
 *   <li><b>可单测</b>：本类是纯函数式组件（仅依赖 SessionMemoryService 读上轮条款），
 *       不涉及 LLM，测试无需 mock 模型调用。</li>
 * </ul>
 *
 * <p>拼装顺序：①上轮条款锚定（多轮追问保上下文）→ ②检索成文答案（内层 AgenticRAG 的 ragAnswer）
 * → ③引用依据（clauseId + content）→ ④计算结论（computedPenalty + extractedParams）
 * → ⑤长期画像软注入。全部来自已落库的状态字段，结果可复现、可审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerComposer {

    private final SessionMemoryService sessionMemoryService;

    /**
     * 拼装最终答复。
     *
     * @param state 图执行到评审阶段的状态
     * @return 成文答案；拒答题原样沿用 ScopeCheck 写入的文案
     */
    public String compose(MultiAgentState state) {
        try {
            if (state.isRejected()) {
                // 拒答题：ScopeCheck 已写入拒答文案，原样沿用
                String oos = state.getAnswer();
                return (oos != null && !oos.isBlank()) ? oos : "（超出知识库范围，无法作答）";
            }

            StringBuilder sb = new StringBuilder();

            // ① 上轮条款锚定（多轮追问保上下文）
            List<String> prev = sessionMemoryService.getRetrievedDocs(state.getSessionId());
            if (prev != null && !prev.isEmpty()) {
                sb.append("（延续上轮引用条款：").append(String.join("、", prev)).append("）\n\n");
            }

            // ② 检索成文答案（来自内层 AgenticRAG 生成节点的 ragAnswer，已是 LLM 成文答案）
            String prose = lastHandoff(state, "retriever::");
            if (prose != null && !prose.isBlank()) {
                sb.append(prose).append("\n\n");
            }

            // ③ 引用依据（clauseId + content）
            List<Map<String, Object>> docs = state.getRetrievedDocs();
            if (docs != null && !docs.isEmpty()) {
                sb.append("【引用依据】\n");
                for (Map<String, Object> d : docs) {
                    Object cid = d.get("clauseId");
                    Object content = d.get("content");
                    String c = content != null ? content.toString() : "";
                    if (cid != null) {
                        sb.append("· ").append(cid).append("：").append(c).append("\n");
                    }
                }
                sb.append("\n");
            }

            // ④ 计算结论（computedPenalty + extractedParams）
            Double penalty = state.getComputedPenalty();
            if (penalty != null) {
                Map<String, Object> ext = state.getExtractedParams();
                Object principal = ext != null ? ext.get("principal") : null;
                Object rate = ext != null ? ext.get("daily_rate_per_mille") : null;
                Object days = ext != null ? ext.get("days") : null;
                sb.append(String.format("【计算结论】本金 %s 元，日利率 %s‰，%s 天，金额 = %.2f 元。\n",
                        principal, rate, days, penalty));
            }

            // ⑤ 长期记忆软注入：按相关度召回的用户画像作语境附注（只读增强，绝不覆盖短期 state）
            String profile = state.getUserProfile();
            if (profile != null && !profile.isBlank()) {
                sb.append("\n【结合您的长期画像】").append(profile).append("\n");
            }

            String answer = sb.toString().trim();
            return answer.isEmpty() ? "（未能生成答复）" : answer;
        } catch (Exception e) {
            log.error("答案拼装异常", e);
            return "（综合失败）";
        }
    }

    /**
     * 取 handoffs 中指定前缀的最后一条内容。
     * 重检索会追加新条目，故取最近一次而非首次。
     */
    private static String lastHandoff(MultiAgentState state, String prefix) {
        String found = null;
        for (String h : state.getHandoffs()) {
            if (h.startsWith(prefix)) {
                found = h.substring(prefix.length());
            }
        }
        return found;
    }
}
