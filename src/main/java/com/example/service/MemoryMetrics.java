package com.example.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 会话记忆层的可观测指标（P2 护栏）：对每一次记忆「读」操作统计 hit / miss。
 *
 * <p>hit 定义：读到了有效内容（历史非空、参数非空、calcType 非 null、条款非空）；
 * miss 定义：读到了空 / null（首轮无记忆、或记忆已过期）。</p>
 *
 * <p>用途：记忆层任何改动（换存储后端、改路由、改继承逻辑）都应让多轮用例产生
 * 稳定的 hit 增量。护栏脚本 {@code memory_eval.py} 在跑 G2/G3 用例前后读取本指标，
 * 若跨轮继承被破坏（follow-up 轮不再读到上轮参数），对应 facet 的 hit 增量会掉到 0，
 * 从而立即暴露回归。</p>
 *
 * <p>指标为进程级累计值，可通过调试端点 {@code /api/memory/metrics} 读取、
 * {@code /api/memory/metrics/reset} 清零。重置仅影响计数，不影响记忆数据本身。</p>
 */
@Component
public class MemoryMetrics {

    /** 对话历史（getHistory）。 */
    private final LongAdder historyHit = new LongAdder();
    private final LongAdder historyMiss = new LongAdder();

    /** 计算抽取参数（getCalcParams）。 */
    private final LongAdder calcParamsHit = new LongAdder();
    private final LongAdder calcParamsMiss = new LongAdder();

    /** 计算类型（getCalcType）—— 跨轮继承的关键信号。 */
    private final LongAdder calcTypeHit = new LongAdder();
    private final LongAdder calcTypeMiss = new LongAdder();

    /** 检索条款（getRetrievedDocs，即滚动归档后的「上一轮」）。 */
    private final LongAdder docsHit = new LongAdder();
    private final LongAdder docsMiss = new LongAdder();

    public void recordHistory(boolean hit) {
        (hit ? historyHit : historyMiss).increment();
    }

    public void recordCalcParams(boolean hit) {
        (hit ? calcParamsHit : calcParamsMiss).increment();
    }

    public void recordCalcType(boolean hit) {
        (hit ? calcTypeHit : calcTypeMiss).increment();
    }

    public void recordDocs(boolean hit) {
        (hit ? docsHit : docsMiss).increment();
    }

    /** 当前累计快照。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("history_hit", historyHit.sum());
        m.put("history_miss", historyMiss.sum());
        m.put("calcParams_hit", calcParamsHit.sum());
        m.put("calcParams_miss", calcParamsMiss.sum());
        m.put("calcType_hit", calcTypeHit.sum());
        m.put("calcType_miss", calcTypeMiss.sum());
        m.put("docs_hit", docsHit.sum());
        m.put("docs_miss", docsMiss.sum());
        return m;
    }

    /** 清零所有计数（不影响记忆数据）。 */
    public void reset() {
        historyHit.reset();
        historyMiss.reset();
        calcParamsHit.reset();
        calcParamsMiss.reset();
        calcTypeHit.reset();
        calcTypeMiss.reset();
        docsHit.reset();
        docsMiss.reset();
    }
}
