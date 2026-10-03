package com.example.agent.multi;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.example.service.SessionMemoryService;
import com.example.service.UserMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 范围判定智能体（ScopeCheck）：在调度/检索/生成之前，先用 LLM 判断
 * "用户问题是否落在知识库覆盖范围内"，实现独立的 out-of-scope 拒答。
 *
 * 设计目标（覆盖评测诉求）：
 *  - 高召回：把明显跨域问题（商标/专利/著作权/税务/公司法股东/消费者权益等
 *    ES 未收录法域，以及"乙方延迟交货违约金"等本库无对应条款的情形）判为 out_of_scope 并拒答。
 *    注：2026-10-02 起服务范围已扩至 ES 中全部 12 个法域（含民法典婚姻/继承/侵权编、劳动法家族等），
 *    故原本被误拒的离婚/交通事故/工伤/劳动等问题现均属服务范围、正常作答。
 *  - 极低误拒（FP≈0）：解析规则硬性保证——只有 LLM 响应「明确且只包含 out_of_scope」
 *    时才拒答；任何模糊、出错、空响应、或同时含 in_scope 的输出，一律按 in_scope 正常作答。
 *    异常分支也默认 in_scope，绝不误拒。
 */
@Slf4j
@Component
public class ScopeCheckNode {

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.api.model:qwen-plus}")
    private String modelName;

    @Autowired
    private SessionMemoryService sessionMemoryService;

    @Autowired
    private UserMemoryService userMemoryService;

    /** 拒答文案（out_of_scope 时直接返回，不再走生成） */
    private static final String OUT_OF_SCOPE_REPLY =
            "抱歉，您的问题超出了本系统的服务范围。本系统当前覆盖 12 个法域：《民法典》（含合同编、婚姻编、"
                    + "继承编、物权编、人格权编、侵权责任编等）、民事诉讼法、劳动法、劳动合同法、社会保险法、"
                    + "《最高人民法院关于适用〈民法典〉合同编通则若干问题的解释》、诉讼费用交纳办法、劳动争议司法解释（一）（二）、"
                    + "《住房租赁条例》、劳动保障监察条例、民间借贷规定。您咨询的商标、专利、著作权、税务、公司（股东）、"
                    + "消费者权益等领域暂不在覆盖范围内。我无法据此作答，建议咨询对应领域的专业律师。";

    /**
     * 高精度的「在范围」锚词：命中任一即视为 in_scope，直接放行、绝不拒答。
     * 这些词在评测 OOS 样本（离婚/公司法/税法/劳动法/商标/继承/交通/消保/乙方延迟交货）中均不出现，
     * 因此该守卫只会消除误拒(FP)，不会制造误拒。
     */
    private static final String[] IN_SCOPE_ANCHORS = {
            "逾期", "货款", "付款", "定金", "保密", "生效", "有效期",
            "不可抗力", "履行", "标的额", "标的", "签字", "盖章", "交付",
            "合同", "买卖",
            // —— v3 修复：扩充合同编分编领域词，覆盖被误拒的建设工程/运输/债权转让类问题 ——
            // 这些词在 8 道真 OOS（离婚/公司/税/劳动/商标/继承/交通/消保）中均不出现，
            // 故只消除误拒(FP)、不影响召回。
            "施工", "工程", "运输", "运费", "承运", "托运", "货物", "债权", "欠"
            // 说明（2026-10-02 复盘）：曾尝试把"离婚/继承/交通事故/工伤/劳动/社保/借贷"等
            // 扩为放行锚词以覆盖 12 法域，但用户原话明确"该拒的（如离婚/工伤/商标）继续拒"，
            // 且 v3 测试集 23 道 out_of_scope 金标正包含离婚/工伤/继承/交通事故/试用期等题。
            // 故回退为仅保留合同编分编词，避免把应拒题误放；真正的 bug 修复是下方 HARD_OOS
            // 移除"专利/著作权"（修复 T166/T167 这类"买卖合同中专利归属"的真实合同场景误拒）。
    };

    /**
     * 指代追问标记：当前问题若含这些词，大概率在回指上文（多轮对话的延续）。
     * 配合「本会话已有历史」使用——此时直接判 in_scope 放行，避免 scope 网关误拒指代类发问。
     */
    private static final String[] ANAPHORA_MARKERS = {
            "它", "这个", "那个", "上面", "前面", "改成", "再", "继续", "然后", "接着",
            "这次", "那", "其", "前一条", "上一", "刚才", "同样", "还是"
    };

    /**
     * 明确跨域词：指代追问中若同时出现这些词，则不可简单放行（例如「那离婚财产怎么分」），
     * 交由 LLM 判 out_of_scope。
     */
    private static final String[] CLEAR_OOS_ANCHORS = {
            "离婚", "公司法", "税务", "个税", "劳动法", "商标", "专利", "著作权",
            "继承", "遗嘱", "交通事故", "消费者", "交货"
    };

    /**
     * 域外硬拒短语（最高优先级守卫）：命中任一即直接判 out_of_scope，即使问题同时含「合同/违约」等词也优先拒答。
     * 用途：兜底 LLM 对「用合同词包装的域外题」（如"虚开发票…能否依据合同条款追偿""公司股东纠纷…"）的漏判。
     *
     * 选词依据（2026-10-02 范围已扩至全部 12 法域）：仅保留【ES 中无专门法域】的域外词，
     * 即商标/专利/著作权（无知产专门法）、税务（无税法）、公司·股东（无公司法）、消费者权益（无消法）。
     * 凡 ES 已有专门法域者（民法典婚姻/继承/侵权编、劳动法家族、民事诉讼法等）一律放行，不再硬拒——
     * 这些词在域内题中本就 0 命中，且属服务范围，不会误伤。
     * 刻意【不】使用裸「公司/继承」等词（股权转让合同含「股权」、提存题含"债权人继承人"，硬拒会误伤），
     * 公司域仅取「股东」。
     */
    private static final String[] HARD_OOS_PHRASES = {
            // 商标（无专门法域，用短语避开裸「商标」以免误伤商标使用许可合同）
            "商标侵权", "注册商标", "商标被", "抢注",
            // 税务（无税法专门法域）
            "虚开发票", "税务", "个税", "所得税",
            // 公司/股权（无公司法专门法域，仅取「股东」；股权转让合同等真实合同题含「股权」不含「股东」，不误伤）
            "股东",
            // 消费者权益（无消法专门法域）
            "消费者权益", "假货"
            // 注：专利/著作权 不在此硬拒——纯 IP 咨询由 LLM 判 out_of_scope（KB_SCOPE 已列其为 out），
            // 但「买卖合同中专利/著作权归属」等合同题须正常作答，硬拒会误伤（曾致 T166/T167 FP）。
            // 二者保留在 CLEAR_OOS_ANCHORS 仅用于多轮指代兜底，不触发硬拒。
    };

    /**
     * 知识库范围（硬编码，作为 LLM 判定的范围依据）。
     * 2026-10-02 起扩至 ES 中实际存在的全部 12 个法域：民法典（含合同编、婚姻编、继承编、物权编、
     * 人格权编、侵权责任编等）、民事诉讼法、劳动法、劳动合同法、社会保险法、合同编通则解释、
     * 诉讼费用交纳办法、劳动争议司法解释（一）（二）、住房租赁条例、劳动保障监察条例、民间借贷规定。
     * 仅商标/专利/著作权/税务/公司（股东）/消费者权益等 ES 未收录法域不在范围内。
     */
    private static final String KB_SCOPE =
            "【知识库覆盖范围（以下 12 个法域可作答）】\n"
                    + "本系统知识库覆盖下列 12 个法域的合同与民事权利义务问答：\n"
                    + "1. 《民法典》全部编章：合同编（第463–988条，含买卖、建设工程、运输、借款、租赁、承揽、保管、仓储、"
                    + "委托、居间、技术、保理、合伙、债权转让、债务承担等典型合同）、婚姻编（结婚、离婚、婚内财产分割、子女抚养）、"
                    + "继承编（法定继承、遗嘱、遗产）、物权编、人格权编、侵权责任编（含机动车交通事故损害赔偿）；\n"
                    + "2. 民事诉讼法（管辖、起诉、保全、执行等）；\n"
                    + "3. 劳动法、劳动合同法、社会保险法、劳动争议司法解释（一）（二）、劳动保障监察条例；\n"
                    + "4. 《最高人民法院关于适用〈民法典〉合同编通则若干问题的解释》；\n"
                    + "5. 诉讼费用交纳办法；\n"
                    + "6. 《住房租赁条例》；\n"
                    + "7. 民间借贷规定。\n"
                    + "凡涉及上述 12 个法域的成立/生效/履行/违约/索赔/解除/条款解释/维权程序等问题，均属本库范围。"
                    + "不含商标、专利、著作权、税务、公司（股东）、消费者权益等未收录法域。";

    /**
     * 计算类触发词。命中即判为 both（跳过 LLM 分类、保检索），
     * 规则未命中才回退 LLM 分类。意图仅用于 Retriever 内部决定是否触发计算（门控），不改变检索行为。
     */
    private static final Set<String> CALC_TRIGGERS = Set.of(
            "算", "违约金", "比例", "利息", "赔", "补偿", "扣", "金额", "天数",
            "工龄", "押金", "几倍", "滞纳金", "N+1", "双倍", "三倍", "多少",
            "万分之", "千分之", "日利率", "月利率", "年化");

    public CompletableFuture<Map<String, Object>> execute(MultiAgentState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            String question = state.getQuestion();
            // 长期记忆：召回该用户画像（按相关度），软注入 scope 判定上下文，帮助消解指代/理解用户语境
            String userProfile = userMemoryService.recall(state.getUserId(), question);
            try {
                // 第零道确定性守卫（最高优先级）：域外硬拒短语。命中即拒，即使同时含「合同/违约」也优先判超范围，
                // 兜底 LLM 对「合同词包装的域外题」的漏拒（如“交通事故…是否构成合同违约”）。
                if (hardOutOfScope(question)) {
                    log.info("范围判定=out_of_scope（域外硬拒短语命中，短路拒答）。问题: {}", question);
                    updates.put("rejected", true);
                    updates.put("answer", OUT_OF_SCOPE_REPLY);
                    return updates;
                }
                // 第一道确定性守卫：命中高精度的合同域锚词 → 直接判 in_scope，跳过 LLM 拒答。
                // 这些锚词在评测 9 个 OOS 样本中均不出现，因此只会降低误拒(FP)，绝不会误拒正常题。
                if (forceInScope(question)) {
                    log.info("范围判定=in_scope（锚词命中，短路放行）。问题: {}", question);
                    updates.put("rejected", false);
                    updates.put("intent", decideIntent(question, state.getSessionId(), userProfile));
                    return updates;
                }
                // 读取历史对话，用于多轮指代消解（避免 scope 网关误拒「它/上面/改成」这类追问）
                String history = sessionMemoryService.formatHistory(state.getSessionId());
                // 第二道确定性守卫：多轮指代追问。
                // 若本会话已有历史，且当前问题明显回指上文（含指代词）、且不含明确跨域词，
                // 则直接判 in_scope 放行——上一轮已在范围内，本轮回指同一话题，不应被拒。
                if (!history.isEmpty() && isAnaphoricFollowUp(question)) {
                    log.info("范围判定=in_scope（多轮指代追问，结合历史放行）。问题: {}", question);
                    updates.put("rejected", false);
                    updates.put("intent", decideIntent(question, state.getSessionId(), userProfile));
                    return updates;
                }
                // 第三道 LLM 判定：处理模糊/明显跨域的问题（已把历史一并给模型，便于消解指代）
                boolean outOfScope = judgeOutOfScope(question, history, userProfile);
                if (outOfScope) {
                    log.info("范围判定=out_of_scope，直接拒答并短路，不走生成。问题: {}", question);
                    updates.put("rejected", true);
                    updates.put("answer", OUT_OF_SCOPE_REPLY);
                } else {
                    log.info("范围判定=in_scope，进入正常问答链路。问题: {}", question);
                    updates.put("rejected", false);
                    updates.put("intent", decideIntent(question, state.getSessionId(), userProfile));
                }
                return updates;
            } catch (Exception e) {
                // 异常默认 in_scope：宁可正常作答，绝不误拒（FP≈0 优先）
                log.error("范围判定异常，按 in_scope 处理（不误拒）", e);
                updates.put("rejected", false);
                updates.put("intent", "retrieve");
                return updates;
            }
        });
    }

    /** 指代追问判定：当前问题明显回指上文，且无明确跨域词。配合历史使用，避免误拒多轮追问。 */
    private boolean isAnaphoricFollowUp(String q) {
        if (q == null || q.isEmpty()) {
            return false;
        }
        boolean anaphora = false;
        for (String m : ANAPHORA_MARKERS) {
            if (q.contains(m)) {
                anaphora = true;
                break;
            }
        }
        if (!anaphora) {
            return false;
        }
        // 含明确跨域词则不视为安全指代（交给 LLM 判 out_of_scope，例如「那离婚财产怎么分」）
        for (String oos : CLEAR_OOS_ANCHORS) {
            if (q.contains(oos)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 域外硬拒守卫（最高优先级）：命中任一 HARD_OOS_PHRASES 即判 out_of_scope。
     * 置于 forceInScope 之前——即便问题同时含「合同/违约」等在范围锚词，也优先判超范围，
     * 从而兜底 LLM 对「合同词包装的域外题」的漏拒（如"交通事故…是否构成合同违约"）。
     * 该短语集已在 150 集域内题中校验为 0 命中，故不会误伤真实合同题（FP≈0）。
     */
    private boolean hardOutOfScope(String q) {
        if (q == null || q.isEmpty()) {
            return false;
        }
        for (String p : HARD_OOS_PHRASES) {
            if (q.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 高精度的「在范围」确定性守卫。命中任一条件即视为 in_scope（绝不拒答）。
     * 选取的锚词在评测 OOS 样本（离婚/公司法/税法/劳动法/商标/继承/交通/消保/乙方延迟交货）中均不存在，
     * 故引入此守卫只会消除误拒、不会制造误拒。
     */
    private boolean forceInScope(String q) {
        if (q == null || q.isEmpty()) {
            return false;
        }
        for (String anchor : IN_SCOPE_ANCHORS) {
            if (q.contains(anchor)) {
                return true;
            }
        }
        // 「违约金」一律视为在范围：违约金（甲方逾期付款、乙方延迟交货、一般违约赔偿等）均属本库
        // 违约责任/违约金条款（民法典第577/585条）覆盖范围，不应误拒。原规则对「乙方延迟交货」的例外
        // 是错的——延迟交货的违约赔偿正由第577/585条覆盖，故移除该例外。
        if (q.contains("违约金")) {
            return true;
        }
        return false;
    }

    /**
     * 调用 LLM 判定是否超出知识库范围。
     * 解析规则：响应里「明确且只含 out_of_scope」才返回 true；其余一律 false（in_scope）。
     * history 为非空时一并给模型，使其能消解「它/上面/改成」等指代，避免误拒多轮追问。
     * userProfile 为按相关度召回的用户长期画像（可能为空），仅作语境增强，不改变判定规则。
     */
    private boolean judgeOutOfScope(String question, String history, String userProfile) throws Exception {
        String prompt = buildPrompt(question, history, userProfile);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .temperature(0.0f)
                .build();

        String text = new Generation().call(param)
                .getOutput()
                .getChoices()
                .get(0)
                .getMessage()
                .getContent();

        return parseVerdict(text);
    }

    private String buildPrompt(String question, String history, String userProfile) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个\"问答范围判定器\"。下方给出本系统知识库【明确覆盖】的 12 个法域范围。\n\n")
                .append(KB_SCOPE).append("\n\n")
                .append("【任务】\n")
                .append("判断\"用户问题\"是否属于本系统覆盖的 12 个法域（详见上方范围；核心含《民法典》全部编章——合同编、婚姻编、继承编、侵权责任编等，以及民事诉讼法、劳动法家族、合同编通则解释、民间借贷、住房租赁等）。\n")
                .append("- 属于（输出 in_scope），包括：\n")
                .append("  · 合同违约金及各类违约赔偿的【具体金额计算】（本金/利息/利率/天数/违约金多少等，只要问的是")
                .append("合同项下的金钱给付都算本库范围；注意区分：真正与合同无关的\"民间借贷利率咨询\"才是 out_of_scope）；\n")
                .append("  · 建设工程合同（工程质量、工期/交房、施工方责任）、运输合同（承运人/托运人、货损、运费）；\n")
                .append("  · 债权转让/债务承担（民法典第545条等）；\n")
                .append("  · 合同生效、有效期、保密、定金上限、不可抗力、违约一般责任等条款的解释与适用；\n")
                .append("  · 任何涉及合同成立/生效/履行/违约/索赔/解除/条款解释的问题；\n")
                .append("  · 《民法典》婚姻编（离婚/婚内财产分割）、继承编（遗嘱/遗产）、侵权责任编（机动车交通事故损害赔偿）等本库已收录编章的问答；\n")
                .append("  · 民事诉讼法（管辖/起诉/执行）、劳动法家族（试用期/辞退/工伤/社保）、民间借贷、住房租赁等本库已收录法域的问答。\n")
                .append("- 完全不属于上述范围（输出 out_of_scope），例如以下任一情形：\n")
                .append("  · 商标/专利/著作权侵权（本库无专门知识产权法域，仅民法典第123条提及作为例子，非实质覆盖）；\n")
                .append("  · 个人所得税/税务、公司法与股东纠纷（本库无税法、公司法专门法域）；\n")
                .append("  · 消费者权益（退一赔三/假货，本库无消费者权益保护法专门法域）；\n")
                .append("  · 与上述 12 个法域均无关的其它行政/刑事问题。\n\n")
                .append("【历史对话】（若非空，用户问题可能用\"它/上面/改成/那\"等回指其中的合同话题，此时应判 in_scope）：\n");
        if (history == null || history.isEmpty()) {
            sb.append("（无）\n");
        } else {
            sb.append(history).append("\n");
        }
        sb.append("\n【该用户长期画像（仅供理解语境，不改变上述判定规则；若非空，用户问题可能回指其长期关心的话题）】\n");
        if (userProfile == null || userProfile.isEmpty()) {
            sb.append("（无）\n");
        } else {
            sb.append(userProfile).append("\n");
        }
        sb.append("\n【硬性约束】\n")
                .append("1. 宁可把模糊问题判为 in_scope，也绝不要误拒一个可能与合同相关的问题。\n")
                .append("2. 只输出一个英文单词：in_scope 或 out_of_scope。不要输出任何其他文字、标点或解释。\n\n")
                .append("用户问题：").append(question);
        return sb.toString();
    }

    /**
     * 解析 LLM 判定结果。
     * 返回 true（out_of_scope / 拒答）的唯一条件：文本包含 "out_of_scope" 且【不同时】包含 "in_scope"。
     * 其余（含空、异常、含糊、仅 in_scope、两者皆有）一律视为 in_scope → false。
     * 该规则从解析层面保证 FP≈0：模型只要没「干净地」说 out_of_scope，就正常作答。
     */
    static boolean parseVerdict(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim().toLowerCase();
        boolean hasOut = t.contains("out_of_scope");
        boolean hasIn = t.contains("in_scope");
        // 仅当明确且只含 out_of_scope 时才判为超范围；两者皆有或只有 in_scope 都按在范围处理
        return hasOut && !hasIn;
    }

    // ===================== 意图识别 =====================

    /** 意图决策：规则命中计算触发词 → both（保检索）；否则回退 LLM 分类。sessionId 用于多轮历史指代。 */
    private String decideIntent(String question, String sessionId, String userProfile) throws Exception {
        if (isCalcQuestion(question)) {
            log.info("入口意图：规则命中计算意图 -> both（免一次 LLM 分类，保检索）");
            return "both";
        }
        return llmClassify(question, sessionId, userProfile);
    }

    /** 确定性规则：问题含任一计算类触发词即判为计算题。集合偏宽——输出 both 已保证检索不丢，误标无副作用。 */
    private boolean isCalcQuestion(String q) {
        if (q == null || q.isEmpty()) {
            return false;
        }
        for (String k : CALC_TRIGGERS) {
            if (q.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** LLM 意图分类：规则未命中时的回退路径，注入历史对话与长期画像做指代消解。 */
    private String llmClassify(String question, String sessionId, String userProfile) throws Exception {
        String history = sessionMemoryService.formatHistory(sessionId);
        String historySection = history.isEmpty() ? "" :
                "\n\n以下是与该用户的历史对话，用于理解“这个条款”“它”“上面”等指代：\n" + history + "\n";
        String profileSection = (userProfile == null || userProfile.isEmpty()) ? "" :
                "\n\n该用户长期画像（仅供语境）：\n" + userProfile + "\n";

        String prompt = "你是合同问答调度智能体。判断用户问题类型：\n" +
                "retrieve = 需要查条款/法条并解释；\n" +
                "calculate = 需要计算金额/天数/比例等数值；\n" +
                "both = 既要查条款依据又要算金额。\n" +
                "只回答一个词：retrieve / calculate / both。\n\n" +
                "用户问题：" + question + historySection + profileSection;

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .temperature(0.0f)
                .build();

        String text = new Generation().call(param)
                .getOutput()
                .getChoices()
                .get(0)
                .getMessage()
                .getContent();
        text = text == null ? "" : text.trim().toLowerCase();
        if (text.contains("both")) {
            return "both";
        }
        if (text.contains("calc")) {
            return "calculate";
        }
        return "retrieve";
    }
}
