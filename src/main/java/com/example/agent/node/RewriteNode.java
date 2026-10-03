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
 * <p>只执行一次（以 rewriteCount 标记），避免与外层多轮改写叠加放大语义漂移。
 */
@Slf4j
@Component
public class RewriteNode {

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.api.model:deepseek-v3}")
    private String modelName;

    public CompletableFuture<Map<String, Object>> execute(AgenticState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            if (state.isRewritten()) {
                log.info("查询已改写过，跳过 RewriteNode");
                return updates;
            }
            String original = state.getOriginalQuestion();
            String rewritten = null;
            try {
                rewritten = rewrite(original);
            } catch (Exception e) {
                log.warn("查询改写失败, 沿用原查询: {}", e.getMessage());
            }
            if (rewritten != null && !rewritten.isBlank()) {
                updates.put("currentQuery", rewritten);
                updates.put("rewriteCount", 1);
                log.info("查询改写: 「{}」-> 「{}」", original, rewritten);
            } else {
                // 兜底：即使改写失败也标记一次，避免死循环反复调用改写
                updates.put("rewriteCount", 1);
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
