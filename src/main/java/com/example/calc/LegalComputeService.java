package com.example.calc;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 法律数值计算共享服务（Phase 0 抽取）。
 *
 * <p>把 {@code RetrieverAgentNode.runCalc} 中「与具体问答/会话无关」的纯计算内核
 * （route → calculator.compute → penalty/handoff 拼装）抽成独立服务，
 * 供 Retriever（按问题计算）与后续 ClauseReviewer（按条款计算）共用，消除重复。</p>
 *
 * <p>设计约束：本服务只做「给定 ExtractedParams + 上轮计算类型 → 金额/手递文本」的确定性计算，
 * 不含任何会话记忆（session 跨轮继承、ReAct 兜底抽取）与 LLM 直算降级——
 * 这些 Retriever 专属逻辑仍留在 {@code RetrieverAgentNode}，保证抽取后行为逐字节等价。</p>
 */
@Service
public class LegalComputeService {

    private final CalcRegistry calcRegistry;

    public LegalComputeService(CalcRegistry calcRegistry) {
        this.calcRegistry = calcRegistry;
    }

    /**
     * 执行数值计算内核。
     *
     * @param ep           已解析的数值参数（principal/rate/days/forceMajeure/question）
     * @param prevCalcType 上轮计算类型（跨轮追问继承用，可 null）
     * @param text         用于路由的原始文本（问题或条款），路由判定与不可抗力文案使用
     * @return 计算结果：calcType / penalty（可能为 null）/ handoff（确定性手递文本）
     */
    public ComputeResult compute(ExtractedParams ep, CalcType prevCalcType, String text) {
        CalcType calcType = CalcRegistry.route(text, prevCalcType);
        Double penalty;
        String handoff;
        if (ep.forceMajeure) {
            penalty = 0.0;
            handoff = String.format("不可抗力免责（KB C3）：违约金=0元（问题：%s）", text);
        } else {
            LegalCalculator calculator = calcRegistry.get(calcType);
            Map<String, Object> calc = (calculator != null) ? calculator.compute(ep) : null;
            if (calc != null && calc.get("penalty") != null) {
                penalty = ((Number) calc.get("penalty")).doubleValue();
                handoff = String.format("[%s] 本金%.0f元，日利率%.4g‰，%d天，金额=%.2f元（公式 %s）",
                        calcType, ep.principal, ep.dailyRatePerMille, ep.days, penalty, calc.get("formula"));
            } else if (ep.principal != null && ep.principal > 0 && ep.days != null && ep.days > 0) {
                // 参数不完整但确属金额计算题：本服务不触发 LLM 直算（那是 Retriever 专属降级），
                // 返回 null + 失败手递，由调用方决定是否走 LLM 兜底。
                penalty = null;
                handoff = "（计算失败：参数不完整且 LLM 直算无结果）";
            } else {
                penalty = null;
                handoff = "（非金额计算语境，无需计算）";
            }
        }
        return new ComputeResult(calcType, penalty, handoff);
    }

    /** 计算结果载体。 */
    public static class ComputeResult {
        public final CalcType calcType;
        public final Double penalty;
        public final String handoff;

        public ComputeResult(CalcType calcType, Double penalty, String handoff) {
            this.calcType = calcType;
            this.penalty = penalty;
            this.handoff = handoff;
        }
    }
}
