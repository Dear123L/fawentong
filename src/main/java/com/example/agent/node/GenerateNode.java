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
 * 生成节点：把评分门过滤后的片段拼成带编号引用的上下文，交给 LLM 成文。
 *
 * <p>无片段时直接拒答（不把空上下文喂给模型），杜绝编造条款。
 * 产物 {@code ragAnswer} 会被外层 {@code RetrieverAgentNode} 直接复用，
 * 因此外层不再重复做一次 refine 合成，省一次 LLM 调用。
 */
@Slf4j
@Component
public class GenerateNode {

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.api.model:deepseek-v3}")
    private String modelName;

    /** 拒答文案：同时被外层节点用于 rejected 判定（contains 匹配） */
    static final String REJECT_ANSWER = "抱歉，知识库中未检索到相关合同条款，无法作答。";

    public CompletableFuture<Map<String, Object>> execute(AgenticState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();

            List<Map<String, Object>> docs = state.getGradedDocs().isEmpty()
                    ? state.getRetrievedDocs()
                    : state.getGradedDocs();

            if (docs == null || docs.isEmpty()) {
                // 无相关片段则拒答，杜绝编造
                updates.put("ragAnswer", REJECT_ANSWER);
                updates.put("finalAnswer", REJECT_ANSWER);
                log.info("无相关片段，拒答");
                return updates;
            }

            // 编号化引用上下文：[i+1] 片段
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < docs.size(); i++) {
                Object contentObj = docs.get(i).get("content");
                String content = contentObj != null ? contentObj.toString() : "";
                context.append("[").append(i + 1).append("] ").append(content).append("\n\n");
            }

            String prompt = String.format(
                    "你是中国法律领域的助手。请严格依据下列检索到的条款片段回答问题。\n"
                    + "要求：\n"
                    + "1. 只能使用片段中的内容，不得凭记忆补充或编造法条；\n"
                    + "2. 引用处必须标注片段编号，如 [1]；\n"
                    + "3. 若片段不足以回答，请明确说明缺什么。\n\n"
                    + "片段：\n%s\n问题：%s",
                    context.toString(), state.getOriginalQuestion());

            String answer;
            try {
                answer = callLlm(prompt);
            } catch (Exception e) {
                log.error("生成失败: {}", e.getMessage(), e);
                answer = "抱歉，答案生成失败，请稍后重试。";
            }
            updates.put("ragAnswer", answer);
            updates.put("finalAnswer", answer);
            return updates;
        });
    }

    private String callLlm(String prompt) throws Exception {
        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .messages(List.of(Message.builder()
                        .role(Role.USER.getValue())
                        .content(prompt)
                        .build()))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        return new Generation().call(param).getOutput().getChoices().get(0).getMessage().getContent();
    }
}
