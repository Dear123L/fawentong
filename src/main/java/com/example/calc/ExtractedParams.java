package com.example.calc;

/**
 * 计算智能体从问题中确定性抽取的数值参数（与 {@code ContractParamParser} 解耦的干净载体）。
 *
 * <p>各 LegalCalculator 仅依赖此对象，便于单测与扩展；原始问题文本 {@link #question}
 * 一并携带，供违约金计算做上下文量级归一化（如「万分之五」对齐）。</p>
 */
public class ExtractedParams {
    /** 本金（元）；null=未抽到 */
    public Double principal;
    /** 日利率（千分比）；null=未明说 */
    public Double dailyRatePerMille;
    /** 天数；null=未抽到 */
    public Integer days;
    /** 是否含「不可抗力」免责 */
    public boolean forceMajeure;
    /** 原始问题文本（上下文归一化用） */
    public String question;

    public boolean isComplete() {
        return principal != null && dailyRatePerMille != null && days != null;
    }
}
