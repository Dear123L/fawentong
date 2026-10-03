package com.example.agent.multi;

import com.example.service.SessionMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 综合智能体（Answer）：确定性模板拼接，不调用 LLM 综合。
 *
 * 拼装顺序：①上轮条款锚定（多轮追问保上下文）→ ②检索成文答案（内层 AgenticRAG 生成节点已有的 ragAnswer）
 * → ③引用依据（clauseId + content）→ ④计算结论（computedPenalty + extractedParams）。
 * 全部来自已落库的状态字段，零额外 LLM 调用，结果可复现、可审计。
 *
 * 多轮追问通过注入上轮 clauseId 锚定文本保住上下文，避免被无关重检索带偏（原 LLM 综合的跨轮锚定能力以低成本近似替代）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerAgentNode {

    private final SessionMemoryService sessionMemoryService;

    public CompletableFuture<Map<String, Object>> execute(MultiAgentState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            try {
                if (state.isRejected()) {
                    // 拒答题：ScopeCheck 已写入拒答文案，原样沿用
                    String oos = state.getAnswer();
                    updates.put("answer", oos != null && !oos.isBlank() ? oos : "（超出知识库范围，无法作答）");
                    return updates;
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

                // ⑤ 长期记忆软注入：把按相关度召回的用户画像作为语境附注（只读增强，绝不覆盖短期 state）
                String profile = state.getUserProfile();
                if (profile != null && !profile.isBlank()) {
                    sb.append("\n【结合您的长期画像】").append(profile).append("\n");
                }

                String answer = sb.toString().trim();
                if (answer.isEmpty()) {
                    answer = "（未能生成答复）";
                }
                updates.put("answer", answer);
                updates.put("handoffs", state.appendHandoff("answer", answer));
                return updates;
            } catch (Exception e) {
                log.error("综合智能体异常", e);
                updates.put("answer", "（综合失败）");
                return updates;
            }
        });
    }

    /** 取 handoffs 中指定前缀的最后一条内容（重检索后会追加新条目，取最近一次）。 */
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
