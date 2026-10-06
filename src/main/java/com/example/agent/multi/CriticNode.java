package com.example.agent.multi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 评审智能体（Critic）：拼装最终答案 + 对其做充分性 / 引用完整性自检（D）。
 *
 * <p>两段职责：
 * <ol>
 *   <li><b>拼装</b>——委托 {@link AnswerComposer}（纯函数组件，无 LLM 调用）。
 *       原为独立的 {@code AnswerAgentNode} 节点，现降级并入本节点，
 *       目的是去掉一个只做字符串拼接的图节点，同时保持图拓扑扁平。</li>
 *   <li><b>评审与决策</b>——若判定不足且未达最大重试次数，则置 needsMore=true，
 *       由图路由回退 retriever 重新检索（B 重规划）。</li>
 * </ol>
 *
 * <p>为何不把拼装完全内联到本类：拼装是纯确定性字符串操作，评审是 LLM 判断。
 * 分成两个类后，{@link AnswerComposer} 可独立单测（无需 mock 模型调用），
 * 且 Critic 评的是"外部传入的答案"而非自己刚拼的内容。
 *
 * <p>对齐"supervisor + critic"多智能体范式，让调度具备反思与再分派能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticNode {

    private final ToolCallingLlm toolCallingLlm;
    private final AnswerComposer answerComposer;

    /** 重规划最大重试次数（含首次后的回退次数） */
    private static final int MAX_RETRY = 1;

    public CompletableFuture<Map<String, Object>> execute(MultiAgentState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            try {
                // 评审前先拼装答案（原 AnswerAgentNode 的职责，已降级为纯函数组件 AnswerComposer）。
                // 检查逻辑与降级前完全一致：拼装只是把"谁生成"从节点挪到了这里。
                String answer = answerComposer.compose(state);
                updates.put("answer", answer);

                // P4: 审查意图感知——审查报告已定稿（ClauseReviewer 产出 + AnswerComposer 审查分支拼装），
                // 不需要回退重检索，needsMore 强制 false；并跳过 LLM 评审省一次调用。
                // （ClauseReviewer 也已置 needsMore=false，双保险。）
                String intent = state.getIntent();
                boolean reviewIntent = "review".equals(intent);

                boolean needsMore;
                if (reviewIntent) {
                    needsMore = false;
                } else {
                    boolean heuristicBad = isHeuristicInsufficient(answer);
                    boolean llmBad = llmSaysInsufficient(answer, state.getQuestion());

                    boolean insufficient = heuristicBad || llmBad;
                    int retry = state.getRetryCount();
                    needsMore = insufficient && retry < MAX_RETRY;
                    updates.put("retryCount", retry + (needsMore ? 1 : 0));
                }

                updates.put("needsMore", needsMore);

                log.info("Critic 评审: intent={}, needsMore={}", intent, needsMore);
                return updates;
            } catch (Exception e) {
                log.error("Critic 评审异常", e);
                updates.put("needsMore", false);
                updates.put("retryCount", state.getRetryCount());
                return updates;
            }
        });
    }

    /** 确定性兜底：空白或明显的失败标记直接判为不足 */
    private boolean isHeuristicInsufficient(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        return answer.contains("（检索失败）")
                || answer.contains("（综合失败）")
                || answer.contains("（计算失败）");
    }

    /** 让 LLM 评审：是否充分、有据可查、直接回答了问题 */
    private boolean llmSaysInsufficient(String answer, String question) {
        try {
            String prompt = "你是对法律问答质量的评审专家。\n"
                    + "用户问题：" + question + "\n\n"
                    + "候选答案：\n" + answer + "\n\n"
                    + "该答案是否充分、有据可查、且直接回答了用户问题？"
                    + "只回答 YES 或 NO，并附一句理由。";
            String resp = toolCallingLlm.chat(prompt);
            if (resp == null) {
                return false;
            }
            String up = resp.trim().toUpperCase();
            return up.startsWith("NO") || up.contains("不足") || up.contains("不充分");
        } catch (Exception e) {
            return false;
        }
    }
}
