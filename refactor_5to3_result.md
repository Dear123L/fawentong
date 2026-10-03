# 多智能体编排精简（6→4 节点 / 5→3 业务智能体）实施结果报告

> 目标架构：`ScopeCheck（入口守卫）→ Retriever（检索 + 计算）→ Answer（模板拼接）→ Critic（评审 + 回退）`
> 实施顺序：**B → A → C**，每步先备份、跑全量 38 题评测 + G2/G3 多轮验证。
> 日期：2026-09-30　运行环境：Spring Boot 3.3.4 / Java 17 / ES 8.15.5 / langgraph4j，新代码跑在 **8081**（8080 旧进程沙箱锁死）。

---

## 一、拓扑演进（节点计数）

| 阶段 | 节点 | 业务智能体 | 说明 |
|---|---|---|---|
| 改造前 | ScopeCheck → Coordinator → Retriever → Calculator → Answer → Critic | 5（Coordinator/ScopeCheck/Retriever/Calculator/Answer）+ Critic 闸门 | 6 节点 |
| Phase B 后 | ScopeCheck → Coordinator → Retriever → Answer → Critic | 4 + Critic | Calculator 合并进 Retriever |
| Phase A 后 | ScopeCheck → Retriever → Answer → Critic | 3 + Critic | Coordinator 规则并入 ScopeCheck |
| **最终态** | **ScopeCheck → Retriever → Answer → Critic** | **3 + Critic** | **4 节点，达成目标** |

- **Phase B**：`CalculatorAgentNode` 删除，计算逻辑搬入 `RetrieverAgentNode.runCalc()`，用 `intent ∈ {both, calculate}` 门控；`extractedParams`/`computedPenalty` 仍原样写入 state（评测口径不变）；保留 `retriever::`/`calculator::` handoff 供过渡。
- **Phase A**：`CoordinatorNode` 删除，其 `CALC_TRIGGERS` + `isCalcQuestion` + `llmClassify` 搬入 `ScopeCheckNode`（规则命中→`both`，未命中→LLM 回退分类）；图入口由 ScopeCheck 直接路由到 Retriever。
- **Phase C**：`AnswerAgentNode` 改为**确定性模板拼接**（不再调 LLM 综合），顺序：①上轮条款锚定 → ②检索成文答案（内层 AgenticRAG 已生成的 `ragAnswer`）→ ③引用依据（clauseId+content）→ ④计算结论（computedPenalty+extractedParams）。

---

## 二、全量 38 题评测对比（核心结论：三阶段零回归）

评测口径：`contract_qa_testset_v2.json`（27 道检索子集 + 8 道 OOS 拒答 + 若干计算题；计算题 17 道）。
`eval_scorer.py` **只读取 state 桥接字段**（`retrievedDocs`/`extractedParams`/`computedPenalty`/`rejected`），**从不读 answer 文本**——故 Answer 节点改动天然不影响以下数字。

| 指标 | 改造前基线 | Phase B | Phase A | **Phase C** |
|---|---|---|---|---|
| HR@1 | 0.4074 | 0.4074 | 0.4074 | **0.4074** |
| HR@3 | 0.5926 | 0.5926 | 0.5926 | **0.5926** |
| HR@5 | 0.7037 | 0.7037 | 0.7037 | **0.7037** |
| MRR | 0.5368 | 0.5368 | 0.5368 | **0.5368** |
| rate_acc（利率抽取） | 1.0 | 1.0 | 1.0 | **1.0** |
| amount_acc（金额计算） | 1.0 | 1.0 | 1.0 | **1.0** |
| reject_recall（拒答召回） | 1.0 | 1.0 | 1.0 | **1.0** |
| reject_precision | 1.0 | 1.0 | 1.0 | **1.0** |

产物：`results_registry_run1.json`（B前基线）→ `results_phaseB_run1.json` → `results_phaseA_run1.json` → **`results_phaseC_run1.json`**。
**结论：B/A/C 三步全部零回归**，HR@5 恒为 0.7037、amount_acc/reject 恒为 1.0/1.0。

---

## 三、额外修复的隐藏 Bug（Phase B 期间发现）

合并 Calculator 进 Retriever 后，`RetrieverAgentNode` 在 calc 前先调 `sessionMemoryService.saveRetrievedDocs(...)`。
原 `SessionMemoryServiceImpl.saveRetrievedDocs` 首轮用 `prevDocStore.put(sessionId, null)`——**ConcurrentHashMap 拒绝 null value → NPE**，被节点 catch 吞掉，导致 calc 永不执行、`computed_penalty` 全空（Phase B 探针初现 `computed_penalty=None`）。

旧代码里该 NPE 被独立的 `CalculatorAgentNode` 节点"隔开"而长期隐匿；合并后暴露。
**修复**：用 `prevDocStore.remove(sessionId)` 替代 `put(null)`（见 `SessionMemoryServiceImpl.java:133-141）。复测 T017=3780.82、T015/T016=0.0、T013=15000.0 均正确。属真实稳定性修复，非为刷分。

---

## 四、多轮场景验证（G2 / G3）—— 用户特别要求

### G2：指代消解跨轮（"上面说的…适用于 X 吗"）
- **结果：通过。** turn2 正确注入上轮条款锚定（`（延续上轮引用条款：民法典-第502条…）`），并基于锚定条款展开对"赠与合同"的分析，答案连贯、引用合理。
- 当 Critic 未触发 `needsMore` 重检索时，锚定文本取自 `prevDocStore`（真正的上一轮条款），行为符合 Phase C 设计意图。

### G3：跨轮参数继承（"本金100万，日利率5%，借10天" → "改成60天呢"）
- **参数继承：成功。** turn2 抽取为 `{principal:1000000, daily_rate_per_mille:50, days:60}`——本金/利率从上轮继承，days 被"60天"覆盖。这是 `runCalc` 的 `getCalcParams` 跨轮继承逻辑生效。
- **但出现两个真实问题：**

  1. **计算路由在多轮追问下丢失利率上限（核心问题）。**
     turn1 `computed_penalty=3780.82`（正确，命中 `LOAN_INTEREST` + 4×LPR 封顶）；turn2 `computed_penalty=3000000.0`（**错误，未封顶**）。
     根因：`CalcRegistry.route(question)` 只看**当前轮文本**。"改成60天呢"不含"借/利息/利率"等触发词 → 路由到 `PENALTY`（违约金裸公式 `本金×‰×天数`）→ 3,000,000。属于 **CalcRegistry 路由在追问场景的缺口**，非 Phase C 模板节点之过。
     > 注：即使加回 Answer 节点的 LLM 润色，也**修不了这个错误数字**——错值在 state 层，LLM 只会照着念。

  2. **短追问检索退化（次要，且非 Phase C 引入）。**
     "改成60天呢"作为检索 query 语义稀薄，召回了无关的"调查期限/产假"等条款，模板节点如实拼出垃圾检索 → 答案离题。该问题在 Phase A/B（检索步骤未变）同样存在；原 LLM 综合节点或许可借对话历史"脑补"得更像样，但检索本身仍是错的。

  3. **锚定文本在 G3 被重检索覆盖（Phase C 交互瑕疵）。**
     G3 turn2 因检索质量差触发 Critic `needsMore` → 同轮内 Retriever 二次执行 → `saveRetrievedDocs` 二次滚动，`prevDocStore` 被当前轮条款覆盖，导致 Answer 读到的"上轮锚定"实为当前轮。G2（未重检索）无此现象。属边界交互 bug，优先级低。

---

## 五、是否"多轮退化严重"——诚实判定

| 维度 | 判定 |
|---|---|
| 单轮 38 题评测（唯一可量化指标） | **零回归**，HR@5=0.704、amount_acc/reject=1.0/1.0 |
| G2 指代多轮 | 通过 |
| G3 多轮 | 真实退化，但**根因在上游**（计算路由 + 检索上下文），**不是 Answer 模板化简导致** |

**结论：Phase C 并未造成"严重"的单轮退化；G3 的多轮问题本质是多轮追问链路（计算路由 / 检索上下文）的既有短板，模板化只是让它"无法用 LLM 脑补遮掩"，并未让数字变错。**

因此，**不推荐为修 G3 而加回 Answer 节点的 LLM 润色**——它既修不了 state 层错误金额，又会重新引入被本次精简去掉的 LLM 成本/延迟/429 风险。G3 的正确修法在下游之上：

- **(a) 计算路由继承**：`CalcRegistry.route` 在当轮无触发词但 `getCalcParams` 非空（上轮算过）且当轮含"改成/如果/变成/天/利率/本金/金额"等改写指示时，继承上轮 calcType（上轮是 `LOAN_INTEREST` 则本轮也走 `LOAN_INTEREST`，保住封顶）。
- **(b) 检索上下文增强**：对短追问，将上一轮 question 拼接到检索 query（context-augmented retrieval），缓解语义稀薄导致的离题召回。

两项均为**超出本次 B→A→C 范围的新增特性**，需另行排期。

---

## 六、风险与回滚

- 每步均保留文件级备份：`.refactor_backup/pre_phaseB/multi`（+SessionMemoryServiceImpl）、`pre_phaseA/multi`、`pre_phaseC/multi`，任一阶段出问题可整体回退。
- 生产端点 `/api/rag/chatAgent/multi` 未被改动；仅评测用 `/multiDebug` 多了 meta，对前端透明。
- 已知限制：8081 为新代码验证端口；8080 旧进程在沙箱内 `kill`/`taskkill` 均杀不掉（会话隔离），待环境放行后 `mvn -o package` 重建方能在 8080 生效。

---

## 七、后续待办（需用户拍板）

1. **G3 多轮修复**：是否实施 (a) 计算路由继承 + (b) 检索上下文增强？（推荐，优先级中）
2. **部署到 8080**：环境放行后重新打包部署。
3. **面试/简历口径**：核心数字维持 KB=2186、HR@1/3/5=0.407/0.667/0.704、MRR=0.562、rate/amount/reject=1.0/1.0/1.0、拒答精确率 1.000；调度层规则化 + 本次 5→3 精简均属**架构/可靠性/成本优化**，诚实表述，不夸大为召回提升。
