package com.example.agent.node;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.example.agent.state.AgenticState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质量评分门节点：对召回片段逐条做「相关性 + 置信度」评分，只保留过门的片段。
 *
 * <p>用轻量模型（qwen-flash）做打分而非主链路模型，把成本与延迟压在一次短输出上。
 * 过门片段写入 {@code gradedDocs}；全部被过滤时由图路由回 RewriteNode 改写查询。
 */
@Slf4j
@Component
public class GradeNode {

    /**
     * 质量评分门阈值：0.6。
     * 仅当 LLM 判定"相关"且置信度 >= 阈值时，该片段才保留进入生成阶段。
     */
    private static final double GRADE_THRESHOLD = 0.6;

    /** 解析 LLM 输出："相关|0.87" / "不相关|0.32" */
    private static final Pattern GRADE_PATTERN = Pattern.compile("(相关|不相关)\\s*\\|\\s*([0-9.]+)");

    @Value("${dashscope.api.key}")
    private String apiKey;

    /** 打分走轻量模型（qwen-flash）而非主链路模型，把成本与延迟压在一次短输出上 */
    @Value("${dashscope.api.model:qwen-flash}")
    private String modelName;

    public CompletableFuture<Map<String, Object>> execute(AgenticState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            try {
                List<Map<String, Object>> docs = state.getRetrievedDocs();
                // 评分过滤后保留的相关片段
                List<Map<String, Object>> gradedDocs = new ArrayList<>();
                // 评分始终针对"原始问题"（用 question 而非 rewritten_question）
                String question = state.getOriginalQuestion();

                if (docs == null || docs.isEmpty()) {
                    updates.put("gradedDocs", gradedDocs);
                    return updates;
                }

                for (Map<String, Object> doc : docs) {
                    Object contentObj = doc.get("content");
                    String content = contentObj != null ? contentObj.toString() : "";
                    if (content.isBlank()) {
                        continue;
                    }
                    double score = scoreWithLlm(question, content);
                    if (score >= GRADE_THRESHOLD) {
                        doc.put("gradeScore", score);
                        gradedDocs.add(doc);
                    }
                }
                log.info("评分完成: 召回 {} 条 → 过门 {} 条（阈值 {}）", docs.size(), gradedDocs.size(), GRADE_THRESHOLD);
                updates.put("gradedDocs", gradedDocs);
                // 评分门信号：供 Graph 条件边直接路由（等价于 gradedDocs 非空，落为独立布尔字段更直观）
                updates.put("relevant", !gradedDocs.isEmpty());
            } catch (Exception e) {
                log.error("评分节点异常: {}", e.getMessage(), e);
                // 评分失败时不做门控：直接放行全部召回片段，避免因单点故障丢失可用依据
                updates.put("gradedDocs", state.getRetrievedDocs());
            }
            return updates;
        });
    }

    /**
     * 调用 LLM 对单个片段打分，返回置信度（0~1）；解析失败按不相关处理。
     */
    private double scoreWithLlm(String question, String content) {
        try {
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(modelName)
                    .messages(List.of(Message.builder()
                            .role(Role.USER.getValue())
                            .content(String.format(
                                    "判断下面这段法律条款与问题的相关性。\n"
                                    + "只输出一行，格式严格为：相关|0.85 或 不相关|0.20\n\n"
                                    + "问题：%s\n\n条款片段：%s", question, content))
                            .build()))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            String resp = new Generation().call(param).getOutput().getChoices().get(0).getMessage().getContent();
            Matcher m = GRADE_PATTERN.matcher(resp == null ? "" : resp.trim());
            if (m.find()) {
                boolean relevant = "相关".equals(m.group(1));
                double conf = Double.parseDouble(m.group(2));
                return relevant ? conf : 0.0;
            }
        } catch (Exception e) {
            log.warn("LLM 评分失败, 该片段按不相关处理: {}", e.getMessage());
        }
        return 0.0;
    }
}
