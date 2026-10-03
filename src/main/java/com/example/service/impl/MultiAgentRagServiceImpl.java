package com.example.service.impl;

import com.example.agent.multi.MultiAgentGraph;
import com.example.agent.multi.MultiAgentState;
import com.example.service.MultiAgentRagResult;
import com.example.service.MultiAgentRagService;
import com.example.service.SessionMemoryService;
import com.example.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentRagServiceImpl implements MultiAgentRagService {

    private final MultiAgentGraph multiAgentGraph;
    private final SessionMemoryService sessionMemoryService;
    private final UserMemoryService userMemoryService;

    @Override
    public String ask(Long kbId, String sessionId, String question, Long userId) {
        try {
            log.info("多智能体问答开始: kbId={}, sessionId={}, question={}, userId={}", kbId, sessionId, question, userId);

            Map<String, Object> input = new HashMap<>();
            input.put("question", question);
            input.put("sessionId", sessionId);
            input.put("kbId", kbId);
            input.put("userId", userId);
            input.put("intent", "");
            input.put("retryCount", 0);
            input.put("needsMore", false);
            input.put("handoffs", new java.util.ArrayList<String>());
            input.put("answer", "");

            MultiAgentState finalState = multiAgentGraph.execute(input);
            String answer = finalState.getAnswer();

            // 流程结束：把本轮（用户问题 + 最终答案）写入短期记忆，供下一轮多轮交互使用
            sessionMemoryService.appendRound(sessionId, question, answer);
            // 长期记忆：轮末摊销抽取用户信号（异步、容错，不阻塞应答；按会话每 3 轮触发一次 LLM 抽取）
            extractUserMemoryAsync(userId, sessionId, question, answer);
            return answer;

        } catch (Exception e) {
            log.error("多智能体问答失败", e);
            return "抱歉，服务暂时不可用，请稍后重试。";
        }
    }

    @Override
    public MultiAgentRagResult askWithMeta(Long kbId, String sessionId, String question, Long userId) {
        try {
            log.info("多智能体问答(带元数据)开始: kbId={}, sessionId={}, question={}, userId={}", kbId, sessionId, question, userId);

            Map<String, Object> input = new HashMap<>();
            input.put("question", question);
            input.put("sessionId", sessionId);
            input.put("kbId", kbId);
            input.put("userId", userId);
            input.put("intent", "");
            input.put("retryCount", 0);
            input.put("needsMore", false);
            input.put("handoffs", new ArrayList<String>());
            input.put("answer", "");

            MultiAgentState s = multiAgentGraph.execute(input);
            String answer = s.getAnswer();

            // 流程结束：写入短期记忆
            sessionMemoryService.appendRound(sessionId, question, answer);

            // 长期记忆：轮末摊销抽取用户信号（异步、容错，不阻塞应答）
            extractUserMemoryAsync(userId, sessionId, question, answer);

            // 评测元数据：从最终状态抽取 5 个字段，生产 ask 链路完全不变
            Map<String, Object> meta = new HashMap<>();
            meta.put("retrieved", s.getRetrievedDocs());            // RetrieverAgentNode 桥接
            meta.put("extracted", s.getExtractedParams());          // CalculatorAgentNode 桥接
            meta.put("computed_penalty", s.getComputedPenalty());   // CalculatorAgentNode 桥接
            meta.put("rejected", s.isRejected());                   // RetrieverAgentNode 桥接
            meta.put("replanned", s.getRetryCount() > 0);           // CriticNode.retryCount
            return new MultiAgentRagResult(s.getAnswer(), meta);
        } catch (Exception e) {
            log.error("多智能体问答(带元数据)失败", e);
            Map<String, Object> meta = new HashMap<>();
            meta.put("error", e.getMessage());
            return new MultiAgentRagResult("抱歉，服务暂时不可用，请稍后重试。", meta);
        }
    }

    /**
     * 异步触发长期记忆摊销抽取：不阻塞主应答链路。任何异常（LLM 限流/Redis 抖动）在
     * {@link com.example.service.impl.RedisUserMemoryServiceImpl#maybeExtract} 内部已被吞掉，
     * 这里再兜一层，保证绝不拖累问答。
     */
    private void extractUserMemoryAsync(Long userId, String sessionId, String question, String answer) {
        CompletableFuture.runAsync(() -> {
            try {
                userMemoryService.maybeExtract(userId, sessionId, question, answer);
            } catch (Exception e) {
                log.warn("长期记忆摊销抽取异步任务异常（已忽略）: {}", e.getMessage());
            }
        });
    }
}
