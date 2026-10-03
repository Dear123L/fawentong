package com.example.calc;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 法律数值计算器注册表（Phase 0 核心）。
 *
 * <p>计算智能体节点不再硬编码公式，而是：
 *  {@code CalcType t = route(question); registry.get(t).compute(params);}
 * 新增一类计算只需实现 {@link LegalCalculator} 并 {@link #register} 即可，零侵入。</p>
 *
 * <p>路由策略（确定性关键词，可单测、可审计）：
 *  - 含「利息 / 利率 / 借贷 / 借款 / 贷款 / 借了」→ LOAN_INTEREST（适用民间借贷利率上限）
 *  - 其余（违约金 / 逾期付款赔偿等）→ PENALTY（默认）
 *  - 多轮追问继承：当轮无借贷触发词，但上轮算过（prevCalcType 非空）且当轮含改写指示
 *    （改成 / 如果 / 变成 / 天 / 利率 / 本金 / 金额 / 呢）→ 继承上轮 calcType，
 *    保住利率上限封顶（修复 G3「改成60天呢」误路由 PENALTY 致金额未封顶）。</p>
 */
@Component
public class CalcRegistry {

    private final Map<CalcType, LegalCalculator> registry = new EnumMap<>(CalcType.class);

    private static final Pattern LOAN_PATTERN =
            Pattern.compile(".*(利息|利率|借贷|借款|贷款|借了).*");

    /** 多轮追问改写指示：当轮无借贷触发词但上轮算过时，命中即继承上轮 calcType。 */
    private static final Pattern REWRITE_PATTERN =
            Pattern.compile(".*(改成|如果|变成|天|利率|本金|金额|呢).*");

    public CalcRegistry() {
        // 注册已知计算器；默认违约金放最后保证其为兜底
        register(new LoanInterestCalculator());
        register(new PenaltyCalculatorAdapter());
    }

    public void register(LegalCalculator calculator) {
        registry.put(calculator.type(), calculator);
    }

    public LegalCalculator get(CalcType type) {
        return registry.get(type);
    }

    /** 默认计算器：违约金（兜底）。 */
    public LegalCalculator defaultCalculator() {
        return registry.get(CalcType.PENALTY);
    }

    /** 按问题语义确定性路由到计算类型。纯静态、无副作用、可单测。 */
    public static CalcType route(String question) {
        return route(question, null);
    }

    /**
     * 路由（含多轮追问继承）。prevCalcType 为上一轮计算类型（来自会话记忆）；
     * 当轮无借贷触发词、但上轮算过且当轮含改写指示时，继承上轮 calcType，
     * 避免「改成60天呢」这类短追问丢掉利率上限封顶。
     */
    public static CalcType route(String question, CalcType prevCalcType) {
        if (question != null && LOAN_PATTERN.matcher(question).matches()) {
            return CalcType.LOAN_INTEREST;
        }
        if (question != null && prevCalcType != null
                && REWRITE_PATTERN.matcher(question).matches()) {
            return prevCalcType;
        }
        return CalcType.PENALTY;
    }
}
