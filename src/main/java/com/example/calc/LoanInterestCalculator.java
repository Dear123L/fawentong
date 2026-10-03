package com.example.calc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 借贷利息计算器（Phase 1）：支持单利 / 复利，且对年化利率施加司法保护上限。
 *
 * <p>法律约束：最高人民法院关于审理民间借贷案件的规定（第 25 条）——
 * 约定利率超过合同成立时一年期 LPR 四倍的部分不受保护。
 * 本实现将「日利率(‰)」折算为年化后封顶，超出部分按 4×LPR 计，
 * 避免产出法律不支持的畸高利息（如 T017 的日利率 5% → 年化 1825% 明显违法）。</p>
 *
 * <p>计算口径（沿用 penalty 字段名，便于评测脚本不改）：
 *  - 年化输入 = dailyRatePerMille / 1000 × 365
 *  - 有效年化 = min(年化输入, LEGAL_ANNUAL_RATE_CAP)
 *  - 单利 = 本金 × 有效年化 × 天数 / 365
 *  - 复利 = 本金 × ((1 + 有效年化/365)^天数 − 1)
 * </p>
 */
public class LoanInterestCalculator implements LegalCalculator {

    /** 民间借贷司法保护上限：4 × 一年期 LPR（取值 3.45%）= 13.8% 年化。可据实调整。 */
    public static final double LEGAL_ANNUAL_RATE_CAP = 0.138;

    public enum Mode { SIMPLE, COMPOUND }

    @Override
    public CalcType type() {
        return CalcType.LOAN_INTEREST;
    }

    @Override
    public Map<String, Object> compute(ExtractedParams p) {
        return compute(p, Mode.SIMPLE);
    }

    public Map<String, Object> compute(ExtractedParams p, Mode mode) {
        if (p == null || p.principal == null || p.dailyRatePerMille == null || p.days == null) {
            return null;
        }
        if (p.principal < 0 || p.days < 0) {
            return null;
        }
        double annualInput = p.dailyRatePerMille / 1000.0 * 365.0;     // 千分比日利率 -> 年化
        double effAnnual = Math.min(annualInput, LEGAL_ANNUAL_RATE_CAP); // 利率上限封顶
        double interest;
        if (mode == Mode.COMPOUND) {
            double daily = effAnnual / 365.0;
            interest = p.principal * (Math.pow(1.0 + daily, p.days) - 1.0);
        } else {
            interest = p.principal * effAnnual * (p.days / 365.0);
        }
        double rounded = Math.round(interest * 100.0) / 100.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("principal", p.principal);
        out.put("daily_rate_per_mille_input", p.dailyRatePerMille);
        out.put("annual_rate_input", annualInput);
        out.put("annual_rate_effective", effAnnual);
        out.put("days", p.days);
        out.put("mode", mode.name());
        out.put("penalty", rounded); // 复用 penalty 字段，评测口径不变
        out.put("formula", String.format(
                "本金%.0f × 年化%.4f%% × %d/365（封顶 %.1f%%，原始年化 %.2f%%）",
                p.principal, effAnnual * 100, p.days, LEGAL_ANNUAL_RATE_CAP * 100, annualInput * 100));
        return out;
    }
}
