package com.example.calc;

import java.util.Map;

/**
 * 法律数值计算器统一接口（策略模式）。
 *
 * <p>每个实现对应一种 {@link CalcType}。{@link CalcRegistry} 按类型分发，
 * 计算智能体节点不再硬编码公式，实现「可插拔、可单测、可审计」。</p>
 *
 * @return 含 {@code penalty}（最终金额）与 {@code formula}（可追溯公式）等的 map；
 *         参数不完整无法计算时返回 {@code null}。
 */
public interface LegalCalculator {
    CalcType type();

    Map<String, Object> compute(ExtractedParams params);
}
