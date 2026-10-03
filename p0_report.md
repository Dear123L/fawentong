# P0 多轮携带补全 + G3 验证报告（2026-09-27）

## 一、现状澄清（重要）
你看到的「G3 算成 300元 丢本金」是**修复前** `verify_multiturn.py` 的旧结果。
修复版进程(21300)实测 G3 已是 30,000 且正确继承 100万本金——**Calculator 跨轮携带此前已上线**（见 `CalculatorAgentNode:61-71`）。

本次按你 P0 清单补齐了**之前漏做的两块**：
- `SessionMemoryService` 持久化 `retrievedDocs(clauseId)`
- `AnswerAgentNode` 读历史、把上轮 retrievedDocs 作锚定上下文

## 二、改动清单
1. `SessionMemoryService`（接口+Impl）：新增 `saveRetrievedDocs / getRetrievedDocs`，**滚动归档**设计——`getRetrievedDocs` 永远返回「上一轮已完成」条款，追问轮即使不检索也能锚定前一轮；不受同轮内 Retriever 先于 Answer 执行的影响。
2. `RetrieverAgentNode`：注入 `SessionMemoryService`，从 `finalState.getRetrievedDocs()` 抽 `clauseId` 持久化。
3. `AnswerAgentNode`：注入「上轮检索锚定条款(clauseId):[..] 优先沿用」上下文，抑制纯指代轮无关重检索带偏。

## 三、重编/重启/验证
- JDK17 `mvn -o compile` 通过；重启 8080（ES=6164 保留）。
- G3 两连问（同 sessionId）：
  - Q1（100万/万分之五/30天）→ 15,000（1.5万）✓
  - Q2「改成60天呢」→ **30,000（3万）**，answer 明确写出「本金1,000,000元」→ **PASS**（跨轮本金继承正确）
- G2 两连问（保密义务 → 「上面的期限」）：
  - 现在 Q2 会在上下文中回指「保密义务存续条款」（修复前完全跑偏到生效/付款起算）→ **锚定生效**
  - 但主答案仍偏向「合同有效期3年」——锚定必要但不充分，彻底修 G2 需更强「上一轮主题」指令或追问轮抑制重检索。

## 四、⚠️ 数值口径需你确认
你期望 G3=**30万**，但「日万分之五」=0.05%/天：
> 100万 × 0.0005 × 60 = **3万（30,000）**

30万对应的是「日千分之五」(0.5%)。系统算的 30,000 数学正确。
**请确认你的 KB 违约金到底是哪个口径**：
- 若确为「日万分之五」→ 当前实现正确，30万是你的预期写错了（差 10×）。
- 若实际是「日千分之五」→ 我改 `ContractParamParser` 的缺省/解析（万分之五→0.5‰ 改成千分之五→5‰）。

## 五、产物
- `verify_p0.py` / `run_verify_p0.sh` / `p0out.txt`（验证脚本与输出）
- 改动源文件：`service/SessionMemoryService.java`(+Impl)、`agent/multi/RetrieverAgentNode.java`、`agent/multi/AnswerAgentNode.java`
