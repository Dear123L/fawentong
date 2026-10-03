# 多 Agent 编排精简（6 节点 → 4 节点）只读评估

> 评估日期：2026-09-30 ｜ 状态：**只读评估，未改动任何源码**
> 目标架构：`ScopeCheck（入口守卫）→ Retriever（检索+计算工具）→ Answer（模板拼接）→ Critic（评审+回退）`

---

## 0. 拓扑计数澄清（"5→3" 怎么对上）

当前 `MultiAgentGraph` 实际有 **6 个节点**：
```
START → scopeCheck → coordinator → retriever →(both) calculator → answer → critic →(needsMore) retriever
```
- 你口中的"5 个"应指 **业务智能体 = Coordinator / ScopeCheck / Retriever / Calculator / Answer**（Critic 是独立评审闸门，通常不计入"业务 agent"）。
- 精简后 **业务智能体 = 3 个 = ScopeCheck / Retriever / Answer**，Critic 保留。
- 因此"5→3"成立；若按全部节点算则是 **6 → 4**（删 Coordinator + 删 Calculator，合并进 Retriever）。

---

## 1. 现状数据流（决定"评测会不会变差"的关键）

评测元数据桥接（`MultiAgentRagServiceImpl.askWithMeta`，第 74–80 行）只从最终 state 抽 5 个字段，**eval_scorer 从不读 `answer` 文本**：

| 评测字段 | 来源 state 字段 | 由谁写入 | 对应指标 |
|---|---|---|---|
| `retrieved` | `retrievedDocs` | **RetrieverAgentNode** | HR@1/3/5、MRR |
| `extracted` | `extractedParams` | **CalculatorAgentNode** | rate_acc |
| `computed_penalty` | `computedPenalty` | **CalculatorAgentNode** | amount_acc |
| `rejected` | `rejected` | ScopeCheck + Retriever | reject_recall / prec |
| `replanned` | `retryCount` | Critic | （仅观测） |

**结论前置**：HR / rate / amount / reject 全部由 ScopeCheck + Retriever + Calculator 产出，**与 Answer 的 LLM 无关、与 Coordinator 无关**。所以只要"合并后 Retriever 仍写入这三个字段"，评测数字零回归。

---

## 2. 改造方案（四个子任务）

### A. 删除 Coordinator，规则 + LLM 回退并入 ScopeCheck
- **删除 `CoordinatorNode.java`**。
- `MultiAgentGraph`：去掉 `coordinatorNode` 依赖、`addNode("coordinator")`、START→scopeCheck→coordinator 的边、以及 `_routeCoord` 条件路由。
- `ScopeCheckNode`：在 scope 判定通过（in_scope）后，**设 `intent`**：
  - 规则命中（`isCalcQuestion`，即 CALC_TRIGGERS 那套触发词）→ `"both"`；
  - 规则未命中 → 调原 `llmClassify()` 回退（逻辑原样搬入）。
- 删除 Coordinator 里那段 `handoffs` 写入（Answer 不再依赖 handoffs，见 C）。
- **延迟影响：零回归**。规则命中（~82%）仍只 1 次 LLM（scope）；规则未命中（~18%）= scope LLM + intent LLM = 2 次，与现状（coordinator 是第 2 次）持平。

### B. CalculatorAgentNode 合并进 RetrieverAgentNode
- **删除 `CalculatorAgentNode.java`**。
- `RetrieverAgentNode`：新增依赖 `CalcRegistry` + `ContractParamParser`（已有 `toolCallingLlm` + `sessionMemoryService`）。把 Calculator 的 calc 块（原 `execute` 第 44–143 行）原样搬为私有方法 `runCalc(state)`。
- **门控关键**：`runCalc` 内含 `toolCallingLlm.callWithToolsCapture(...)`（一次 ReAct LLM 兜底抽取）。**必须仅当 `state.getIntent() ∈ {both, calculate}` 时才调用**，否则每个检索题都会被多扣一次 LLM。
- **保命写入（易漏）**：`runCalc` 必须原样保留 `updates.put("extractedParams", ...)` 与 `updates.put("computedPenalty", ...)` —— 漏了 amount_acc 直接归零。
- `MultiAgentGraph`：去掉 `calculatorAgentNode` 依赖、`addNode("calculator")`、`_routeAfterRetriever` 条件路由（retriever 现在恒 → answer）。
- **handoffs 通道两种处理**：
  - **B1（低风险）**：Retriever 同时写 `retriever::`（检索结果）与 `calculator::`（计算结果）两个 handoff，Answer 不改。
  - **B2（推荐，契合 C）**：Answer 改为直接读 state 字段（`retrievedDocs`/`computedPenalty`/`extractedParams`），handoffs 彻底退役。

### C. Answer 轻量化（LLM 综合 → 模板拼接）
- 替换 `toolCallingLlm.callWithTools(...)` 为确定性字符串装配：
  - 若 `rejected`=true → 直接用 ScopeCheck 已写入的拒答文案；
  - 否则：装配 `retrievedDocs`（带 `clauseId` 引用）+ `computedPenalty`/`extractedParams`（计算结论）+ 问题。
  - 模板示例：`【依据：<clauseId>】<doc>\n\n【计算】本金X元，日利率Y‰，Z天，金额=W元（公式…）\n\n` 。
- **技术上完全可行，代码量小（~40 行重写）。**
- **质量风险（唯一真实风险）**：当前 LLM Answer 做了三件 eval 不测、但产品需要的事：① 历史指代消解（多轮"上面/它"）；② 跨轮锚定（G2"上面"回不到保密条款）；③ 自然语言连贯 + 引用编织。纯模板会丢掉这三件。
  - **对评测数字：零影响**（eval 不读 answer 文本）。
  - **对产品体验：多轮追问退化**（"改成60天呢""上面那条呢"可能答偏）。建议在模板里注入 `sessionMemoryService.getRetrievedDocs`（上一轮 clauseId 锚定），不调 LLM 即可部分保住多轮表现。
- Critic 的 `llmSaysInsufficient` 会评审模板答案，偶发判 NO → 触发一次重检索（`MAX_RETRY=1` 封顶），不影响评测数字，仅多一次检索耗时。

### D. Critic 保留，零改动
- 只读 `state.getAnswer()`（现在变模板文本）+ 启发式失败标记。
- `needsMore → retriever` 回退环不变；回退后 retriever 会按持久化的 `intent` 再跑一次 `runCalc`，与原行为一致。

---

## 3. 改动清单（文件级）

| 文件 | 操作 | 改动量 |
|---|---|---|
| `CoordinatorNode.java` | **删除** | — |
| `CalculatorAgentNode.java` | **删除** | — |
| `ScopeCheckNode.java` | 改：并入规则 + `llmClassify`，设 `intent` | +~80 行 |
| `RetrieverAgentNode.java` | 改：加 `runCalc` 私有方法 + 依赖，保留字段写入 + 门控 | +~100 行（从 Calculator 搬入） |
| `AnswerAgentNode.java` | 重写：LLM 综合 → 模板拼接 | 重写 ~40 行 |
| `MultiAgentGraph.java` | 改：删 2 节点、改边、删 2 条件路由 | -~40 行 |
| `MultiAgentState.java` | 可选：清理 handoffs 注释（非必须） | 0 |
| `calc/*`（Registry/Adapter/LoanInterest） | **不动** | 0 |

依赖图：`RetrieverAgentNode` 新增对 `CalcRegistry`、`ContractParamParser` 的注入（已在 Spring 容器，直接 `@RequiredArgsConstructor` 加 final 字段即可）。

---

## 4. 评测影响（结论）

| 指标 | 现状 | 精简后预期 | 依据 |
|---|---|---|---|
| HR@5 | 0.704 | **0.704（不变）** | 检索由 Retriever 产出，未动 |
| rate_acc | 1.0 | **1.0（不变）** | extractedParams 仍由 Retriever 写 |
| amount_acc | 1.0 | **1.0（不变）** | computedPenalty 仍由 Retriever 写（前提：B 不漏写） |
| reject_recall / prec | 1.0 / 1.0 | **不变** | ScopeCheck + Retriever 未动逻辑 |
| 答案文本质量 | LLM 连贯 | **多轮退化** | 见 C 风险 |

**唯一会让数字"变差"的场景**：B 步骤漏写 `extractedParams`/`computedPenalty` → amount_acc 静默归零（不报错）。已列为头号风险。

---

## 5. 风险点（按危险度）

1. 🔴 **高 — 合并后漏写 `extractedParams`/`computedPenalty`**：amount_acc 从 1.0 直接掉 0，eval 不报错只悄悄失败。必须在 `runCalc` 原样保留那两行写入。
2. 🔴 **高 — handoffs 通道断裂**：若 Answer 仍按 `retriever::`/`calculator::` 前缀解析，合并后只写一个前缀 → Answer 组装出空上下文。缓解：采用 B2，Answer 直接读 state 字段，彻底脱离 handoffs。
3. 🟡 **中 — ReAct 兜底 LLM 被误放大**：calc 块里的 `callWithToolsCapture` 是 LLM 调用。若合并后对每个问题都跑 `runCalc`（没用 intent 门控），所有检索题多一次 LLM（延迟/成本升、且可能误算）。必须门控：仅 `intent∈{both,calculate}` 才跑。
4. 🟡 **中 — Answer 模板化致多轮质量退化**："上面/改成60天" 类追问失去指代消解与锚定。评测不扣分但 UX 掉。建议模板注入 prev-clause 锚定文本（不调 LLM）。
5. 🟢 **低 — ScopeCheck 变重**：入口做 scope+intent 两次判断，但 LLM 调用数与现状持平，无延迟回归。
6. 🟢 **低 — Critic 对模板误判 needsMore**：偶发触发一次重检索（MAX_RETRY=1 封顶），不影响评测数字，仅多一次检索耗时。

---

## 6. 时间估算

| 步骤 | 内容 | 人日 |
|---|---|---|
| A | 删 Coordinator + 规则/LLM 并入 ScopeCheck + 图接线 | 0.5 |
| B | Calculator 合并进 Retriever + 保字段 + 门控（最易出 bug） | 1.0 |
| C | Answer 模板化 + 锚定注入 | 0.5 |
| 联调 + 全量 38 题评测回归 | 验证数字零回归 | 0.5 |
| **合计** | | **≈ 2.5–3 人日** |

（不含 Phase 2 劳动补偿/押金扣减；若同周期做另计。）

---

## 7. 建议落地顺序（降低风险）

1. **先做 B**（合并 Calculator 进 Retriever，保字段写入）→ 跑一轮评测确认 `amount_acc` 仍 = 1.0，验证头号风险已规避。
2. **再做 A**（删 Coordinator，规则进 ScopeCheck）。
3. **最后 C**（Answer 模板化）→ 跑评测确认数字零回归。
4. **D 不动**，仅回归验证。

---

## 8. 一句话结论

> 技术上完全可行、评测数字可做到**零回归**（HR/rate/amount/reject 均不变），真实代价是 **Answer 模板化会牺牲多轮对话连贯性**（评测不测、但产品可见）。最易翻车点是 B 合并时漏写 `computedPenalty`/`extractedParams` 与未对 ReAct 兜底做 intent 门控——这两点守住，改造即安全。
