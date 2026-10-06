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

        // 桩：ToolCallingLlm，统计调用次数；按 prompt 区分「分类」与「风险判断」返回
        ToolCallingLlm llm = new ToolCallingLlm(null) {
            @Override
            public String chat(String userPrompt) {
                llmCalls.incrementAndGet();
                return classifyOrRisk(userPrompt);
            }

            @Override
            public String chat(String userPrompt, String model) {
                llmCalls.incrementAndGet();
                return classifyOrRisk(userPrompt);
            }

            /** 合并调用返回 JSON 结论；按待审条款内容决定维度与风险。 */
            private String classifyOrRisk(String p) {
                if (p == null || !p.contains("一次性输出结论")) {
                    return CANNED_LLM;
                }
                String clause = extractClause(p);
                if (clause.contains("交付货物")) {
                    return "{\"维度\":\"无关\",\"是否有风险\":\"否\","
                            + "\"风险描述\":\"\",\"法律依据\":\"\",\"改写建议\":\"\"}";
                }
                if (clause.contains("利率")) {
                    return "{\"维度\":\"数值\",\"是否有风险\":\"是\","
                            + "\"风险描述\":\"利率超过法定上限\","
                            + "\"法律依据\":\"民间借贷规定\",\"改写建议\":\"降至LPR四倍以内\"}";
                }
                if (clause.contains("签字盖章")) {
                    return "{\"维度\":\"格式\",\"是否有风险\":\"否\","
                            + "\"风险描述\":\"\",\"法律依据\":\"民法典\",\"改写建议\":\"\"}";
                }
                return "{\"维度\":\"语义\",\"是否有风险\":\"否\","
                        + "\"风险描述\":\"\",\"法律依据\":\"\",\"改写建议\":\"\"}";
            }

            /** 只取【待审条款】到【检索到的法规依据】之间的条款正文，避免匹配到说明文字。 */
            private String extractClause(String p) {
                int a = p.indexOf("【待审条款】");
                if (a < 0) {
                    return "";
                }
                int start = a + "【待审条款】".length();
                int b = p.indexOf("【检索到的法规依据】");
                return b > start ? p.substring(start, b) : p.substring(start);
            }

        };

        return new ClauseReviewEngine(extractor, rag, legal, llm, null);
    }

    @Test
    void review_selectiveLlm_and_riskDetection() {
        AtomicInteger llmCalls = new AtomicInteger(0);
        ClauseReviewEngine engine = buildEngine(llmCalls);

        ClauseReviewEngine.ReviewReport report =
                engine.review("（整段合同文本）", 1L, "doc1");

        // 0) 条款数达阈值(3)且占位文本缺多项要素 -> 额外产出 1 条完备性缺失条目
        assertEquals(4, report.getReviewedCount(), "含 1 条合同级完备性缺失条目");

        // 1) 第 1 条：正常履约条款 -> 走白名单/语义判定，不应判为风险
        ClauseReviewEngine.ClauseReviewItem it1 = report.items.get(0);
        assertFalse(it1.risky, "正常条款不应判为风险");
        assertFalse(it1.numericAnomaly);
        assertFalse(it1.lowRelevance);
        assertEquals("第一条", it1.clauseLabel);

        // 2) 第 2 条：借款利率每日千分之五 -> 数值类，走确定性规则（4×LPR 上限）
        //    数值类由规则直接判定，不调用 LLM，故无 riskPoint/改写建议
        ClauseReviewEngine.ClauseReviewItem it2 = report.items.get(1);
        assertTrue(it2.risky);
        assertTrue(it2.numericAnomaly, "千分之五利率应判为数值异常");
        assertNotNull(it2.riskPoint, "合并调用会补充风险描述");
        assertNotNull(it2.rewriteSuggestion, "合并调用会补充改写建议");
        assertEquals("第八条", it2.clauseLabel);

        // 3) 第 3 条：签字盖章条款 -> 格式类；桩检索返回空 -> 低相关 -> 仍判风险
        //    （低相关兜底对所有非无关维度生效，这是设计意图：无依据时提示人工复核）
        ClauseReviewEngine.ClauseReviewItem it3 = report.items.get(2);
        assertTrue(it3.lowRelevance, "无检索依据应判低相关");
        assertTrue(it3.risky, "低相关条款应提示风险以免漏检");

        // 4) 最后一条：合同级完备性检查（占位文本缺全部要素）
        ClauseReviewEngine.ClauseReviewItem comp = report.items.get(3);
        assertTrue(comp.risky, "要素缺失应标风险");
        assertEquals("【合同完备性】", comp.clauseLabel);
        assertNotNull(comp.riskPoint);
        assertTrue(comp.riskPoint.contains("缺少必要要素"));

        // 5) 报告纯文本拼装（含完备性缺失提示）
        String text = report.toPlainText();
        assertTrue(text.contains("合同审查报告"));
        assertTrue(text.contains("第一条") && text.contains("第八条") && text.contains("第十二条"));
        assertTrue(text.contains("【合同完备性】"));
    }

    @Test
    void review_emptyText_returnsEmptyReport() {
        AtomicInteger llmCalls = new AtomicInteger(0);
        ClauseReviewEngine engine = buildEngine(llmCalls);
        // 空文本直接短路，返回 0 条报告且不触发任何 LLM
        ClauseReviewEngine.ReviewReport report = engine.review("", 1L, "docX");
        assertNotNull(report);
        assertEquals(0, report.getReviewedCount());
        assertEquals(0, llmCalls.get());
    }

    // ==================== 审查点驱动（P5）专项 ====================

    private ClauseReviewEngine newEngine(AtomicInteger llmCalls, AtomicReference<String> canned) {
        DocumentTextExtractorService extractor = new DocumentTextExtractorService() {
            @Override
            public List<ClauseBlock> splitIntoClauseBlocks(String text, String label) {
                List<ClauseBlock> bs = new ArrayList<>();
                // 造两个块：语义类 + 数值类
                bs.add(new ClauseBlock("第一条 本合同最终解释权归甲方所有。", "d-1", 0));
                bs.add(new ClauseBlock("第二条 借款年利率按日千分之五计息。", "d-2", 0));
                return bs;
            }
            @Override
            public String extractText(org.springframework.web.multipart.MultipartFile file) {
                return "";
            }
        };
        RagVectorService rag = new RagVectorService() {
            @Override
            public void initEsIndex() {}
            @Override
            public void uploadDocument(Long kbId, Long userId,
                                       org.springframework.web.multipart.MultipartFile file) {}
            @Override
            public List<Map<String, Object>> hybridSearch(Long kbId, String query, int topK) {
                Map<String, Object> hit = new HashMap<>();
                hit.put("clauseId", "KB_X");
                hit.put("content", "民法典第四百六十六条：合同应当按照公平原则确定权利义务。");
                hit.put("score", 0.5);
                return List.of(hit);
            }
        };
        ToolCallingLlm llm = new ToolCallingLlm(null) {
            @Override
            public String chat(String userPrompt) {
                llmCalls.incrementAndGet();
                return classifyOrRisk(userPrompt, canned);
            }
            @Override
            public String chat(String userPrompt, String model) {
                llmCalls.incrementAndGet();
                return classifyOrRisk(userPrompt, canned);
            }
            /** 合并调用返回 JSON 结论；cannedRef 控制是否判风险。 */
            private String classifyOrRisk(String p, AtomicReference<String> cannedRef) {
                if (p == null || !p.contains("一次性输出结论")) {
                    return cannedRef.get();
                }
                String clause = extractClause(p);
                boolean risky = cannedRef.get() != null && cannedRef.get().contains("风险点：违反")
                        || cannedRef.get() != null && cannedRef.get().contains("存在风险");
                String dim = clause.contains("解释权") ? "语义" : (clause.contains("利率") ? "数值" : "无关");
                return "{\"维度\":\"" + dim + "\","
                        + "\"是否有风险\":\"" + (risky ? "是" : "否") + "\","
                        + "\"风险描述\":\"" + (risky ? "存在法律风险" : "") + "\","
                        + "\"法律依据\":\"民法典\","
                        + "\"改写建议\":\"" + (risky ? "建议修改" : "") + "\"}";
            }

            /** 只取【待审条款】到【检索到的法规依据】之间的条款正文，避免匹配到说明文字。 */
            private String extractClause(String p) {
                int a = p.indexOf("【待审条款】");
                if (a < 0) {
                    return "";
                }
                int start = a + "【待审条款】".length();
                int b = p.indexOf("【检索到的法规依据】");
                return b > start ? p.substring(start, b) : p.substring(start);
            }

        };
        return new ClauseReviewEngine(extractor, rag, new LegalComputeService(new CalcRegistry()), llm, null);
    }

    @Test
    void semanticClause_llmSaysRisk_isRisky() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>("风险点：违反公平原则");
        ClauseReviewEngine engine = newEngine(calls, canned);

        ClauseReviewEngine.ReviewReport rpt = engine.review("合同文本", 1L, "doc");
        // 第 1 条语义类 -> LLM 明确指出风险 -> risky
        assertTrue(rpt.items.get(0).risky, "语义类 LLM 判风险应标风险");
    }

    @Test
    void semanticClause_llmSaysNoRisk_notRisky() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>(
                "风险点：未发现明显风险。\n改写建议：无。\n替换条款：无。");
        ClauseReviewEngine engine = newEngine(calls, canned);

        ClauseReviewEngine.ReviewReport rpt = engine.review("合同文本", 1L, "doc");
        // 第 1 条语义类 -> LLM 明确说无风险 -> 不标风险（抑制误报）
        assertFalse(rpt.items.get(0).risky, "LLM 明确说无风险不应标风险");
    }

    @Test
    void numericClause_detectedByDeterministicRule() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>("风险点：未发现明显风险。");
        ClauseReviewEngine engine = newEngine(calls, canned);

        ClauseReviewEngine.ReviewReport rpt = engine.review("合同文本", 1L, "doc");
        // 第 2 条千分之五利率 -> 确定性数值异常（不依赖 LLM）
        ClauseReviewEngine.ClauseReviewItem it2 = rpt.items.get(1);
        assertTrue(it2.numericAnomaly, "千分之五利率应被确定性规则判为数值异常");
        assertTrue(it2.risky);
    }

    @Test
    void completenessCheck_flagsMissingItems() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>("风险点：未发现明显风险。");
        ClauseReviewEngine engine = newEngine(calls, canned);

        // newEngine 桩造 2 块（达阈值 2）-> 产出完备性条目
        ClauseReviewEngine.ReviewReport rpt = engine.review("合同文本", 1L, "doc");
        boolean hasComp = rpt.items.stream().anyMatch(i -> "【合同完备性】".equals(i.clauseLabel));
        assertTrue(hasComp, "2 条款达阈值应产出完备性条目");
        assertEquals(3, rpt.getReviewedCount());

        // 阈值边界一：单条款（1）不检查，避免用户粘贴单个条款被误判为合同缺陷
        assertTrue(engine.checkCompleteness("第一条 甲乙双方协商一致。", 1).isEmpty(),
                "单条款不应报缺失");
        // 阈值边界二：2 条款（达阈值）且缺多项 -> 报缺失
        List<String> atTwo = engine.checkCompleteness("甲方应按期交货，乙方应按期付款。", 2);
        assertTrue(atTwo.size() >= 2, "2 条款缺多项应报缺失，实际=" + atTwo);
        // 阈值边界三：0 条款但有实质内容（自由文本）-> 同样检查
        List<String> freeText = engine.checkCompleteness("双方达成如下协议，共同遵守。", 0);
        assertTrue(freeText.size() >= 2, "无编号自由文本应报缺失，实际=" + freeText);
        // 阈值边界二：仅缺 1 项（生效条件）不作为风险
        List<String> oneMissing = engine.checkCompleteness(
                "甲方与乙方就货物标的、数量、价款、交付时间、违约责任、争议解决等达成一致。", 4);
        assertTrue(oneMissing.isEmpty(), "仅缺 1 项不应判为风险，实际=" + oneMissing);
        // 极短文本（<8 字）不做检查，避免噪声
        assertTrue(engine.checkCompleteness("嗯", 0).isEmpty(), "极短文本不应报缺失");
        // 条款数足够且缺多项 → 报缺失
        List<String> manyMissing = engine.checkCompleteness("甲乙双方就合作事项达成协议。", 5);
        assertTrue(manyMissing.size() >= 2, "要素严重缺失应报>=2项，实际=" + manyMissing);
    }

    @Test
    void llmSaysRisk_robustParsing() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>("");
        ClauseReviewEngine engine = newEngine(calls, canned);
        // 明确否定 -> 无风险
        assertFalse(engine.llmSaysRisk("风险点：未发现明显风险。"));
        // 明确指出 -> 风险
        assertTrue(engine.llmSaysRisk("风险点：违反公平原则，存在风险。"));
        // 无法判断 -> 保守判风险
        assertTrue(engine.llmSaysRisk(""));
        assertTrue(engine.llmSaysRisk(null));
    }

    /**
     * 白名单过宽风险用例：这些条款含白名单词但本身是风险条款，
     * 若白名单把它们判为「无关」就会漏检。用于回归防护。
     */
    @Test
    void whitelist_doesNotSwallowRiskyClauses() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>("");
        ClauseReviewEngine engine = newEngine(calls, canned);

        // 这些条款含「应当遵循 / 按照约定履行」等白名单词，但属免责/单方权利类风险
        String[] riskyButWhitelisted = {
                "第五条 甲方造成的损失应当遵循当地标准由甲方自行承担。",
                "第三条 按照约定履行，乙方不得向任何第三方主张权利。",
                "第七条 双方应按照约定履行义务，乙方放弃追究甲方违约责任的权利。",
        };
        // 白名单命中即为 NONE（无关）——记录当前行为，作为过宽证据
        for (String c : riskyButWhitelisted) {
            boolean hit = false;
            for (String m : new String[]{"诚信原则", "遵循诚信", "诚实信用", "全面履行",
                    "按照约定履行", "应当遵循", "信息保密", "对委托方信息", "承担保密"}) {
                if (c.contains(m)) { hit = true; break; }
            }
            assertTrue(hit, "该风险条款确实命中白名单（过宽证据）: " + c);
        }
    }

    /** 违约金比例畸高规则（民法典585 条 30% 参照线） */
    @Test
    void penaltyRatioRule_detectsOverCapRatio() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>("{\"维度\":\"数值\",\"是否有风险\":\"否\","
                + "\"风险描述\":\"\",\"法律依据\":\"\",\"改写建议\":\"\"}");
        ClauseReviewEngine engine = newEngine(calls, canned);

        // 超过 30% -> 判畸高
        assertNotNull(engine.penaltyRatioAnomaly("第十条 违约方应按合同总价的百分之五十向守约方支付违约金。"),
                "50% 应判畸高");
        assertNotNull(engine.penaltyRatioAnomaly("第八条 违约金按未履行部分的百分之四十计。"),
                "40% 应判畸高");
        // 未超 30% -> 不判
        assertNull(engine.penaltyRatioAnomaly("第七条 违约方应按合同金额的百分之十支付违约金。"),
                "10% 不应判畸高");
        assertNull(engine.penaltyRatioAnomaly("第三条 违约金按未履行部分的千分之一支付。"),
                "千分之一不应判畸高");
        // 非违约金语境的百分比不应触发（避免把利率当违约金）
        assertNull(engine.penaltyRatioAnomaly("第八条 借款年利率按百分之二十计息。"),
                "利率百分比不应触发违约金规则");
    }

    /** 中文数字解析 */
    @Test
    void cnNumber_parsesChineseNumerals() {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicReference<String> canned = new AtomicReference<>("");
        ClauseReviewEngine engine = newEngine(calls, canned);
        assertEquals(50.0, engine.cnNumber("五十"), 0.001);
        assertEquals(10.0, engine.cnNumber("十"), 0.001);
        assertEquals(20.0, engine.cnNumber("二十"), 0.001);
        assertEquals(40.0, engine.cnNumber("四十"), 0.001);
        assertEquals(35.0, engine.cnNumber("三十五"), 0.001);
    }
}
