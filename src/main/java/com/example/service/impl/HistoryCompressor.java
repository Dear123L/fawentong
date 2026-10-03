package com.example.service.impl;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 历史对话压缩器（P1a 记忆压缩）。
 *
 * 把超窗的老轮次（一个或多个完整轮）经 LLM 压缩成一条摘要。默认使用便宜小模型
 * {@code qwen-flash}（与 GradeNode 同款，已验证可用），可经 {@code memory.compress.model} 切换为
 * 更便宜的 {@code qwen-turbo}。
 *
 * 降级契约：任何异常（限流 / 超时 / key 缺失 / 网络抖动）都返回 {@code null}。
 * 调用方据此「不压缩、保持旧行为」，绝不拖垮主问答链路。压缩是纯增量能力，
 * 失败等价于没启用压缩，约束条款另有结构化存储兜底（见 RedisSessionMemoryServiceImpl）。
 */
@Slf4j
@Component
public class HistoryCompressor {

    @Value("${dashscope.api.key:}")
    private String apiKey;

    @Value("${memory.compress.model:qwen-flash}")
    private String model;

    @Value("${memory.compress.enabled:true}")
    private boolean enabled;

    /**
     * 将「已有摘要 + 待压缩的新对话片段」合并压缩为一条摘要文本。
     *
     * @param existingSummary 已有的滚动摘要（可能为 null/空，表示首次压缩）
     * @param evictedText     本次要压缩的历史文本（已格式化为「用户：/助手：」段落）
     * @return 压缩后的摘要文本；失败返回 null（调用方降级为不压缩）
     */
    public String summarize(String existingSummary, String evictedText) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            String prompt = buildPrompt(existingSummary, evictedText);
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .messages(java.util.List.of(userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .temperature(0.0f)
                    .build();
            GenerationResult result = new Generation().call(param);
            if (result == null || result.getOutput() == null
                    || result.getOutput().getChoices() == null
                    || result.getOutput().getChoices().isEmpty()) {
                return null;
            }
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            return (content == null || content.isBlank()) ? null : content.trim();
        } catch (ApiException | NoApiKeyException e) {
            log.warn("历史压缩 LLM 调用失败，降级为不压缩: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("历史压缩异常，降级为不压缩", e);
            return null;
        }
    }

    /**
     * 压缩 prompt：强制保留数值型约束与条款编号，这是「防约束条款误摘要」的第二道兜底
     * （第一道是结构化存储兜底，第三道是近轮不压）。
     */
    private String buildPrompt(String existingSummary, String evictedText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是法律问答系统的对话压缩器。下面是要压缩的历史对话片段（可能是之前摘要的延续）。\n");
        sb.append("请压缩为一条简洁中文摘要，供后续轮次理解上下文。\n");
        sb.append("硬性要求：\n");
        sb.append("1. 必须原值保留所有数值型约束：金额（本金/违约金/利息）、天数、利率、百分比、封顶值。\n");
        sb.append("2. 必须保留条款编号（如「第585条」「条款X」）、合同类型（借款/买卖/租赁等）、当事方（甲方/乙方）。\n");
        sb.append("3. 保留用户未解决的关键问题或待办。\n");
        sb.append("4. 不编造新事实，不替用户做决策。\n");
        sb.append("5. 只输出摘要文本本身，不要 JSON、不要解释、不要加前缀。\n\n");
        sb.append("【已有摘要】\n").append(existingSummary == null || existingSummary.isBlank() ? "(无)" : existingSummary).append("\n\n");
        sb.append("【待压缩的新对话】\n").append(evictedText).append("\n\n");
        sb.append("【压缩后摘要】");
        return sb.toString();
    }
}
