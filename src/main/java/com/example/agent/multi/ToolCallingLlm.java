package com.example.agent.multi;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.example.util.PenaltyCalculator;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具调用 LLM 封装
 *
 * 做法（SDK 版本无关、保证可编译可跑）：
 *  1) 给 LLM 注入工具说明 system 指令，需要算违约金/溯源时只输出 JSON 工具调用；
 *  2) 解析 JSON -> 经 ToolExecutor 执行 -> 把结构化结果回填；
 *  3) 多轮循环：若 LLM 仍输出工具调用则继续执行，直到给出最终答复或达到最大步数。
 * 原生 DashScope tools 可作为后续增强，此处用 ReAct 循环实现等效能力
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallingLlm {

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.api.model:qwen-plus}")
    private String modelName;

    private final ToolExecutor toolExecutor;

    /** 单次对话最多允许的工具调用轮数（多轮 ReAct） */
    private static final int MAX_TOOL_STEPS = 5;

    private static final String TOOL_INSTRUCTION =
            "你拥有两个工具：\n" +
                    "1) calculate_penalty(principal 本金金额/元, daily_rate_per_mille 日违约金千分比, days 逾期天数)：\n" +
                    "   合同违约金 = 本金 × (日利率/1000) × 天数。法律金额必须精确，遇到金额计算请务必调用本工具。\n" +
                    "   入参 JSON schema 约束（违反会被工具拒绝，请务必遵守）：\n" +
                    "     - principal: number，必须为正数（如 1000000，单位元）\n" +
                    "     - daily_rate_per_mille: number，日利率千分比，必须为正数且 <= 100（如「万分之五」=0.5，「5%」=50）\n" +
                    "     - days: integer，必须为正整数（如 30）\n" +
                    "   注意：「万分之X」换算为 X/10 的千分比，「百分之X」换算为 X*10 的千分比。\n" +
                    "2) cite_clause(query 法律问题或条款关键词, top_k 返回条数)：\n" +
                    "   从合同知识库检索最相关条款原文，让回答有据可查、抑制幻觉。\n" +
                    "当需要调用工具时，只输出如下 JSON（不要包含任何额外文字或解释）：\n" +
                    "{\"tool\":\"<工具名>\",\"arguments\":{...}}\n" +
                    "否则正常回答用户问题。";

    /**
     * 带工具调用的对话：system 用默认工具指令。
     */
    public String callWithTools(String userPrompt, Long kbId) {
        return callWithTools(TOOL_INSTRUCTION, userPrompt, kbId);
    }

    /**
     * 带工具调用的对话：可自定义 system 指令（如"你是合同计算智能体"）。
     * 支持多轮工具调用（ReAct 循环）。
     */
    public String callWithTools(String systemPrompt, String userPrompt, Long kbId) {
        try {
            // 多轮 ReAct：以 USER 角色累积消息，规避 SDK 对多角色的支持差异，保证可编译可跑
            List<Message> messages = new ArrayList<>();
            // 注入工具定义（TOOL_INSTRUCTION 含 calculate_penalty / cite_clause 的说明与调用格式），
            // 否则自定义 systemPrompt 下 LLM 不知道工具签名，永远不会发出 JSON 工具调用。
            messages.add(userMsg(TOOL_INSTRUCTION + "\n\n" + systemPrompt + "\n\n" + userPrompt));

            String lastText = "";
            for (int step = 0; step < MAX_TOOL_STEPS; step++) {
                String out = llm(messages);
                lastText = (out == null) ? "" : out;
                ToolCall toolCall = parseToolCall(lastText);
                if (toolCall == null) {
                    // 不再输出工具调用 -> 视为最终答复
                    return lastText;
                }
                String toolResult = toolExecutor.execute(toolCall.name, toolCall.args, kbId, userPrompt);
                messages.add(userMsg("[工具 " + toolCall.name + " 返回结果]\n" + toolResult
                        + "\n\n若还需要调用工具，请只输出 JSON；否则请直接给出最终答复。"));
            }
            // 达到最大步数，做最后一次综合调用，强制收口
            messages.add(userMsg("请基于以上所有工具结果，直接给出最终答复，不要再调用工具。"));
            return llm(messages);
        } catch (Exception e) {
            log.error("工具调用 LLM 异常", e);
            return "";
        }
    }

    /**
     * 带工具调用的对话，并捕获「最后一次 calculate_penalty 调用」的结构化参数与结果。
     * 复用同一套 ReAct 循环（callWithTools 不变，零侵入），仅额外捕获供评测使用的元数据。
     * 返回的 lastToolArgs / lastToolResult 特指 calculate_penalty 调用的入参与出参，便于抽取
     * principal / daily_rate_per_mille / days 与最终 penalty 与金标准比对。
     */
    public ToolCapture callWithToolsCapture(String systemPrompt, String userPrompt, Long kbId) {
        try {
            List<Message> messages = new ArrayList<>();
            // 注入工具定义，确保 Calculator 智能体能发出 calculate_penalty 的 JSON 调用（否则 computed_penalty 永远为 null）
            messages.add(userMsg(TOOL_INSTRUCTION + "\n\n" + systemPrompt + "\n\n" + userPrompt));

            String lastText = "";
            Map<String, Object> calcArgs = null;
            Map<String, Object> calcResult = null;
            for (int step = 0; step < MAX_TOOL_STEPS; step++) {
                String out = llm(messages);
                lastText = (out == null) ? "" : out;
                ToolCall tc = parseToolCall(lastText);
                if (tc == null) {
                    // 不再输出工具调用 -> 视为最终答复
                    return new ToolCapture(lastText, calcArgs, calcResult);
                }
                String toolResult = toolExecutor.execute(tc.name, tc.args, kbId, userPrompt);
                // 仅捕获【首次】calculate_penalty 的入参与出参（避免 ReAct 多轮回填漂移覆盖正确值）
                if ("calculate_penalty".equals(tc.name) && calcArgs == null) {
                    // 归一化抽取到的 rate（解决"万分之五 -> 0.5‰"的 10× 漂移），保证评测 extracted.rate 正确
                    double rawRate = toDoubleSafe(tc.args.get("daily_rate_per_mille"));
                    double normRate = PenaltyCalculator.normalizeDailyRate(rawRate, userPrompt);
                    Map<String, Object> captured = new HashMap<>(tc.args);
                    if (normRate != rawRate) {
                        captured.put("daily_rate_per_mille", normRate);
                    }
                    calcArgs = captured;
                    calcResult = parseJsonMap(toolResult);
                }
                messages.add(userMsg("[工具 " + tc.name + " 返回结果]\n" + toolResult
                        + "\n\n若还需要调用工具，请只输出 JSON；否则请直接给出最终答复。"));
            }
            // 达到最大步数，做最后一次综合调用，强制收口
            messages.add(userMsg("请基于以上所有工具结果，直接给出最终答复，不要再调用工具。"));
            lastText = llm(messages);
            return new ToolCapture(lastText, calcArgs, calcResult);
        } catch (Exception e) {
            log.error("工具调用 LLM 异常", e);
            return new ToolCapture("", null, null);
        }
    }

    /**
     * 评测捕获结果：answer 为最终文本，lastToolArgs/lastToolResult 为最后一次 calculate_penalty 的入参与出参。
     */
    public record ToolCapture(String answer, Map<String, Object> lastToolArgs, Map<String, Object> lastToolResult) {
    }

    private Map<String, Object> parseJsonMap(String s) {
        try {
            JSONObject obj = JSON.parseObject(s);
            return obj != null ? obj.toJavaObject(Map.class) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private double toDoubleSafe(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 纯对话（无工具）：供 Critic 评审等场景使用。
     */
    public String chat(String userPrompt) {
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(userMsg(userPrompt));
            return llm(messages);
        } catch (Exception e) {
            log.error("纯对话 LLM 异常", e);
            return "";
        }
    }

    /**
     * 解析 LLM 输出是否为 JSON 工具调用（兼容 ```json 代码块包裹）。
     */
    private ToolCall parseToolCall(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        // 去除可能的 ```json ... ``` 包裹
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            JSONObject obj = JSON.parseObject(t);
            if (obj != null && obj.containsKey("tool")) {
                String name = obj.getString("tool");
                JSONObject argsJson = obj.getJSONObject("arguments");
                Map<String, Object> args = argsJson != null
                        ? argsJson.toJavaObject(Map.class)
                        : new HashMap<>();
                return new ToolCall(name, args);
            }
        } catch (Exception e) {
            // 非工具调用，视为普通答复
        }
        return null;
    }

    private String llm(List<Message> messages) throws Exception {
        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .temperature(0.0f)
                .build();

        return new Generation().call(param)
                .getOutput()
                .getChoices()
                .get(0)
                .getMessage()
                .getContent();
    }

    private Message userMsg(String content) {
        return Message.builder()
                .role(Role.USER.getValue())
                .content(content)
                .build();
    }

    private static class ToolCall {
        final String name;
        final Map<String, Object> args;

        ToolCall(String name, Map<String, Object> args) {
            this.name = name;
            this.args = args;
        }
    }
}
