package com.example.calc;

import com.example.util.PenaltyCalculator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 违约金计算器适配器：包裹现有 {@link PenaltyCalculator} 确定性函数，使其符合
 * {@link LegalCalculator} 接口，零公式改动。
 *
 * <p>边界处理：{@code valid} 判定只要求 {@code principal>0 && days>0}，
 * 「本金为 0」(T015) 与「未逾期天数 0」(T016) 这类合法边界题需返回 {@code 0.0} 而非 {@code null}。
 * 适配器直接委托 PenaltyCalculator，由 {@code 0 × 任何数 = 0} 自然得出 {@code 0.0}，符合法律确定性。</p>
 */
public class PenaltyCalculatorAdapter implements LegalCalculator {

    @Override
    public CalcType type() {
        return CalcType.PENALTY;
    }

    @Override
    public Map<String, Object> compute(ExtractedParams p) {
        if (p == null || p.principal == null || p.dailyRatePerMille == null || p.days == null) {
            return null; // 参数不完整，交给调用方降级（LLM 直算 / 置 null）
        }
        if (p.principal < 0 || p.days < 0) {
            return null; // 负数非法
        }
        // 委托既有确定性函数；context 传原始问题做量级归一化，行为与改造前一致
        Map<String, Object> raw = PenaltyCalculator.calculate(p.principal, p.dailyRatePerMille, p.days, p.question);
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.put("penalty", ((Number) raw.get("penalty")).doubleValue());
        return out;
    }
}
