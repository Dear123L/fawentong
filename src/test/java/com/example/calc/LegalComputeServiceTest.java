package com.example.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LegalComputeService 共享计算内核单测（脱离 Spring，手动构造 CalcRegistry）。
 * 覆盖：违约金主路径 / 借贷利率封顶 / 不可抗力 / 非金额语境 / 跨轮继承保利率上限。
 */
class LegalComputeServiceTest {

    private final CalcRegistry registry = new CalcRegistry();
    private final LegalComputeService service = new LegalComputeService(registry);

    private ExtractedParams params(Double p, Double r, Integer d, boolean fm, String q) {
        ExtractedParams ep = new ExtractedParams();
        ep.principal = p;
        ep.dailyRatePerMille = r;
        ep.days = d;
        ep.forceMajeure = fm;
        ep.question = q;
        return ep;
    }

    @Test
    void penaltyHappyPath() {
        ExtractedParams ep = params(100000.0, 0.5, 30, false, "逾期付款违约金多少");
        LegalComputeService.ComputeResult r = service.compute(ep, null, ep.question);
        assertEquals(CalcType.PENALTY, r.calcType);
        assertEquals(1500.0, r.penalty, 0.001);
        assertTrue(r.handoff.contains("[PENALTY]"));
        assertTrue(r.handoff.contains("1500.00"));
    }

    @Test
    void loanInterestCapped() {
        // 日利率 5%（=50‰）→ 年化 1825%，应封顶到 4×LPR=13.8%
        ExtractedParams ep = params(100000.0, 50.0, 365, false, "借款利息怎么算");
        LegalComputeService.ComputeResult r = service.compute(ep, null, ep.question);
        assertEquals(CalcType.LOAN_INTEREST, r.calcType);
        assertEquals(13800.0, r.penalty, 0.001);
        assertTrue(r.handoff.contains("[LOAN_INTEREST]"));
        assertTrue(r.handoff.contains("13800.00"));
    }

    @Test
    void forceMajeureZero() {
        ExtractedParams ep = params(100000.0, 0.5, 30, true, "因不可抗力无法付款");
        LegalComputeService.ComputeResult r = service.compute(ep, null, ep.question);
        assertEquals(0.0, r.penalty, 0.001);
        assertTrue(r.handoff.contains("不可抗力免责"));
    }

    @Test
    void nonNumericNoCompute() {
        ExtractedParams ep = params(null, null, null, false, "这条条款是否有问题");
        LegalComputeService.ComputeResult r = service.compute(ep, null, ep.question);
        assertNull(r.penalty);
        assertTrue(r.handoff.contains("非金额计算语境"));
    }

    @Test
    void crossTurnInheritanceKeepsLoanCap() {
        // 上轮算过 LOAN_INTEREST；当轮短追问"改成60天呢"应继承 LOAN_INTEREST 保住封顶（G3 修复）
        ExtractedParams ep = params(100000.0, 50.0, 60, false, "改成60天呢");
        LegalComputeService.ComputeResult r = service.compute(ep, CalcType.LOAN_INTEREST, ep.question);
        assertEquals(CalcType.LOAN_INTEREST, r.calcType);
        double expected = 100000.0 * 0.138 * (60.0 / 365.0);
        assertEquals(expected, r.penalty, 0.01);
    }
}
