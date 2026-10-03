package com.example.agent.node;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.example.agent.state.AgenticState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 查询改写节点：评分门全部未过时，把口语化提问改写成更适合 BM25 命中的法律检索式。
 *
 * <p>最多重试 {@value #MAX_RETRY} 次：每次改写后重新检索并再过一次评分门，
 * 仍全灭则交由生成节点拒答。重试次数以 {@code retryCount} 累加，
 * 由 Graph 的条件边与本节点自身双重兜底，避免无限回环。
 */
@Slf4j
@Component
public class RewriteNode {

    /** 改写重试上限：最多 2 次（即原始查询 + 2 轮改写，共 3 轮检索） */
    private static final int MAX_RETRY = 2;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.api.model:deepseek-v3}")
    private String modelName;

    public CompletableFuture<Map<String, Object>> execute(AgenticState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            // 最多重试 MAX_RETRY 次（由 Graph 的条件边按 retryCount 兜底，此处只负责累加计数）
            if (state.getRetryCount() >= MAX_RETRY) {
                log.info("改写次数已达上限 {}，跳过 RewriteNode", MAX_RETRY);
                return updates;
            }
            String original = state.getOriginalQuestion();
            String rewritten = null;
            try {
                rewritten = rewrite(original);
            } catch (Exception e) {
                log.warn("查询改写失败, 沿用原查询: {}", e.getMessage());
            }
            // 无论改写成功与否都累加计数，避免改写失败时无限回环
            updates.put("retryCount", state.getRetryCount() + 1);
            if (rewritten != null && !rewritten.isBlank()) {
                updates.put("currentQuery", rewritten);
                log.info("查询改写(第{}次): 「{}」-> 「{}」", state.getRetryCount() + 1, original, rewritten);
            }
            return updates;
        });
    }

    private String rewrite(String question) throws Exception {
        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .messages(List.of(Message.builder()
                        .role(Role.USER.getValue())
                        .content("把下面的用户提问改写为更适合法律条文检索的查询式。\n"
                                + "要求：保留核心意图，补充法律术语，去除口语化表达。只输出改写后的查询，不要解释。\n\n"
                                + "原提问：" + question)
                        .build()))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        return new Generation().call(param).getOutput().getChoices().get(0).getMessage().getContent();
    }
}
