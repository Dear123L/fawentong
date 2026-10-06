package com.example.agent.multi;

import com.example.calc.LegalComputeService;
import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import com.example.service.RagVectorService;
import com.example.util.ContractParamParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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

    private final ClauseReviewEngine clauseReviewEngine;

    public ClauseReviewerNode(ClauseReviewEngine clauseReviewEngine) {
        this.clauseReviewEngine = clauseReviewEngine;
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
}
