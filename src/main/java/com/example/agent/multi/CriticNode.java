package com.example.agent.multi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 评审智能体（Critic）：对最终答案做充分性 / 引用完整性自检（D）。
 * 若判定不足且未达最大重试次数，则置 needsMore=true，由图路由回退 retriever 重新检索（B 重规划）。
 * 对齐"supervisor + critic"多智能体范式，让调度具备反思与再分派能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticNode {

    private final ToolCallingLlm toolCallingLlm;

    /** 重规划最大重试次数（含首次后的回退次数） */
    private static final int MAX_RETRY = 1;

    public CompletableFuture<Map<String, Object>> execute(MultiAgentState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            try {
                String answer = state.getAnswer();
                boolean heuristicBad = isHeuristicInsufficient(answer);
                boolean llmBad = llmSaysInsufficient(answer, state.getQuestion());

                boolean insufficient = heuristicBad || llmBad;
                int retry = state.getRetryCount();
                boolean needsMore = insufficient && retry < MAX_RETRY;

                updates.put("needsMore", needsMore);
                updates.put("retryCount", retry + (needsMore ? 1 : 0));

                log.info("Critic 评审: heuristicBad={}, llmBad={}, needsMore={}, retry={}",
                        heuristicBad, llmBad, needsMore, retry);
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
