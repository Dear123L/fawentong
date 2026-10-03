package com.example.calc;

/**
 * 法律数值计算类型。
 *
 * <p>把「计算智能体」从单一违约金公式，升级为可插拔的注册表：每类法律数值计算
 * 对应一个 {@link LegalCalculator} 实现，由 {@link CalcRegistry} 统一调度。</p>
 *
 * <p>Phase 0 先落地 PENALTY（违约金，默认）；Phase 1 增加 LOAN_INTEREST（借贷利息，
 * 含利率上限）。后续 Phase 2 可扩展 LABOR_COMP（劳动补偿）/ DEPOSIT（押金扣减）。</p>
 */
public enum CalcType {
    /** 违约金 / 逾期付款赔偿：本金 × 日利率(‰) / 1000 × 天数 */
    PENALTY,
    /** 借贷利息：单利/复利，年化利率受司法保护上限（4×LPR）约束 */
    LOAN_INTEREST
}
