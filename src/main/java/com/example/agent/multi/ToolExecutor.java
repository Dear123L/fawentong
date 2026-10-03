package com.example.agent.multi;

import com.alibaba.fastjson2.JSON;
import com.example.service.RagVectorService;
import com.example.util.PenaltyCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行器：分发多智能体所需的 function calling 工具。
 *  - calculate_penalty：确定性违约金计算（含入参 JSON schema 校验 + 上下文感知量级归一化）
 *  - cite_clause：从合同知识库（ES 混合检索）溯源最相关条款原文
 */

/**
 * 工具执行器：分发多智能体所需的 function calling 工具。
 *  - calculate_penalty：确定性违约金计算
 *  - cite_clause：从合同知识库（ES 混合检索）溯源最相关条款原文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final RagVectorService ragVectorService;

    /**
     * 执行工具调用，返回结构化结果文本（JSON 字符串，便于回填给 LLM）。
     */
    public String execute(String name, Map<String, Object> args, Long kbId) {
        return execute(name, args, kbId, null);
    }

    /**
     * 执行工具调用，返回结构化结果文本（JSON 字符串，便于回填给 LLM）。
     * @param context 原始问题上下文，用于 calculate_penalty 的 rate 合理性归一化
     */
    public String execute(String name, Map<String, Object> args, Long kbId, String context) {
        if (name == null) {
            return "{}";
        }
        try {
            if ("calculate_penalty".equals(name)) {
                double principal = toDouble(args.get("principal"));
                double rawRate = toDouble(args.get("daily_rate_per_mille"));
                int days = toInt(args.get("days"));
                // 1) 上下文感知量级归一化（万分之X -> X/10 等），先于校验
                double rate = PenaltyCalculator.normalizeDailyRate(rawRate, context);
                // 2) JSON schema 校验：principal>0、0<rate<=MAX、days>0；失败不默认 0
                List<String> errors = validateCalcArgs(principal, rate, days);
                if (!errors.isEmpty()) {
                    log.warn("calculate_penalty 入参校验失败 {}：principal={}, rawRate={}, normRate={}, days={}",
                            errors, principal, rawRate, rate, days);
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("valid", false);
                    err.put("error", String.join("; ", errors));
                    err.put("penalty", null);
                    return JSON.toJSONString(err);
                }
                Map<String, Object> result = PenaltyCalculator.calculate(principal, rate, days, context);
                result.put("valid", true);
                log.info("执行 calculate_penalty: {} × {}/1000 × {} = {}", principal, rate, days, result.get("penalty"));
                return JSON.toJSONString(result);
            } else if ("cite_clause".equals(name)) {
                String query = args.getOrDefault("query", "").toString();
                int topK = args.containsKey("top_k") ? toInt(args.get("top_k")) : 3;
                return citeClause(query, topK, kbId);
            }
        } catch (Exception e) {
            log.error("工具 {} 执行失败", name, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
        return "{}";
    }

    /**
     * calculate_penalty 入参的 JSON schema 约束：
     *  - principal：正数（&gt;0）
     *  - daily_rate_per_mille：正数（&gt;0）且在合理范围（&lt;= MAX_RATE_PER_MILLE）
     *  - days：正整数（&gt;0）
     * 返回违例信息列表；空列表表示通过。校验失败时不默认 0，交由上层降级 LLM 直算。
     */
    private List<String> validateCalcArgs(double principal, double rate, int days) {
        List<String> errors = new ArrayList<>();
        if (!(principal > 0)) {
            errors.add("principal 必须为正数");
        }
        if (!(rate > 0)) {
            errors.add("daily_rate_per_mille 必须为正数");
        } else if (rate > PenaltyCalculator.MAX_RATE_PER_MILLE) {
            errors.add("daily_rate_per_mille 超出合理范围(<= " + PenaltyCalculator.MAX_RATE_PER_MILLE + ")");
        }
        if (!(days > 0)) {
            errors.add("days 必须为正整数");
        }
        return errors;
    }

    /**
     * 条款溯源：复用真实 ES 混合检索（BM25 + KNN + RRF），返回最相关条款原文。
     */
    private String citeClause(String query, int topK, Long kbId) {
        try {
            List<Map<String, Object>> docs = ragVectorService.hybridSearch(kbId, query, topK);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> doc : docs) {
                Map<String, Object> m = new LinkedHashMap<>();
                Object content = doc.get("content");
                m.put("text", content != null ? content.toString() : "");
                out.add(m);
            }
            log.info("cite_clause 检索到 {} 条相关条款", out.size());
            return JSON.toJSONString(out);
        } catch (Exception e) {
            log.error("cite_clause 检索失败", e);
            return "[]";
        }
    }

    private double toDouble(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private int toInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
