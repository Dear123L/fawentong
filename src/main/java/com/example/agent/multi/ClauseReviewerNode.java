package com.example.agent.multi;

import com.example.calc.LegalComputeService;
import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import com.example.service.SessionMemoryService;
import com.example.util.ContractParamParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 审查智能体（ClauseReviewer）：Agent 图内的合同审查入口节点。
 *
 * <p>职责单一：把用户问题（即粘贴/上传的合同文本）交给 {@link ClauseReviewEngine}
 * 跑完整审查流程，并把纯文本审查报告写入 state 的 {@code rewriteSuggestions} 键
 * （供 P4 的 AnswerComposer 在审查分支拼装最终答案）。
 * 审查计算逻辑只存在于 ClauseReviewEngine 一处，本节点不做任何数值/检索/LLM 判定。</p>
 *
 * <p>路由：ScopeCheck 命中审查锚词后，intent="review"，图从 scopeCheck 直接路由到本节点，
 * 再进 critic。审查分支不需要回退重检索，故本节点显式置 needsMore=false。</p>
 *
 * <p>边界：仅服务 Agent 内审查，不动 Agent 外的 ContractReviewController /
 * ContractReviewServiceImpl / AiAnalysisService。</p>
 */
@Slf4j
@Component
public class ClauseReviewerNode {

    /** 单轮最多锚定的风险条款数，避免长合同把 prompt 撑满。 */
    private static final int MAX_REVIEW_ANCHORS = 5;

    private final ClauseReviewEngine clauseReviewEngine;
    private final SessionMemoryService sessionMemoryService;

    public ClauseReviewerNode(ClauseReviewEngine clauseReviewEngine,
                              SessionMemoryService sessionMemoryService) {
        this.clauseReviewEngine = clauseReviewEngine;
        this.sessionMemoryService = sessionMemoryService;
    }

    public CompletableFuture<Map<String, Object>> execute(MultiAgentState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            try {
                // 审查场景：用户问题即合同文本（粘贴或上传后填回 question 字段）
                String contractText = state.getQuestion();
                Long kbId = state.getKbId();
                String sourceLabel = "chat_" + state.getSessionId();

                ClauseReviewEngine.ReviewReport report =
                        clauseReviewEngine.review(contractText, kbId, sourceLabel);

                // 审查报告以纯文本存入 state，供 P4 AnswerComposer 审查分支拼装。
                // 同时直接落入 answer 兜底，避免 P4 接线前审查结果丢失（P4 会统一由 Critic 拼装覆盖）。
                String reportText = report.toPlainText();
                updates.put("rewriteSuggestions", reportText);
                updates.put("answer", reportText);
                // 跨轮锚定：把本轮审查用到的法规 clauseId 持久化，供追问轮锚定上轮依据。
                // 与问答路径同格式（ES clauseId 列表），复用同一份记忆键。
                persistReviewAnchors(state.getSessionId(), report, updates);

                // 审查分支不回退重检索（P4 还会在 Critic 处做意图感知强制 false，双保险）
                updates.put("needsMore", false);
                log.info("ClauseReviewer 审查完成：共 {} 条，风险 {} 条",
                        report.getReviewedCount(), report.getRiskCount());
                return updates;
            } catch (Exception e) {
                log.error("ClauseReviewer 执行异常", e);
                updates.put("rewriteSuggestions", "（审查执行异常，请重试或人工复核）");
                updates.put("answer", "（审查执行异常，请重试或人工复核）");
                updates.put("needsMore", false);
                return updates;
            }
        });
    }

    /**
     * 把审查结论写入会话历史，供追问轮获得条款上下文。
     *
     * <p>写入 {@code history}（而非 {@code retrievedDocs}）有两个原因：</p>
     * <ul>
     *   <li>审查与问答共用history，追问轮无论走哪条分支都能读到；</li>
     *   <li>{@code retrievedDocs} 是滚动归档的，后到的问答检索会覆盖它——
     *       实测追问轮走问答路径后，审查写入的条款被60 条检索结果冲掉。</li>
     * </ul>
     * <p>只写入被判定有风险的条款原文（最多 {@value #MAX_REVIEW_ANCHORS} 条）：
     * 追问通常是「某条为什么有问题」，需要条款原文才能作答；
     * 无风险条款不写——不会有人追问「那条为什么没问题」。</p>
     */
    private void persistReviewAnchors(String sessionId,
                                      ClauseReviewEngine.ReviewReport report,
                                      Map<String, Object> updates) {
        if (sessionId == null || report == null || report.items == null) {
            return;
        }
        List<String> anchors = new ArrayList<>();
        for (ClauseReviewEngine.ClauseReviewItem item : report.items) {
            if (!item.risky || item.clauseText == null || item.clauseText.isBlank()) {
                continue;
            }
            String text = item.clauseText.trim();
            if (!anchors.contains(text)) {
                anchors.add(text);
            }
            if (anchors.size() >= MAX_REVIEW_ANCHORS) {
                break;
            }
        }
        if (anchors.isEmpty()) {
            return;
        }
        // 落到 state 的历史文本，编排层回写记忆时会随本轮一起持久化
        String NL = "\n";
        StringBuilder sb = new StringBuilder();
        for (String a : anchors) {
            sb.append("【已审查条款】").append(a).append(NL);
        }
        String note = sb.toString();
        sessionMemoryService.appendMessage(sessionId, "assistant", "（审查结论摘要）" + NL + note);
        updates.put("reviewAnchors", note);
        log.info("ClauseReviewer 锚定 {} 条风险条款原文供跨轮追问", anchors.size());
    }
}
