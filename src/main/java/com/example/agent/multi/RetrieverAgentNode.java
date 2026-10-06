package com.example.agent.multi;

import com.example.agent.graph.AgenticRagGraph;
import com.example.agent.state.AgenticState;
import com.example.calc.CalcType;
import com.example.calc.ExtractedParams;
import com.example.calc.LegalComputeService;
import com.example.service.SessionMemoryService;
import com.example.service.UserMemoryService;
import com.example.util.ContractParamParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索智能体（Retriever）：复用单图版 AgenticRAG 工作流（retrieve→grade→rewrite→generate）
 * 得到带引用的依据，再用 cite_clause 工具做解释与溯源补强。
 *
 * 本节点同时承担检索与计算两项职责：计算逻辑由 {@link #runCalc} 私有方法实现，
 * 仅在 {@code intent ∈ {both, calculate}} 时触发（门控），避免对每个
 * 检索题都跑一次 ReAct 兜底 LLM。计算产出的 {@code extractedParams}/{@code computedPenalty} 原样写入
 * state，handoffs 同时写出 retriever::/calculator:: 供 Answer 节点读取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieverAgentNode {

    private final AgenticRagGraph agenticRagGraph;
    private final ToolCallingLlm toolCallingLlm;
    private final SessionMemoryService sessionMemoryService;
    private final LegalComputeService legalComputeService;
    private final UserMemoryService userMemoryService;

    /** 从自由文本中抽取首个数字（LLM 直算兜底用） */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    public CompletableFuture<Map<String, Object>> execute(MultiAgentState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            try {
                // 0) 长期记忆召回：按相关度召回该用户画像片段，写入 state 供 Answer 软注入（不覆盖短期 state）
                String recalled = userMemoryService.recall(state.getUserId(), state.getQuestion());
                updates.put("userProfile", recalled != null ? recalled : "");

                // 1) 主检索：复用单图版 AgenticRAG 工作流
                Map<String, Object> input = new HashMap<>();
                input.put("originalQuestion", state.getQuestion());
                input.put("currentQuery", state.getQuestion());
                input.put("retryCount", 0);
                input.put("needRetrieval", true);
                input.put("kbId", state.getKbId());

                AgenticState finalState = agenticRagGraph.execute(input);
                String ragAnswer = finalState.getFinalAnswer();

                // 评测桥接：把内层检索结果与拒答判定抬到外层 MultiAgentState
                updates.put("retrievedDocs", finalState.getRetrievedDocs());
                // 评测口径桥接：评分门**过滤后**的片段（top8）。与上面的 retrievedDocs（门前 top50）
                // 口径不同并存——评测指标须用本字段，才能与历史基线（门后 top8）对齐。
                updates.put("gradedDocs", finalState.getGradedDocs());
                updates.put("rejected", ragAnswer != null
                        && (ragAnswer.contains("无法作答") || ragAnswer.contains("未检索到")));

                // 跨轮锚定：把本轮回合检索到的 clauseId 持久化（滚动归档），供追问轮 Answer 锚定前一轮条款
                List<Map<String, Object>> docs = finalState.getRetrievedDocs();
                if (docs != null) {
                    List<String> clauseIds = new ArrayList<>();
                    for (Map<String, Object> d : docs) {
                        Object cid = d.get("clauseId");
                        if (cid != null) {
                            clauseIds.add(String.valueOf(cid));
                        }
                    }
                    sessionMemoryService.saveRetrievedDocs(state.getSessionId(), clauseIds);
                }

                // 2) 检索结论：直接采用内层 AgenticRAG 生成节点已产出的 ragAnswer（LLM 成文答案）。
                // 不额外做一次 refine 合成——交由 Answer 节点模板拼接，省一次 LLM 调用。
                String retrieveResult = (ragAnswer != null && !ragAnswer.isBlank())
                        ? ragAnswer : "（未检索到可引用的条款）";

                // 3) 计算（合并自 CalculatorAgentNode；仅当 intent 需计算才跑，含 ReAct 兜底 LLM 门控）
                String intent = state.getIntent();
                String calcHandoff = null;
                if ("both".equals(intent) || "calculate".equals(intent)) {
                    Map<String, Object> calcRes = runCalc(state);
                    if (calcRes.containsKey("extractedParams")) {
                        updates.put("extractedParams", calcRes.get("extractedParams"));
                    }
                    if (calcRes.containsKey("computedPenalty")) {
                        updates.put("computedPenalty", calcRes.get("computedPenalty"));
                    }
                    calcHandoff = (String) calcRes.get("calcHandoff");
                }

                // 4) 组装 handoffs：同时写出 retriever::/calculator:: 供 Answer 节点读取
                List<String> ho = new ArrayList<>(state.getHandoffs());
                ho.add("retriever::" + retrieveResult);
                if (calcHandoff != null) {
                    ho.add("calculator::" + calcHandoff);
                }
                updates.put("handoffs", ho);
                return updates;
            } catch (Exception e) {
                log.error("检索智能体异常", e);
                List<String> ho = new ArrayList<>(state.getHandoffs());
                ho.add("retriever::（检索失败）");
                updates.put("handoffs", ho);
                return updates;
            }
        });
    }

    /**
     * 计算逻辑：确定性解析 + 注册表分发 + 跨轮继承 + LLM 兜底。
     * 返回 Map：extractedParams / computedPenalty / calcHandoff。
     * 注：callWithToolsCapture 是 LLM 调用，调用方已用 intent 门控，不会在纯检索题上触发。
     */
    private Map<String, Object> runCalc(MultiAgentState state) {
        Map<String, Object> res = new HashMap<>();
        try {
            String question = state.getQuestion();
            String sessionId = state.getSessionId();

            // 1) 确定性解析当前问题
            ContractParamParser.Params pp = ContractParamParser.parse(question);

            // 2) LLM 抽取仅作兜底（保持 ReAct 工具链路与 handoff 不变）
            Map<String, Object> capArgs = null;
            try {
                var cap = toolCallingLlm.callWithToolsCapture(
                        "你是合同计算智能体，负责解析用户问题中的数值并调用计算工具得出确定金额。",
                        "请解析并计算：" + question,
                        state.getKbId());
                capArgs = cap.lastToolArgs();
            } catch (Exception e) {
                log.warn("计算 ReAct 抽取异常，回退到确定性解析", e);
            }
            Double llmP = toDouble(capArgs, "principal");
            Double llmR = toDouble(capArgs, "daily_rate_per_mille");
            Integer llmD = toInt(capArgs, "days");

            // 3) 跨轮继承：追问类问题缺失字段从上轮 calc 参数取
            Map<String, Object> prev = sessionMemoryService.getCalcParams(sessionId);
            Double prevP = toDouble(prev, "principal");
            Double prevR = toDouble(prev, "daily_rate_per_mille");
            Integer prevD = toInt(prev, "days");
            // 追问继承 calcType：上轮计算类型（用于带改写指示的短追问保住利率上限）
            CalcType prevCalcType = sessionMemoryService.getCalcType(sessionId);

            // 优先级：确定性解析 > 上轮继承(追问) > LLM 兜底。
            Double principal = pp.principal != null ? pp.principal : (prevP != null ? prevP : llmP);
            Double rate = pp.ratePerMille != null ? pp.ratePerMille : (prevR != null ? prevR : llmR);
            Integer days = pp.days != null ? pp.days : (prevD != null ? prevD : llmD);

            // 4) 组装 ExtractedParams 并交注册表分发（Phase 0：可插拔计算器，零公式改动）
            ExtractedParams ep = new ExtractedParams();
            ep.principal = principal;
            ep.dailyRatePerMille = rate;
            ep.days = days;
            ep.forceMajeure = pp.forceMajeure;
            ep.question = question;

            // 4.1) 路由 + 计算（内核抽到 LegalComputeService，Retriever 与 ClauseReviewer 共用）。
            //      本服务只做确定性数值计算；session 跨轮继承已在上文完成，LLM 直算兜底保留在下方。
            LegalComputeService.ComputeResult cr = legalComputeService.compute(ep, prevCalcType, question);
            CalcType calcType = cr.calcType;
            Double penalty;
            String handoff;
            if (cr.penalty == null
                    && ep.principal != null && ep.principal > 0
                    && ep.days != null && ep.days > 0) {
                // 确属金额计算题（已抽到本金+天数）但参数仍不完整：降级 LLM 直算，而非默认 0
                Double llmVal = llmDirectCalc(question);
                if (llmVal != null) {
                    penalty = llmVal;
                    handoff = String.format("参数不完整(主路径缺失)，降级 LLM 直算=%.2f元（问题：%s）", penalty, question);
                } else {
                    penalty = null;
                    handoff = cr.handoff;
                }
            } else {
                penalty = cr.penalty;
                handoff = cr.handoff;
            }

            // 5) 落库：归一化后的确定参数 + 金额，供评测与下一轮继承
            Map<String, Object> ext = new HashMap<>();
            ext.put("principal", principal);
            ext.put("daily_rate_per_mille", rate);
            ext.put("days", days);
            res.put("extractedParams", ext);
            res.put("computedPenalty", penalty);
            res.put("calcHandoff", handoff);

            Map<String, Object> toSave = new HashMap<>();
            toSave.put("principal", principal);
            toSave.put("daily_rate_per_mille", rate);
            toSave.put("days", days);
            sessionMemoryService.saveCalcParams(sessionId, toSave);
            sessionMemoryService.saveCalcType(sessionId, calcType);

            log.info("计算（合并入 Retriever）：principal={}, rate={}, days={}, penalty={}, forceMajeure={}",
                    principal, rate, days, penalty, pp.forceMajeure);
            return res;
        } catch (Exception e) {
            log.error("计算（合并入 Retriever）异常", e);
            res.put("calcHandoff", "（计算失败）");
            return res;
        }
    }

    private static Double toDouble(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Integer toInt(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 降级方案：当确定性解析 + 跨轮继承 + ReAct 抽取均无法得到合法参数时，
     * 直接让 LLM 算出违约金金额（纯对话、无工具、不递归），而不是默认返回 0。
     */
    private Double llmDirectCalc(String question) {
        try {
            String ans = toolCallingLlm.chat(
                    "你是违约金与利息计算专家（法律问答支持 12 个法域，违约金计算是典型示例）。请根据用户问题直接计算金额。"
                            + "只输出一个阿拉伯数字（金额，单位：元），不要单位、解释或换行。\n问题：" + question);
            if (ans == null || ans.isBlank()) {
                return null;
            }
            Matcher m = NUMBER.matcher(ans);
            if (m.find()) {
                return Double.parseDouble(m.group());
            }
        } catch (Exception e) {
            log.warn("LLM 直算异常", e);
        }
        return null;
    }
}
