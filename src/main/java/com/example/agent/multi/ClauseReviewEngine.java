package com.example.agent.multi;

import com.example.calc.CalcType;
import com.example.calc.ExtractedParams;
import com.example.calc.LegalComputeService;
import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import com.example.service.RagVectorService;
import com.example.util.ContractParamParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合同审查引擎（Agent 内专用，审查逻辑只此一份）。
 *
 * <p>职责：把一段合同文本切成条款，逐条做「法规检索 + 数值校验 + 风险判定」，
 * 仅对命中风险/异常的条款调用一次 LLM 产出改写建议（含替换条款文本），最后聚合成报告。
 * 纯复用既有能力（DocumentTextExtractorService 切分 / RagVectorService 检索 /
 * LegalComputeService 数值校验 / ToolCallingLlm 生成），不触碰 Agent 外的任何模块。</p>
 *
 * <p>风险控制：为控制 LLM 调用量与成本，逐条先走确定性判定（数值异常、低相关），
 * 只有确定性判定命中「风险」的条款才调 LLM——正常条款直接跳过，避免对每个条款都发一次生成请求。</p>
 */
@Slf4j
@Service
public class ClauseReviewEngine {

    private final DocumentTextExtractorService extractor;
    private final RagVectorService ragVectorService;
    private final LegalComputeService legalComputeService;
    private final ToolCallingLlm toolCallingLlm;

    /** 每条款检索召回条数 */
    private static final int REVIEW_TOP_K = 5;
    /** RRF 融合分低于此值视为「低相关 / 无管辖依据」 */
    private static final double LOW_SCORE = 0.03;
    /**
     * 借贷利率法定上限（4×LPR ≈ 13.8%/年）换算的「日千分比」上限 ≈ 0.378‰。
     * 与 PenaltyCalculator 的 4×LPR 封顶口径一致：13.8% ÷ 365 × 1000 ≈ 0.378。
     * 条款显式给出的日利率超过此值即判为数值异常。
     */
    private static final double LOAN_DAILY_CAP_PER_MILLE = 0.378;

    private static final Pattern CLAUSE_NO =
            Pattern.compile("^(第[零一二三四五六七八九十百千0-9]+条)");

    public ClauseReviewEngine(DocumentTextExtractorService extractor,
                              RagVectorService ragVectorService,
                              LegalComputeService legalComputeService,
                              ToolCallingLlm toolCallingLlm) {
        this.extractor = extractor;
        this.ragVectorService = ragVectorService;
        this.legalComputeService = legalComputeService;
        this.toolCallingLlm = toolCallingLlm;
    }

    /**
     * 审查整段合同文本。
     *
     * @param contractText 合同纯文本
     * @param kbId         知识库 ID（检索管辖条款用）
     * @param sourceLabel  来源标识（切分 canonicalId 前缀，通常传 "doc" + docId）
     * @return 审查报告（含结构化条款结果与纯文本报告）
     */
    public ReviewReport review(String contractText, Long kbId, String sourceLabel) {
        List<ClauseReviewItem> items = new ArrayList<>();
        // 空/空白文本直接短路：没有可审条款，返回空报告（避免把噪声当条款送检、白耗 LLM 调用）。
        if (contractText == null || contractText.isBlank()) {
            log.warn("审查引擎：合同文本为空，直接返回空报告");
            return new ReviewReport(items);
        }
        List<ClauseBlock> blocks = extractor.splitIntoClauseBlocks(contractText, sourceLabel);
        if (blocks == null || blocks.isEmpty()) {
            log.warn("审查引擎：切分结果为空，合同可能无「第X条」结构");
            return new ReviewReport(items);
        }
        for (ClauseBlock block : blocks) {
            try {
                items.add(reviewClause(block, kbId));
            } catch (Exception e) {
                log.error("条款审查异常: {}", block.getCanonicalId(), e);
                items.add(new ClauseReviewItem(
                        clauseLabelOf(block), block.getContent(),
                        List.of(), "（审查异常）", false, false, false,
                        "（审查异常，请人工复核）", null, null));
            }
        }
        return new ReviewReport(items);
    }

    private ClauseReviewItem reviewClause(ClauseBlock block, Long kbId) {
        String clauseText = block.getContent();
        String clauseLabel = clauseLabelOf(block);

        // 1) 检索管辖条款
        List<Map<String, Object>> hits = safeHybridSearch(kbId, clauseText);

        // 2) 数值校验（复用 Phase 0 抽取的共享内核）
        ContractParamParser.Params pp = ContractParamParser.parse(clauseText);
        ExtractedParams ep = new ExtractedParams();
        ep.principal = pp.principal;
        ep.dailyRatePerMille = pp.ratePerMille;
        ep.days = pp.days;
        ep.forceMajeure = pp.forceMajeure;
        ep.question = clauseText;
        LegalComputeService.ComputeResult cr = legalComputeService.compute(ep, null, clauseText);

        // 3) 确定性风险判定
        boolean numericAnomaly = isNumericAnomaly(pp, cr);
        boolean lowRelevance = isLowRelevance(hits);
        boolean risky = numericAnomaly || lowRelevance;

        // 4) 仅风险条款调一次 LLM 生成改写建议
        String riskPoint = null;
        String rewriteSuggestion = null;
        String clauseReplacement = null;
        if (risky) {
            String llmResp = callLlmForRewrite(clauseText, hits, cr);
            riskPoint = extractSection(llmResp, "风险点");
            rewriteSuggestion = extractSection(llmResp, "改写建议");
            clauseReplacement = extractSection(llmResp, "替换条款");
        }
        return new ClauseReviewItem(clauseLabel, clauseText, hits,
                cr != null ? cr.handoff : null,
                risky, numericAnomaly, lowRelevance,
                riskPoint, rewriteSuggestion, clauseReplacement);
    }

    // ---- 风险判定 ----

    private boolean isLowRelevance(List<Map<String, Object>> hits) {
        return hits.isEmpty() || topScore(hits) < LOW_SCORE;
    }

    private boolean isNumericAnomaly(ContractParamParser.Params pp,
                                     LegalComputeService.ComputeResult cr) {
        // 借贷利率超过 4×LPR 法定上限：确定性强、无需 LLM
        return cr != null
                && cr.calcType == CalcType.LOAN_INTEREST
                && pp.rateSpecified
                && pp.ratePerMille != null
                && pp.ratePerMille > LOAN_DAILY_CAP_PER_MILLE;
    }

    private double topScore(List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0.0;
        }
        Object s = hits.get(0).get("score");
        return (s instanceof Number) ? ((Number) s).doubleValue() : 0.0;
    }

    // ---- LLM 改写 ----

    private String callLlmForRewrite(String clauseText,
                                     List<Map<String, Object>> hits,
                                     LegalComputeService.ComputeResult cr) {
        StringBuilder basis = new StringBuilder();
        if (hits != null) {
            for (int i = 0; i < Math.min(hits.size(), 3); i++) {
                Map<String, Object> h = hits.get(i);
                String cid = String.valueOf(h.get("clauseId"));
                String content = String.valueOf(h.get("content"));
                basis.append("- [").append(cid).append("] ").append(content).append("\n");
            }
        }
        String numeric = (cr != null && cr.handoff != null) ? cr.handoff : "（无数值计算项）";
        String prompt = "你是资深合同审查律师。请基于以下信息审查该合同条款并给出修改建议。\n\n"
                + "【待审条款】\n" + clauseText + "\n\n"
                + "【可引用的法规依据（知识库检索结果）】\n"
                + (basis.length() > 0 ? basis : "（未检索到相关法规）") + "\n"
                + "【数值校验】" + numeric + "\n\n"
                + "请严格按以下三段格式输出（不要加额外说明、不要使用 markdown 代码块）：\n"
                + "风险点：<该条款存在的法律风险，1-2句>\n"
                + "改写建议：<针对该风险的修改思路，1-2句>\n"
                + "替换条款：<可直接替换的原条款改写文本>";
        try {
            return toolCallingLlm.chat(prompt);
        } catch (Exception e) {
            log.warn("审查改写 LLM 调用失败", e);
            return null;
        }
    }

    /** 按「段标题：」行解析 LLM 输出，提取对应段落；解析失败回退为全文。 */
    private String extractSection(String resp, String key) {
        if (resp == null || resp.isBlank()) {
            return "（生成失败）";
        }
        String[] lines = resp.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.matches("^(风险点|改写建议|替换条款)\\s*[:：].*")) {
                if (line.startsWith(key)) {
                    inSection = true;
                    sb.append(line.replaceFirst("^(风险点|改写建议|替换条款)\\s*[:：]", "").trim()).append("\n");
                } else if (inSection) {
                    break;
                }
            } else if (inSection) {
                sb.append(line).append("\n");
            }
        }
        String r = sb.toString().trim();
        return r.isEmpty() ? resp.trim() : r;
    }

    // ---- 工具 ----

    private List<Map<String, Object>> safeHybridSearch(Long kbId, String query) {
        try {
            List<Map<String, Object>> hits = ragVectorService.hybridSearch(kbId, query, REVIEW_TOP_K);
            return hits != null ? hits : List.of();
        } catch (Exception e) {
            log.warn("审查引擎检索失败（按低相关处理）: {}", e.getMessage());
            return List.of();
        }
    }

    private String clauseLabelOf(ClauseBlock block) {
        String c = block.getContent();
        if (c != null) {
            Matcher m = CLAUSE_NO.matcher(c.trim());
            if (m.find()) {
                return m.group(1);
            }
        }
        return block.getCanonicalId();
    }

    // ===================== 结果载体 =====================

    /** 单条款审查结果。 */
    public static class ClauseReviewItem {
        public final String clauseLabel;
        public final String clauseText;
        public final List<Map<String, Object>> legalBasis;
        public final String numericValidation;
        public final boolean risky;
        public final boolean numericAnomaly;
        public final boolean lowRelevance;
        public final String riskPoint;
        public final String rewriteSuggestion;
        public final String clauseReplacement;

        public ClauseReviewItem(String clauseLabel, String clauseText,
                                List<Map<String, Object>> legalBasis, String numericValidation,
                                boolean risky, boolean numericAnomaly, boolean lowRelevance,
                                String riskPoint, String rewriteSuggestion, String clauseReplacement) {
            this.clauseLabel = clauseLabel;
            this.clauseText = clauseText;
            this.legalBasis = legalBasis;
            this.numericValidation = numericValidation;
            this.risky = risky;
            this.numericAnomaly = numericAnomaly;
            this.lowRelevance = lowRelevance;
            this.riskPoint = riskPoint;
            this.rewriteSuggestion = rewriteSuggestion;
            this.clauseReplacement = clauseReplacement;
        }

        /** 转成 Map，供 MultiAgentState 存储（P3/P4 使用）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("clauseLabel", clauseLabel);
            m.put("clauseText", clauseText);
            m.put("numericValidation", numericValidation);
            m.put("risky", risky);
            m.put("riskPoint", riskPoint);
            m.put("rewriteSuggestion", rewriteSuggestion);
            m.put("clauseReplacement", clauseReplacement);
            return m;
        }
    }

    /** 整份合同审查报告（结构化 items + 纯文本）。 */
    public static class ReviewReport {
        public final List<ClauseReviewItem> items;

        public ReviewReport(List<ClauseReviewItem> items) {
            this.items = items;
        }

        public int getReviewedCount() {
            return items.size();
        }

        public int getRiskCount() {
            return (int) items.stream().filter(i -> i.risky).count();
        }

        /** 纯文本审查报告。 */
        public String toPlainText() {
            StringBuilder sb = new StringBuilder();
            sb.append("合同审查报告\n");
            sb.append("（共审查 ").append(items.size()).append(" 条，发现风险 ").append(getRiskCount()).append(" 条）\n");
            sb.append("================================\n");
            for (int i = 0; i < items.size(); i++) {
                ClauseReviewItem it = items.get(i);
                sb.append("\n").append(i + 1).append(". ").append(it.clauseLabel).append("\n");
                sb.append("【原文】").append(it.clauseText).append("\n");
                if (it.legalBasis != null && !it.legalBasis.isEmpty()) {
                    sb.append("【法规依据】");
                    for (Map<String, Object> h : it.legalBasis) {
                        sb.append(String.valueOf(h.get("clauseId"))).append("; ");
                    }
                    sb.append("\n");
                }
                if (it.numericValidation != null) {
                    sb.append("【数值校验】").append(it.numericValidation).append("\n");
                }
                if (it.risky) {
                    sb.append("【风险点】").append(it.riskPoint != null ? it.riskPoint : "（见下方建议）").append("\n");
                    sb.append("【改写建议】").append(it.rewriteSuggestion != null ? it.rewriteSuggestion : "（无）").append("\n");
                    sb.append("【建议替换条款】").append(it.clauseReplacement != null ? it.clauseReplacement : "（无）").append("\n");
                } else {
                    sb.append("【结论】未发现明显风险。\n");
                }
            }
            return sb.toString();
        }
    }
}
