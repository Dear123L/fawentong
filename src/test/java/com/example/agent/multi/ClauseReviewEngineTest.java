package com.example.agent.multi;

import com.example.calc.CalcRegistry;
import com.example.calc.LegalComputeService;
import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import com.example.service.RagVectorService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClauseReviewEngine 针对性单测（不依赖 Spring / ES / 真实 LLM）。
 *
 * <p>覆盖：①正常条款不调 LLM（控制成本）；②借贷利率超 4×LPR 触发数值异常；
 * ③无检索依据触发低相关；④LLM 三段输出正确解析；⑤报告计数与纯文本拼装正确。</p>
 */
class ClauseReviewEngineTest {

    /** 固定三段式改写回答（模拟 LLM）。 */
    private static final String CANNED_LLM =
            "风险点：利率超过法定上限4倍LPR，超出部分可能被认定无效\n"
            + "改写建议：将日利率降至全国银行间同业拆借中心公布的一年期贷款市场报价利率四倍以内\n"
            + "替换条款：借款利率按全国银行间同业拆借中心公布的一年期贷款市场报价利率的四倍计算";

    private ClauseReviewEngine buildEngine(AtomicInteger llmCalls) {
        // 真实计算内核（手动构造 CalcRegistry，无需 Spring）
        LegalComputeService legal = new LegalComputeService(new CalcRegistry());

        // 桩：切分返回 3 条固定条款
        DocumentTextExtractorService extractor = new DocumentTextExtractorService() {
            @Override
            public String extractText(org.springframework.web.multipart.MultipartFile file) {
                return null;
            }
            @Override
            public List<ClauseBlock> splitIntoClauseBlocks(String text, String sourceLabel) {
                List<ClauseBlock> blocks = new ArrayList<>();
                blocks.add(new ClauseBlock(
                        "第一条 甲方应按约定向乙方交付货物，质量符合国家标准。", "doc1_C1", 0));
                blocks.add(new ClauseBlock(
                        "第八条 借款利率为每日千分之五，逾期按此计息。", "doc1_C8", 0));
                blocks.add(new ClauseBlock(
                        "第十二条 本合同自双方签字盖章之日起生效。", "doc1_C12", 0));
                return blocks;
            }
        };

        // 桩：按查询文本决定检索结果（命中/低相关）
        RagVectorService rag = new RagVectorService() {
            @Override
            public void initEsIndex() {}
            @Override
            public void uploadDocument(Long kbId, Long userId,
                                       org.springframework.web.multipart.MultipartFile file) {}
            @Override
            public List<Map<String, Object>> hybridSearch(Long kbId, String query, int topK) {
                if (query.contains("签字盖章")) {
                    return List.of(); // 无管辖依据 -> 低相关
                }
                Map<String, Object> hit = new HashMap<>();
                hit.put("clauseId", "KB_C12");
                hit.put("content", "民法典第五百零九条：当事人应当按照约定全面履行自己的义务。");
                hit.put("score", 0.5);
                return List.of(hit);
            }
        };

        // 桩：ToolCallingLlm，仅统计调用次数 + 返回固定三段
        ToolCallingLlm llm = new ToolCallingLlm(null) {
            @Override
            public String chat(String userPrompt) {
                llmCalls.incrementAndGet();
                return CANNED_LLM;
            }
        };

        return new ClauseReviewEngine(extractor, rag, legal, llm);
    }

    @Test
    void review_selectiveLlm_and_riskDetection() {
        AtomicInteger llmCalls = new AtomicInteger(0);
        ClauseReviewEngine engine = buildEngine(llmCalls);

        ClauseReviewEngine.ReviewReport report =
                engine.review("（整段合同文本）", 1L, "doc1");

        // 1) 切分得到 3 条
        assertEquals(3, report.getReviewedCount());

        // 2) 第 1 条：正常履约条款，有依据、无数值异常 -> 不调 LLM
        ClauseReviewEngine.ClauseReviewItem it1 = report.items.get(0);
        assertFalse(it1.risky, "正常条款不应判为风险");
        assertFalse(it1.numericAnomaly);
        assertFalse(it1.lowRelevance);
        assertNull(it1.riskPoint, "正常条款不应生成风险点（不应调 LLM）");
        assertEquals("第一条", it1.clauseLabel);

        // 3) 第 2 条：借款利率每日千分之五 -> 数值异常（> 4×LPR 上限）
        ClauseReviewEngine.ClauseReviewItem it2 = report.items.get(1);
        assertTrue(it2.risky);
        assertTrue(it2.numericAnomaly, "千分之五日利率应判为数值异常");
        assertNotNull(it2.riskPoint);
        assertTrue(it2.riskPoint.contains("法定上限"));
        assertTrue(it2.rewriteSuggestion != null && it2.rewriteSuggestion.contains("贷款市场报价利率"));
        assertTrue(it2.clauseReplacement != null && it2.clauseReplacement.contains("贷款市场报价利率"));
        assertEquals("第八条", it2.clauseLabel);

        // 4) 第 3 条：无管辖依据 -> 低相关 -> 风险
        ClauseReviewEngine.ClauseReviewItem it3 = report.items.get(2);
        assertTrue(it3.risky);
        assertTrue(it3.lowRelevance, "无检索依据应判为低相关");
        assertNotNull(it3.riskPoint);

        // 5) LLM 仅被调用 2 次（第 2、3 条），第 1 条跳过
        assertEquals(2, llmCalls.get(), "正常条款不应触发 LLM，LLM 调用次数应为 2");

        // 6) 报告纯文本拼装
        String text = report.toPlainText();
        assertTrue(text.contains("合同审查报告"));
        assertTrue(text.contains("发现风险 2 条"));
        assertTrue(text.contains("第一条") && text.contains("第八条") && text.contains("第十二条"));
    }

    @Test
    void review_emptyText_returnsEmptyReport() {
        AtomicInteger llmCalls = new AtomicInteger(0);
        ClauseReviewEngine engine = buildEngine(llmCalls);
        // 切分桩对空文本仍返回 3 条（桩不依赖输入），这里仅验证空文本不抛异常且该场景可降级
        ClauseReviewEngine.ReviewReport report = engine.review("", 1L, "docX");
        assertNotNull(report);
        assertEquals(0, llmCalls.get());
    }
}
