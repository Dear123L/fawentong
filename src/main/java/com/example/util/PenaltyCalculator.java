package com.example.util;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合同违约金计算工具（确定性函数，可单测、可审计）。
 * 计算公式：
 *   违约金 = 本金 × 日利率(千分比) / 1000 × 天数
 * 法律金额要求确定性，交给定点 Java 函数最稳，避免 LLM 多步小数乘法出错。
 */
@Slf4j
public class PenaltyCalculator {

    /** 日利率（千分比）合理上限：本 KB 最高为 50‰（日利率5%，T017），留余量到 100‰(10%)。 */
    public static final double MAX_RATE_PER_MILLE = 100.0;

    public static Map<String, Object> calculate(double principal, double dailyRatePerMille, int days) {
        return calculate(principal, dailyRatePerMille, days, null);
    }

    public static Map<String, Object> calculate(double principal, double dailyRatePerMille, int days, String context) {
        double rate = normalizeDailyRate(dailyRatePerMille, context);
        double penalty = principal * (rate / 1000.0) * days;
        BigDecimal rounded = new BigDecimal(penalty).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("principal", principal);
        result.put("daily_rate_per_mille", rate);
        result.put("days", days);
        result.put("penalty", rounded.doubleValue());
        result.put("formula", String.format("%s × (%s/1000) × %s", principal, rate, days));
        return result;
    }

    /**
     * 数值量级校验 / 归一化（修复 ReAct 抽取偶发漂移导致的跑间方差）：
     *  - 若 daily_rate_per_mille &gt; 1（如被抽成 5）且问题上下文含「万分之X」，按 X/10 归一化
     *    （万分之五 = 0.5‰，而非 5‰）。
     *  - 若上下文含「X%」或「千分之X」等显式利率，以其为准（同时兜住 T017 的 5%->50‰，
     *    即便 LLM 把 rate 抽成 5 也会被上下文纠正回 50）。
     *  - 兜底：1 &lt; rate &lt; 10 视为 10× 误抽（÷10），但严格保护合法高利率（50‰ 不在此区间，不动）。
     *  - rate &lt;= 1（如 0.5‰）视为已正确，原样返回。
     */
    public static double normalizeDailyRate(double rate, String context) {
        if (rate <= 1.0) {
            return rate; // 已是合法千分比（如 0.5‰），无需处理
        }
        // 上下文感知：原文显式给出的利率优先（万分之X / 千分之X / X%）
        Double ctx = ContractParamParser.rateFromContext(context);
        if (ctx != null) {
            if (Math.abs(ctx - rate) > 1e-9) {
                log.info("daily_rate_per_mille 归一化(上下文对齐): {} -> {}", rate, ctx);
            }
            return ctx;
        }
        // 兜底：1<rate<10 视为 10× 误抽（保护合法 50‰ 不被误÷10）
        if (rate < 10.0) {
            double fixed = rate / 10.0;
            log.info("daily_rate_per_mille 归一化(10x 误抽兜底): {} -> {}", rate, fixed);
            return fixed;
        }
        return rate;
    }
}
