package com.example.agent.multi;

import com.example.calc.CalcType;
import com.example.calc.ExtractedParams;
import com.example.calc.LegalComputeService;
import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import com.example.service.RagVectorService;
import com.example.util.ContractParamParser;
import com.example.util.PenaltyCalculator;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 * <p>风险控制：审查点驱动（P5）。每条款先做一次轻量分类（便宜模型）判定其审查维度，
 * 再按维度分发到对应审查路径——数值类走确定性校验、语义类走 LLM 风险判断、
 * 格式与无关类跳过。另有一条并行的合同级完备性检查，覆盖「缺什么」这类
 * 整份合同属性（单条款分类无法得出）。分类与风险判定合并为一次 LLM 调用（省一次网络往返），
 * 结果按条款文本 hash 缓存复用；数值校验为纯计算不缓存。</p>
 */
@Slf4j
@Service
public class ClauseReviewEngine {

    private final DocumentTextExtractorService extractor;
    private final RagVectorService ragVectorService;
    private final LegalComputeService legalComputeService;
    private final ToolCallingLlm toolCallingLlm;
    private final StringRedisTemplate redis;

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

    // ==================== 审查点驱动（P5） ====================
    /** 审查点分类模型（轻量分类，便宜小模型即可） */
    @Value("${dashscope.api.classify-model:qwen-flash}")
    private String classifyModel;
    /** 语义风险判断模型（需较强推理能力） */
    @Value("${dashscope.api.risk-model:qwen-flash}")
    private String riskModel;

    /** 审查维度：数值类走确定性校验，语义类走 LLM 判断，格式/无关类跳过 */
    public enum ReviewPoint {
        /** 涉及金额/利率/违约金等数值，走 LegalComputeService 确定性校验 */
        NUMERIC("数值"),
        /** 权利义务失衡、免责、解释权等需语义判断，走 LLM 风险判断 */
        SEMANTIC("语义"),
        /** 格式规范类，规则检查 */
        FORMAT("格式"),
        /** 无审查要点 */
        NONE("无关");

        public final String label;

        ReviewPoint(String label) {
            this.label = label;
        }

        /** 把 LLM 输出的类别名解析为枚举；无法识别时保守判为语义类（宁可多审不可漏审）。 */
        public static ReviewPoint parse(String raw) {
            if (raw == null) {
                return SEMANTIC;
            }
            String s = raw.trim();
            for (ReviewPoint rp : values()) {
                if (s.contains(rp.label)) {
                    return rp;
                }
            }
            return SEMANTIC;
        }
    }

    /**
     * 合同级完备性检查项：缺失即标记风险。
     * 这是与逐条款审查并行的第二条逻辑——「缺什么」是整份合同的属性，
     * 无法由单条款的审查点分类得出。
     */
    private static final String[][] COMPLETENESS_ITEMS = {
            {"合同主体", "甲方|乙方|双方|卖方|买方|供方|需方|承租人|出租人|借款人|出借人|委托方|受托方|服务方|客户|公司|个人"},
            {"合同标的", "标的|货物|商品|产品|服务|技术|设备|材料|作品|劳务|项目"},
            {"数量", "数量|件数|台|套|份|批|米|平方米|公斤|吨|数量为"},
            {"交付时间", "交付时间|交货期|履行期限|期限|之日|日内|工作日|前交付|完成时间"},
            {"价款", "价款|价格|报酬|费用|租金|总价|元|万元|支付|付款"},
            {"违约责任", "违约|违约金|违约责任|赔偿|定金|违约金"},
            {"争议解决", "争议|仲裁|诉讼|起诉|管辖|人民法院|仲裁委员会|和解|调解"},
            {"生效条件", "生效|签字|盖章|签署|成立|之日起"},
    };

    /**
     * 触发完备性检查的最小条款数。
     *
     * <p>此前取 3，导致 2 条款的合同（如「交付+付款」两条）完全不做完整性检查，
     * 实测缺失类检出仅 40%。现取 2：单条款片段仍不检查（避免把用户粘贴的
     * 单个条款误判为合同缺陷），2 条及以上才判定。</p>
     */
    private static final int COMPLETENESS_MIN_CLAUSES = 2;

    /**
     * 触发完备性风险提示的最少缺失项数：缺 1 项通常属正常书写差异，
     * 缺 2 项以上才认为合同要素确有缺陷。
     */
    private static final int COMPLETENESS_MIN_MISSING = 2;

    /** 条款审查结果缓存 key 前缀（缓存「分类+风险判定」，不含数值校验） */
    private static final String REVIEW_CACHE_PREFIX = "review:clause:";
    /** 缓存 TTL：7 天 */
    private static final Duration REVIEW_CACHE_TTL = Duration.ofDays(7);

    /**
     * 违约金比例畸高阈值（占合同/未履行部分的比例）。
     * 依据《民法典》第 585 条「约定的违约金过分高于造成的损失」，
     * 司法实践通常以超过损失 30% 作为「过分高于」的参照。
     */
    private static final double PENALTY_RATIO_CAP = 0.30;

    /** 抽取「百分之X」比例（返回 0~1 之间的比例值，未抽到返回 null）。 */
    private static final Pattern PERCENT_PATTERN =
            Pattern.compile("百分之([零一二三四五六七八九十百0-9]+)");

    private static final Pattern CLAUSE_NO =
            Pattern.compile("^(第[零一二三四五六七八九十百千0-9]+条)");

    public ClauseReviewEngine(DocumentTextExtractorService extractor,
                              RagVectorService ragVectorService,
                              LegalComputeService legalComputeService,
                              ToolCallingLlm toolCallingLlm,
                              StringRedisTemplate redis) {
        this.extractor = extractor;
        this.ragVectorService = ragVectorService;
        this.legalComputeService = legalComputeService;
        this.toolCallingLlm = toolCallingLlm;
        this.redis = redis;
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
        // 合同级完备性检查（与逐条款审查并行）：缺失项以独立条目计入报告
        List<String> missing = checkCompleteness(contractText, countReviewableClauses(blocks));
        if (!missing.isEmpty()) {
            items.add(new ClauseReviewItem(
                    "【合同完备性】", "整份合同要素检查", List.of(), null,
                    true, false, false,
                    "缺少必要要素：" + String.join("、", missing),
                    "建议补充约定：" + String.join("、", missing),
                    null));
        }
        return new ReviewReport(items);
    }

    private ClauseReviewItem reviewClause(ClauseBlock block, Long kbId) {
        String clauseText = block.getContent();
        String clauseLabel = clauseLabelOf(block);

        // 0) 引言块（第一条款之前的引言/提问文本）不是合同条款，不参与审查
        if (isIntroBlock(block)) {
            return new ClauseReviewItem(clauseLabel, clauseText, List.of(), null,
                    false, false, false, null, null, null);
        }

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

        // 3) 分类 + 风险判定合并为一次 LLM 调用（命中缓存则直接复用）
        ClauseJudgement j = judgeClause(clauseText, hits, cr);

        // 4) 按审查维度分发：数值类以确定性校验为准，其余维度以 LLM 判定为准
        boolean lowRelevance = isLowRelevance(hits);
        Double penaltyRatio = penaltyRatioAnomaly(clauseText);
        boolean numericAnomaly = isNumericAnomaly(pp, cr, clauseText) || penaltyRatio != null;
        boolean risky;
        String riskPoint = null;
        String rewriteSuggestion = null;
        String clauseReplacement = null;

        if (j.point() == ReviewPoint.NUMERIC) {
            // 数值类：**只以确定性校验为准**，LLM 不参与风险判定。
            // 原因：小模型对「千分之一 vs 百分之三十」这类比例换算不可靠，
            // 实测会把合法偏低的违约金误判为过高；数值结论必须由规则给出。
            risky = numericAnomaly;
            if (risky) {
                if (penaltyRatio != null) {
                    riskPoint = String.format("违约金比例畸高：约定为%.0f%%，超过《民法典》第585条参照的30%%上限",
                            penaltyRatio * 100);
                } else {
                    riskPoint = j.riskPoint() != null && !j.riskPoint().isBlank()
                            ? j.riskPoint() : "数值超出法定/合理区间";
                }
                rewriteSuggestion = j.advice();
            }
        } else if (j.point() == ReviewPoint.NONE) {
            // 无关类：不做语义风险判断，仅保留确定性数值校验
            risky = numericAnomaly;
        } else {
            // 语义类 / 格式类：以 LLM 判定为主；无检索依据时直接视为风险
            risky = j.risky() || lowRelevance;
            if (risky) {
                riskPoint = j.riskPoint();
                rewriteSuggestion = j.advice();
                clauseReplacement = j.advice();
            }
        }

        return new ClauseReviewItem(clauseLabel, clauseText, hits,
                cr != null ? cr.handoff : null,
                risky, numericAnomaly, lowRelevance,
                riskPoint, rewriteSuggestion, clauseReplacement);
    }

    /**
     * 审查点分类：白名单优先（省一次调用并防误判），否则用轻量模型做短 prompt 分类。
     */
    /**
     * 条款审查的 LLM 判定结果（分类与风险判定合并为一次调用产出）。
     *
     * @param point      审查维度
     * @param risky      是否存在法律风险
     * @param riskPoint  风险描述
     * @param basis      法律依据
     * @param advice     改写建议
     */
    public record ClauseJudgement(ReviewPoint point, boolean risky, String riskPoint,
                                  String basis, String advice) {
    }

    /**
     * 分类 + 风险判定合并为一次 LLM 调用：一次网络往返同时产出审查维度、
     * 是否风险、风险描述、法律依据与改写建议，省掉先分类再判定的第二次调用。
     * 结果按条款文本 hash 缓存（TTL 7 天），同文本重复审查直接复用。
     */
    ClauseJudgement judgeClause(String clauseText, List<Map<String, Object>> hits,
                                LegalComputeService.ComputeResult cr) {
        String cacheKey = REVIEW_CACHE_PREFIX + sha256Hex(clauseText);
        ClauseJudgement cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        StringBuilder basis = new StringBuilder();
        if (hits != null) {
            for (Map<String, Object> h : hits) {
                if (basis.length() > 0) {
                    basis.append("; ");
                }
                basis.append(h.get("clauseId"));
            }
        }
        String NL = "\n";
        String prompt = "你是合同审查律师。判断下面条款并一次性输出结论。" + NL
                + "严格只输出如下 JSON，不要任何额外文字：" + NL
                + "{\"维度\":\"数值|语义|格式|无关\","
                + "\"是否有风险\":\"是|否\","
                + "\"风险描述\":\"一句话，无风险填空字符串\","
                + "\"法律依据\":\"引用到的法规条款，无则填空字符串\","
                + "\"改写建议\":\"一句话，无风险填空字符串\"}" + NL
                + "维度含义：数值=涉及金额/利率/违约金计算；语义=权利义务失衡/免责/解释权/单方权利；"
                + "格式=日期编号表述规范；无关=无审查要点。" + NL
                + "【待审条款】" + clauseText + NL
                + "【检索到的法规依据】" + (basis.length() == 0 ? "（无）" : basis) + NL
                + (cr != null && cr.handoff != null ? "【数值校验参考】" + cr.handoff + NL : "")
                + "判定标准（务必遵守，避免误报）：" + NL
                + "1) 以下情形**不算风险**，属正常商业条款或法定补充，不判："
                + "「按约定」「按约定时间/费用/方式」；「有管辖权的人民法院」；"
                + "「收到货物后N日内」「N个工作日内」等已含起算点与期限的表述；"
                + "「任何一方违约均应承担相应违约责任」等对等约定。" + NL
                + "2) 只在存在**明确的不对等、免除、限责、单方解释权、显失公平**时判风险。" + NL
                + "3) 仅「约定期限」这类**完全无指向**的表述才算表述模糊；"
                + "已写明具体日期或「N日内」的，不算模糊。" + NL
                + "4) 合同法允许对未约定事项作法定补充（买卖合同交付时间/地点可按交易习惯确定），"
                + "不得因未写明这些细节而判风险。";

        String raw = toolCallingLlm.chat(prompt, riskModel);
        ClauseJudgement j = parseJudgement(raw);
        writeCache(cacheKey, j);
        return j;
    }

    /** 解析合并调用的 JSON 输出；解析失败保守按语义类 + 风险处理。 */
    private ClauseJudgement parseJudgement(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ClauseJudgement(ReviewPoint.SEMANTIC, true, "（模型未返回结论）", "", "");
        }
        String t = raw.trim();
        int first = t.indexOf('{');
        int last = t.lastIndexOf('}');
        if (first >= 0 && last > first) {
            t = t.substring(first, last + 1);
        }
        try {
            JSONObject o = JSON.parseObject(t);
            if (o != null) {
                ReviewPoint point = ReviewPoint.parse(o.getString("维度"));
                String riskFlag = o.getString("是否有风险");
                boolean risky = riskFlag != null && riskFlag.startsWith("是");
                // 维度与风险结论一致性兜底：语义类必须给出风险描述才算有风险
                String riskPoint = o.getString("风险描述");
                if (risky && (riskPoint == null || riskPoint.isBlank())) {
                    riskPoint = "（模型未给出风险描述）";
                }
                return new ClauseJudgement(point, risky,
                        riskPoint, o.getString("法律依据"), o.getString("改写建议"));
            }
        } catch (Exception e) {
            log.warn("合并审查判定解析失败，回退语义类：{}", e.getMessage());
        }
        return new ClauseJudgement(ReviewPoint.SEMANTIC, true, "（结论解析失败）", "", "");
    }

    /** 条款文本 SHA-256，作为缓存 key。 */
    private String sha256Hex(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 读缓存；Redis 不可用时静默降级为未命中。 */
    private ClauseJudgement readCache(String key) {
        if (redis == null) {
            return null;
        }
        try {
            String v = redis.opsForValue().get(key);
            if (v == null || v.isBlank()) {
                return null;
            }
            JSONObject o = JSON.parseObject(v);
            return new ClauseJudgement(
                    ReviewPoint.valueOf(o.getString("point")),
                    o.getBooleanValue("risky"),
                    o.getString("riskPoint"),
                    o.getString("basis"),
                    o.getString("advice"));
        } catch (Exception e) {
            log.debug("读取审查缓存失败，降级为未命中: {}", e.getMessage());
            return null;
        }
    }

    /** 写缓存；失败不影响主流程。 */
    private void writeCache(String key, ClauseJudgement j) {
        if (redis == null || j == null) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("point", j.point().name());
            o.put("risky", j.risky());
            o.put("riskPoint", j.riskPoint());
            o.put("basis", j.basis());
            o.put("advice", j.advice());
            redis.opsForValue().set(key, o.toJSONString(), REVIEW_CACHE_TTL);
        } catch (Exception e) {
            log.debug("写入审查缓存失败: {}", e.getMessage());
        }
    }

    ReviewPoint classifyReviewPoint(String clauseText) {
        String prompt = "判断条款属于哪类审查维度，只输出类别名之一（不要解释）：\n"
                + "数值=涉及金额/利率/违约金/赔偿金计算\n"
                + "语义=权利义务失衡/免责/解释权/单方权利/义务不对等\n"
                + "格式=日期/编号/表述规范\n"
                + "无关=无审查要点\n\n"
                + "条款：" + clauseText;
        String raw = toolCallingLlm.chat(prompt, classifyModel);
        return ReviewPoint.parse(raw);
    }

    /**
     * 语义风险判定：LLM 必须明确指出风险才算风险。
     * 明确说无风险/未发现风险则判无风险；无法判断时保守判为风险（宁可误报不可漏报）。
     */
    boolean llmSaysRisk(String llmResp) {
        if (llmResp == null || llmResp.isBlank()) {
            return true;
        }
        String s = llmResp.trim();
        // 明确否定 → 无风险
        for (String neg : new String[]{"未发现明显风险", "无明显风险", "不存在风险", "没有风险", "无风险"}) {
            if (s.contains(neg)) {
                return false;
            }
        }
        // 明确指出风险点 → 风险
        return s.contains("风险点") || s.contains("违反") || s.contains("不合理")
                || s.contains("显失公平") || s.contains("无效") || s.contains("应予调整")
                || s.contains("建议修改") || s.contains("存在风险");
    }

    /**
     * 合同级完备性检查：返回缺失项名称列表（与逐条款审查并行的第二条逻辑）。
     *
     * <p>仅对条款数达到 {@link #COMPLETENESS_MIN_CLAUSES} 的合同生效——用户可能只粘贴
     * 单个条款要求审查，此时要求「必须包含主体/标的/交付时间」属于误报。条款数过少时
     * 不产出缺失提示，把判断交还给条款级语义审查。</p>
     */
    List<String> checkCompleteness(String contractText, int clauseCount) {
        List<String> missing = new ArrayList<>();
        if (contractText == null || contractText.isBlank()) {
            return missing;
        }
        // 无编号的自由文本（0 条款）也属「要素可疑」情形：连「第X条」结构都没有，
        // 几乎不可能是完整合同，不应因条款数为 0 而跳过检查。
        boolean unnumberedFreeText = clauseCount == 0 && contractText.length() >= 8;
        if (clauseCount < COMPLETENESS_MIN_CLAUSES && !unnumberedFreeText) {
            return missing;
        }
        for (String[] item : COMPLETENESS_ITEMS) {
            String name = item[0];
            String pattern = item[1];
            if (!Pattern.compile(pattern).matcher(contractText).find()) {
                missing.add(name);
            }
        }
        // 轻微缺失不作为风险：缺失项占比达阈值才提示，避免把要素基本齐全的合同
        // 因为个别通用项（如生效条件）缺失而误判。
        if (missing.size() < COMPLETENESS_MIN_MISSING) {
            return new ArrayList<>();
        }
        return missing;
    }

    /**
     * 非条款块判定（不参与逐条款审查与条款计数）：
     * 切分器把首条款前的引言标记为「-引言」，无条款结构的整段文本标记为「-全文」。
     * 前者是提问语句、后者根本不是条款，都不应被当作合同条款审查。
     */
    private boolean isIntroBlock(ClauseBlock block) {
        String id = block.getCanonicalId();
        return id != null && (id.endsWith("-引言") || id.endsWith("-全文"));
    }

    /** 统计真正参与审查的条款数（排除引言块）。 */
    private int countReviewableClauses(List<ClauseBlock> blocks) {
        int n = 0;
        for (ClauseBlock b : blocks) {
            if (!isIntroBlock(b)) {
                n++;
            }
        }
        return n;
    }

    // ---- 风险判定 ----

    private boolean isLowRelevance(List<Map<String, Object>> hits) {
        return hits.isEmpty() || topScore(hits) < LOW_SCORE;
    }

    /**
     * 违约金比例畸高判定：约定违约金超过合同价/未履行部分的 30% 即属「过分高于损失」。
     * 确定性规则，不依赖 LLM。
     *
     * @return 畸高比例（0~1），未显式给出比例时返回 null
     */
    Double penaltyRatioAnomaly(String clauseText) {
        if (clauseText == null) {
            return null;
        }
        // 必须是违约金语境，避免把「利率百分之X」误当违约金比例
        if (!clauseText.contains("违约金") && !clauseText.contains("滞纳金")) {
            return null;
        }
        java.util.regex.Matcher m = PERCENT_PATTERN.matcher(clauseText);
        if (!m.find()) {
            return null;
        }
        Double pct = cnNumber(m.group(1));
        if (pct == null || pct <= 0) {
            return null;
        }
        double ratio = pct / 100.0;
        return ratio > PENALTY_RATIO_CAP ? ratio : null;
    }

    /**
     * 中文数字转 double。支持「五十」「十」「二十」「三十五」「百」「100」等常见写法，
     * 正确处理十/百作为位权（三十五 = 35，而非305）。
     */
    Double cnNumber(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        int total = 0;      // 已结算的百位及以上
        int section = 0;    // 当前十位段
        int digit = 0;      // 累积的数字
        boolean any = false;

        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                // 阿拉伯数字：整段视为一个值（如 "35" / "100"）
                String rest = s.substring(s.indexOf(c));
                try {
                    return Double.parseDouble(rest);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            int v = switch (c) {
                case '一', '壹' -> 1;
                case '二', '两', '贰' -> 2;
                case '三', '叁' -> 3;
                case '四', '肆' -> 4;
                case '五', '伍' -> 5;
                case '六', '陆' -> 6;
                case '七', '柒' -> 7;
                case '八', '捌' -> 8;
                case '九', '玖' -> 9;
                default -> -1;
            };
            if (v > 0) {
                digit = v;
                any = true;
                continue;
            }
            if (c == '十' || c == '拾') {
                section += (digit == 0 ? 1 : digit) * 10;
                digit = 0;
                any = true;
            } else if (c == '百' || c == '佰') {
                total += (digit == 0 ? 1 : digit) * 100;
                section = 0;
                digit = 0;
                any = true;
            } else {
                return null; // 非法字符
            }
        }
        if (!any) {
            return null;
        }
        return (double) (total + section + digit);
    }

    private boolean isNumericAnomaly(ContractParamParser.Params pp,
                                     LegalComputeService.ComputeResult cr,
                                     String clauseText) {
        // 借贷利率超过 4×LPR 法定上限：确定性强、无需 LLM
        if (cr == null || cr.calcType != CalcType.LOAN_INTEREST
                || !pp.rateSpecified || pp.ratePerMille == null) {
            return false;
        }
        // 归一化：解析器对「日千分之五」可能抽出 5.0（实为 0.5‰，即 10× 误抽），
        // 直接与上限比较会把合法利率误判为超限，也会让超限倍数失真。
        double rate = PenaltyCalculator.normalizeDailyRate(pp.ratePerMille, clauseText);
        return rate > LOAN_DAILY_CAP_PER_MILLE;
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
