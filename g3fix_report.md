# G3 修复报告：CalcRegistry.route 追问场景继承上轮 calcType

> 日期：2026-09-30
> 修复目标：多轮追问（如「改成60天呢」）丢失上轮 calcType，导致误路由 PENALTY、利率上限封顶失效。
> 部署端口：新码跑 8082（8080 沙箱锁死、8081 旧进程跨会话不可达，故新建验证端口）。

## 1. 根因

`CalcRegistry.route(question)` 仅看**当轮文本**：
- 当轮含「利息/利率/借贷/借款/贷款/借了」→ `LOAN_INTEREST`（适用 4×LPR 上限封顶）
- 否则 → `PENALTY`（无封顶）

G3 第 2 轮「改成60天呢」不含任何借贷触发词 → 误判 `PENALTY` → `computed_penalty` 由正确封顶的 3780.82 退化为未封顶的 **3,000,000**（=100万×50‰×60，裸算无上限）。

## 2. 改动（4 个文件）

| 文件 | 改动 |
|---|---|
| `CalcRegistry.java` | `route` 新增重载 `route(question, prevCalcType)`；当轮无借贷触发词、但 `prevCalcType!=null` 且当轮命中改写指示正则 `REWRITE_PATTERN`（改成/如果/变成/天/利率/本金/金额/呢）时，继承 `prevCalcType`。原无参 `route` 委托 `route(q, null)` 行为不变。 |
| `SessionMemoryService.java` | 接口新增 `saveCalcType(sessionId, CalcType)` / `getCalcType(sessionId)`。 |
| `SessionMemoryServiceImpl.java` | 新增 `calcTypeStore`（ConcurrentHashMap），实现上述两法；`null` 安全（不写 null，避免 NPE）。 |
| `RetrieverAgentNode.java` | `runCalc` 读取 `getCalcType(sessionId)` 作为 `prevCalcType`；路由改用 `CalcRegistry.route(question, prevCalcType)`；计算成功后将本轮 `calcType` 通过 `saveCalcType` 持久化。 |

## 3. 多轮验证（G3，同 session）

| 轮次 | 问题 | 修复前 computed_penalty | **修复后 computed_penalty** | 说明 |
|---|---|---|---|---|
| turn1 | 本金100万，日利率5%，借了10天，违约金多少？ | 3780.82 | **3780.82** | 当轮命中「借了」→ LOAN_INTEREST，4×LPR 封顶 ✓ |
| turn2 | 改成60天呢？ | **3,000,000（BUG，未封顶）** | **22684.93（已封顶）** | 继承上轮 calcType=LOAN_INTEREST，100万×13.8%×(60/365)=22684.93 ✓ |

> turn2 数值校验：4×LPR 年化上限 13.8%，60 天封顶利息 = 1,000,000 × 0.138 × 60/365 ≈ **22684.93**，与输出一致。修复正确。

## 4. 全量单轮评测（38 题，v2 测试集）—— 零回归

| 指标 | 修复前（Phase C 基线） | **修复后（G3 fix）** | 变化 |
|---|---|---|---|
| HR@1 | 0.4074 | **0.4074** | 0 |
| HR@3 | 0.5926 | **0.5926** | 0 |
| HR@5 | 0.7037 | **0.7037** | 0 |
| MRR | 0.5368 | **0.5368** | 0 |
| rate_acc | 1.0 | **1.0** | 0 |
| amount_acc | 1.0 | **1.0** | 0 |
| reject_recall | 1.0 | **1.0** | 0 |
| reject_prec | 1.0 | **1.0** | 0 |

逐题 `computed_penalty` 与基线完全一致（T017=3780.82、T013=15000、T015=0.0、T026=270000 等）；8 条 OOS 全部 `rejected=True`。

**为何单轮必然零回归**：单轮时 `getCalcType(sessionId)` 为空（无上轮），`route` 行为与修复前完全一致；G3 改动仅影响「上轮算过 + 当轮改写」的多轮路径。

## 5. 诚实边界（非本次范围，已记录）

- **G3 turn2 答案文本检索仍偏薄**：「改成60天呢」语义稀疏，检索回带有「审限/产假」等无关条款，answer 文本质量差。此属**检索上下文增强**问题，是 Phase A/B 已存在、与本次 calc 路由无关的独立项，用户明确未纳入本次。
- 加回 Answer 节点 LLM 润色**修不了** state 层错误金额（已纠正），且会复引入被去掉的成本/429 风险——故不回退。

## 6. 产物

- 评测结果：`results_phaseC_run1.json`（基线）、`results_g3fix_run1.json`（修复后）
- 验证端口：8082（新码）
- 备份：`.refactor_backup/pre_phaseC/multi`（Phase C 终态源码，可在回滚时参考）
