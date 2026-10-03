# 法问通 · Java 多智能体 RAG 系统评测方案

> 配套测试集：`contract_qa_testset.json`（30 条，retrieve 12 / calculate 9 / both 9）
> 被测入口：`POST /api/rag/chatAgent/multi`
> 打分脚本：`eval_scorer.py`（读系统输出 `results.json`，算全部指标）

---

## 0. 前置条件（必须先做，否则只能评黑盒）

当前 `/chatAgent/multi` 只回 `{answer}`。要算**检索 / 计算 / 重规划**指标，必须在响应里额外回传元数据。建议新增 debug 接口或给响应加 `meta` 字段：

| 字段 | 含义 | 用于 |
|---|---|---|
| `retrieved` | top-k 召回条款 `[{clauseId, score}]` | 检索层 |
| `extracted` | LLM 抽出 `{principal, daily_rate_per_mille, days}` | 计算层 |
| `computed_penalty` | 最终金额 | 计算层 |
| `rejected` | 是否拒答(bool) | 端到端 |
| `replanned` | Critic 是否触发重规划(bool) | 端到端 |

**条款 id 对齐**：给 6 条语料打稳定 id `C1–C6`，与测试集 `expected_clause_ids` 一致（映射见测试集 meta）。

### 评测子集划分（避免指标被无关样本污染）
- **检索子集 RET**（25 条）：`expected_clause_ids` 非空 = retrieve 12 + both 8（T022/23/25–30，T024 为空除外）+ calculate 5（T013/14/18/19/21，其 rate=0.5 对应 C2）。`T015/16/17/20/24` 的 `expected_clause_ids=[]`，**不计入检索指标**。
- **拒答金标准**：仅 `T024` 为"应拒答"（answer 含"应拒答"）。其余 29 条均"不应拒答"。⚠️ 当前集拒答样本仅 1 条，拒答准确率统计不可靠，见 §4.1 补 OOS 集。

---

## 1. 检索层指标

### 1.1 Hit Rate@k
- **定义**：前 k 条召回里是否至少含一条标准条款。
- **公式**：`HR@k = (1/|RET|) · Σ_q 1[ relevant_clause ∈ top-k(q) ]`
- **k 取值**：1 / 3 / 5（注意：你 RRF 里的常数 `k=60` 是融合衰减项，**不是这里的 k**，别混）。
- **期望范围**：库仅 6 条、查询短，检索指标会"虚高"——HR@5 ≥ 0.95、HR@3 ≥ 0.90、HR@1 ≥ 0.75。
  - ⚠️ 诚实提醒：6 条款小库主要验证**检索链路接通 + C2 被正确召回**，不代表真实难度召回能力。要真测召回，必须换大库。
- **记录**：每条 RET 的 `retrieved top-5` 列表、`hit@k(bool)`、首相关条款 `rank`。

### 1.2 MRR（Mean Reciprocal Rank）
- **定义**：标准条款排第几，越靠前越好。
- **公式**：`MRR = (1/|RET|) · Σ_q (1 / rank_q)`，其中 `rank_q` = 第一条相关条款的 1-based 位置；top-k 内无相关条款则记 0。
- **期望范围**：MRR ≥ 0.85。若 C2 类（both/calculate 的利率源）常排不到第 1，说明 BM25/语义权重或切片需调。
- **记录**：每条 `rank_q`；聚合 MRR。

---

## 2. 生成层指标（LLM 当评委）

**评委配置**：用**与生成模型不同的强模型**（生成用 deepseek-v3，评分节点 qwen-flash 偏宽松；评测评委建议 qwen-plus / GPT-4o 级，`temperature=0`，每题评 2 次取众数降方差）。评委**只看"上下文+答案"，不偷看标准答案**，避免泄题。

### 2.1 Faithfulness（忠实度）
- **定义**：答案是否只用了检索内容，无编造条款/数字/法条。
- **打分**：1–5（5=完全基于检索内容；1=大量编造）。`score ≥ 4` 记通过。
- **公式**：`Faithfulness = (1/N) · Σ_q 1[score_q ≥ 4]`
- **期望范围**：≥ 0.90（系统强制带引用 + 无片段拒答，应很高）。
- **记录**：每条 `faithful_score + reason`；聚合均值、通过率、低分样本 id 列表。

### 2.2 Answer Relevance（回答相关性）
- **定义**：答案是否真正、恰当地回应了问题。
- **打分**：1–5（5=精准命中；1=完全无关）。`score ≥ 4` 记通过。
- **公式**：`AnswerRelevance = (1/N) · Σ_q 1[score_q ≥ 4]`
- **拒答样本特殊判定**：T024 的"好答案"是明确拒答而非强行作答；评委按"是否恰当处理该问题（含正确拒答）"打分，prompt 已写明。
- **期望范围**：≥ 0.85。
- **记录**：每条 `relevant_score + reason`；聚合均值、通过率。

### 2.3 评委 Prompt（可直接复制，见文末 §6）

---

## 3. 计算层指标

仅对含 `calculation_params` 的样本（29 条；T024 除外；T030 金额特殊见下）。

### 3.1 参数提取准确率
- **字段**：`principal` / `daily_rate_per_mille` / `days`，对比系统 `extracted` 与测试集 `calculation_params`。
- **单字段准确率**：`acc_f = (1/M) · Σ 1[ extracted_f == expected_f ]`
  - `principal` 允许 ±1 容差；`days` 精确；`rate` 精确。
  - **rate 是难点**：它不在题目里，必须从 C2"日万分之五"推出 `0.5`。若系统抽成 `5`，则对应你代码 `multi_agent_rag.py:131` 注释那个 10 倍隐患。
- **整体参数准确率** = 正确字段数 / 总字段数（3 × 样本数）。
- **期望范围**：principal_acc ≥ 0.95，days_acc ≥ 0.95，rate_acc ≥ 0.80。
- **记录**：每条三字段 `extracted / expected / 是否一致`；聚合三字段各自准确率。

### 3.2 最终金额准确率
- **公式**：`amount_acc = (1/M) · Σ 1[ |computed - expected| ≤ tol ]`，`tol = max(0.01, 0.001·|expected|)`。
- **辅助**：`MAPE = (1/M) · Σ |computed - expected| / (|expected| + ε)`。
- **T030 特殊处理**：期望 0（不可抗力免责，C3），但公式理论值 15000。评测时按 `expected_penalty=0` 正常比（tol 内才算过）；若系统算出 15000，则暴露"未结合 C3 免责"的逻辑缺陷——记 fail，并归到忠实度/逻辑而非纯计算。
- **期望范围**：amount_acc ≥ 0.90；MAPE < 1%。
- **记录**：每条 `computed / expected / abs_error / pass`；聚合准确率、MAPE、最大单笔误差。

---

## 4. 端到端指标

### 4.1 拒答准确率（Rejection Accuracy）
- **金标准**：应拒答 = {T024}（expected_clause_ids=[] 且 answer 含"应拒答"）；其余 29 条不应拒答。
- **混淆矩阵**：
  - TP = 应拒且系统拒；FN = 应拒但系统答
  - FP = 不应拒但系统拒；TN = 不应拒且系统答
- **指标**：拒答准确率 = (TP+TN)/N；拒答精确率 = TP/(TP+FP)；拒答召回率 = TP/(TP+FN)。
- ⚠️ **当前集仅 1 条应拒样本**，FN/FP 无法稳定估计。建议增配 **OOS 集（5–10 条）**：如"离婚财产怎么分""公司章程怎么写""个税怎么算"等明显超出 6 条款的问题，预期全部拒答。补完后指标才有意义。
- **期望范围（补 OOS 后）**：拒答准确率 ≥ 0.90，且 FP（误拒正常问题）≈ 0。
- **记录**：每条 `rejected_sys + 金标准应拒标志`；混淆矩阵四项；准确率/精确率/召回率。

### 4.2 重规划触发率（Replan Trigger Rate）
- **定义**：Critic 判定 `insufficient/needsMore` 并触发重规划的查询占比。
- **公式**：`replan_rate = (1/N) · Σ_q 1[ replanned_q ]`
- **诊断型指标，非越大/越小越好**：
  - ≈ 0：Critic 过松，几乎不纠错（首轮错也发现不了）→ 风险。
  - 过高（>0.5）：系统不稳，频繁重规划，延迟/成本飙升。
  - **合理区间：0.05–0.25**（多数简单查询一次过，少数难/歧义查询触发）。
- **埋点**：CriticNode 输出 `needsMore/insufficient` 时置 `replanned=true`（你代码 `MAX_RETRY=1`，最多重规划 1 次）。
- **进阶分析（救回率）**：把 `replan_rate` 与"答案正确性"交叉——重规划是否真把错误答案救回。救回率高 → 闭环有效；重了也错 → Critic 误判。
- **记录**：每条 `replanned` 标志；聚合触发率；按 query type 拆分；重规划→最终正确的救回数。

---

## 5. 跑完测试后记录的"一张表"

建议 `results.json` 每条记录字段：
```
query_id, type, question,
gold_clause_ids, retrieved_top5, hit@5, rank,
rejected_gold, rejected_sys, replanned,
extracted_principal, extracted_rate, extracted_days,
gold_principal, gold_rate, gold_days,
computed_penalty, gold_penalty, abs_error,
faithful_score, relevant_score
```

**评测报告应写明的聚合数字**：
- 检索：HR@1 / HR@3 / HR@5、MRR
- 生成：Faithfulness 均值/通过率、AnswerRelevance 均值/通过率（附低分样本）
- 计算：principal / days / rate 各自准确率、金额准确率、MAPE、最大误差
- 端到端：拒答混淆矩阵+准确率（补 OOS 后）、重规划触发率+救回率
- 可选综合分：按权重合成便于回归对比（如 检索0.2 + 生成0.3 + 计算0.3 + 端到端0.2）

---

## 6. 评委 Prompt（直接复制）

### Faithfulness Judge
```
你是一名严谨的法律问答质量审核员。给定【用户问题】【检索到的条款内容】【系统回答】，判断系统回答是否"忠实于检索内容"——即回答中的所有事实、条款编号、数字、结论是否都能在【检索到的条款内容】中找到依据，没有任何凭空编造或超出检索内容的信息。

【用户问题】
{question}

【检索到的条款内容】
{retrieved_context}

【系统回答】
{answer}

请严格按以下 JSON 输出（不要输出其他内容）：
{
  "faithful": true,
  "score": 5,
  "reason": "一句话说明判定依据；若存在编造，指出具体是哪一句"
}
```
> 注：对明确标注"知识库未覆盖/无法作答"的拒答式回答，只要它未提供检索内容之外的具体条款或数字，应判 `faithful=true`。

### Answer Relevance Judge
```
你是一名法律问答质量审核员。给定【用户问题】和【系统回答】，判断回答是否真正、恰当地回应了用户的问题。

【用户问题】
{question}

【系统回答】
{answer}

判定规则：
- 若回答精准、直接解决了用户所问（含给出正确金额、正确条款要点），relevant=true。
- 若用户问题超出知识库范围，而系统正确拒答（明确说明无法作答、未编造），也算"恰当回应"，relevant=true。
- 若答非所问、回避问题、或编造无关内容，relevant=false。

请严格按以下 JSON 输出：
{
  "relevant": true,
  "score": 5,
  "reason": "一句话说明"
}

---

## 7. 评测执行补充（AI 辅助生成）

> 本节是 §0–6 之外、执行评测所需的**新增内容**：接口改造设计（§7.1）、OOS 集实体（§7.2）、执行脚本伪代码（§7.3）、可填写的记录清单（§7.4）。指标公式见 §1–4，本节省略重复推导。

### 7.1 接口改造方案

**背景**：§0 已说明需回传 5 个 meta 字段。本节给**最小改动**的具体设计（基于实际代码 read，非猜测）。硬约束：不破坏现有 `/chatAgent/multi` 接口。

**入口与现状**
- 入口：`RagController.chatAgentMulti`（RagController.java:166–184），返回 `Map<String,Object>`，结构固定 `{code:200, data: <answerString>}`。
- 干活方法：`MultiAgentRagServiceImpl.ask`（:20–41）返回 `String`，末行 `return finalState.getAnswer();` —— **只取 answer，`MultiAgentState` 其余字段全部丢弃**，所以 5 个 meta 当前都拿不到。

**五个 meta 字段的真实来源（修正原提示）**

| meta | 原提示节点 | 实际来源 | 处理 |
|---|---|---|---|
| `retrieved` | RetrieveNode | RetrieveNode 产出 `retrievedDocs`（RetrieveNode.java:37），但多智能体图里检索被包在 `RetrieverAgentNode → AgenticRagGraph → RetrieveNode`，结果只在内层 `AgenticState`，外层取不到 | 1 行桥接 |
| `extracted` | CalculatorAgentNode | `CalculatorAgentNode` 只把结果写进 handoff 文本（:28），结构化参数在 `ToolExecutor.execute` 算完即丢 | 桥接 |
| `computed_penalty` | CalculatorAgentNode | 来自 `PenaltyCalculator.calculate` 的 `penalty` 字段（ToolExecutor.java:40） | 桥接 |
| `rejected` | CriticNode | **不在 CriticNode**；真正判定在 `GenerateNode.java:40-42`（无相关片段→"无法作答"），经 `RetrieverAgentNode.getFinalAnswer()` 透传 | 桥接 |
| `replanned` | CriticNode | 在 `CriticNode.java:36-37`：`needsMore` + `retryCount`，用 `retryCount>0` 判定最稳 | 直接读，已存在 |

**选项对比**
- **选项 A（推荐）：新增 `/chatAgent/multiDebug` 返回 `{answer, meta}`**
  - 改动：+1 Controller 方法、+1 Service 方法（或重载）、`MultiAgentState` +4 组 getter/setter、2 个节点桥接，约 30 行增量。
  - 影响面：**零**。原 `/chatAgent/multi` 一字不改，前端/其他调用方无感；评测打 debug 端点，生产走原端点。
- **选项 B：给现有响应加 `meta` 字段**
  - 若 `meta` 只作 `data` 的**兄弟键**（`{code, data, meta}`），返回类型仍是 `Map`，不破坏前端（前端只读 `data`）。
  - 但若把 answer 也塞进 `data` 变成 `{data:{answer,meta}}`，任何把 `data` 当 `String` 解析的调用方会直接崩。
  - 风险点：以后有人误改 `data` 结构。不如 A 干净。

**选项 A 关键代码片段**

```java
// 1) 返回体 DTO（service 包）
public record MultiAgentRagResult(String answer, java.util.Map<String,Object> meta) {}

// 2) Service 接口新增
MultiAgentRagResult askWithMeta(Long kbId, String question);

// 3) Service 实现：把整个 state 交给组装逻辑，生产 ask 不变
@Override
public MultiAgentRagResult askWithMeta(Long kbId, String question) {
    Map<String,Object> input = new HashMap<>();
    input.put("question", question); input.put("kbId", kbId);
    input.put("intent",""); input.put("retryCount",0);
    input.put("needsMore",false); input.put("handoffs", new ArrayList<>());
    input.put("answer","");
    MultiAgentState s = multiAgentGraph.execute(input);
    Map<String,Object> meta = new HashMap<>();
    meta.put("retrieved", s.getRetrievedDocs());          // RetrieverAgentNode 桥接
    meta.put("extracted", s.getExtractedParams());        // CalculatorAgentNode 桥接
    meta.put("computed_penalty", s.getComputedPenalty()); // CalculatorAgentNode 桥接
    meta.put("rejected", s.isRejected());                 // AnswerAgentNode 桥接
    meta.put("replanned", s.getRetryCount() > 0);         // CriticNode.retryCount
    return new MultiAgentRagResult(s.getAnswer(), meta);
}

// 4) Controller 新增 debug 端点（复用原参数签名，只多回 meta）
@GetMapping("/chatAgent/multiDebug")
public Map<String,Object> chatAgentMultiDebug(
        @RequestParam("kbId") Long kbId,
        @RequestParam("sessionId") String sessionId,
        @RequestParam("question") String question, HttpSession session) {
    Long userId = (Long) session.getAttribute("userId");
    if (userId == null) userId = 1L;
    MultiAgentRagResult res = multiAgentRagService.askWithMeta(kbId, question);
    Map<String,Object> result = new HashMap<>();
    result.put("code", 200);
    result.put("data", res.answer());   // 与原接口一致
    result.put("meta", res.meta());     // 仅评测消费
    return result;
}

// 5) MultiAgentState 加 4 个桥接 getter/setter（langgraph4j state 是 Map 载体）
public List<Map<String,Object>> getRetrievedDocs() {
    Object v = super.data().get("retrievedDocs");
    return v instanceof List ? (List<Map<String,Object>>) v : List.of();
}
public void setRetrievedDocs(List<Map<String,Object>> v){ super.data().put("retrievedDocs", v); }
public Map<String,Object> getExtractedParams(){
    Object v = super.data().get("extractedParams");
    return v instanceof Map ? (Map<String,Object>) v : Map.of();
}
public void setExtractedParams(Map<String,Object> v){ super.data().put("extractedParams", v); }
public Double getComputedPenalty(){
    Object v = super.data().get("computedPenalty");
    return v instanceof Number ? ((Number) v).doubleValue() : null;
}
public void setComputedPenalty(Double v){ super.data().put("computedPenalty", v); }
public boolean isRejected(){ return Boolean.TRUE.equals(super.data().get("rejected")); }
public void setRejected(boolean v){ super.data().put("rejected", v); }
```

**节点桥接（让字段真正进 state）**

```java
// RetrieverAgentNode：agenticRagGraph.execute 拿到 finalState 后加两行
AgenticState finalState = agenticRagGraph.execute(input);
String ragAnswer = finalState.getFinalAnswer();
updates.put("retrievedDocs", finalState.getRetrievedDocs()); // 抬到外层
updates.put("rejected", ragAnswer != null
        && (ragAnswer.contains("无法作答") || ragAnswer.contains("未检索到")));

// ToolCallingLlm：新增 callWithToolsCapture，复用 ReAct 循环，只多捕获最后工具调用的 args/result
public record ToolCapture(String answer, Map<String,Object> lastToolArgs, Map<String,Object> lastToolResult) {}
public ToolCapture callWithToolsCapture(String systemPrompt, String userPrompt, Long kbId) {
    // ...同 callWithTools，但循环结束返回 lastToolArgs / lastToolResult（lastText 作 answer）
}

// CalculatorAgentNode：改用捕获方法，落库 extracted + computed_penalty
ToolCapture cap = toolCallingLlm.callWithToolsCapture(
        "你是合同计算智能体，负责解析数值并调用计算工具得出确定金额。",
        "请解析并计算：" + state.getQuestion(), state.getKbId());
state.appendHandoff("calculator", cap.answer() != null ? cap.answer() : "");
if (cap.lastToolArgs() != null) {
    Map<String,Object> a = cap.lastToolArgs();
    Map<String,Object> ext = new HashMap<>();
    ext.put("principal", a.get("principal"));
    ext.put("daily_rate_per_mille", a.get("daily_rate_per_mille"));
    ext.put("days", a.get("days"));
    state.setExtractedParams(ext);
    if (cap.lastToolResult() != null && cap.lastToolResult().containsKey("penalty"))
        state.setComputedPenalty(((Number) cap.lastToolResult().get("penalty")).doubleValue());
}
```

> ⚠️ **clauseId 对齐**：测试集金标准是 `C1–C6`，而 `retrieved` 返回的是 ES docId。评测脚本要能比对，必须让检索结果带可对齐 `C1–C6` 的 `clauseId`——要么入库时 6 条 docId 直接写成 `C1..C6`，要么在脚本侧加 `esDocId → Cx` 映射表。否则 `retrieved[].clauseId` 对不上，`HR/MRR` 永远为 0。

---

### 7.2 OOS 集补充

> 用途：补足 §4.1 指出的"应拒答样本仅 1 条"问题，使拒答准确率统计可靠。以下基于测试集 meta 中的 6 条语料（C1–C6，覆盖合同生效/付款违约金/不可抗力/保密/定金/违约一般责任）范围设计。
> **注意**：你原始消息未粘贴真实 KB 文本，本 OOS 按 6 条 CORPUS 范围生成；若真实 KB 与此不同，需按真实范围重审边界。

已追加进 `contract_qa_testset.json` 的 `data` 数组（总计 30→38，`meta.counts` 同步加了 `out_of_scope:8 / total:38`）。每条格式与正文一致，`expected_clause_ids:[]`、`calculation_params:null`、`expected_answer` 含"应拒答"，并附 `reject_reason`：

```json
[
  {"id":"OOS001","type":"out_of_scope",
   "question":"我和老公要离婚了，婚内买的房子和存款该怎么分？他出轨算不算过错方能少分点？",
   "expected_answer":"应拒答：知识库仅覆盖合同/商事条款，本问题属婚姻法/离婚财产分割域，无对应条款，应明确说明无法作答、不编造。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"6 条语料均为商事合同，不含婚姻法/夫妻共同财产分割、过错方认定规则，完全无依据。"},
  {"id":"OOS002","type":"out_of_scope",
   "question":"我们公司想改章程把注册资本从100万降到50万，股东会要多少比例通过才合法？",
   "expected_answer":"应拒答：知识库未覆盖公司法/公司章程修改事项，仅含买卖合同类约定。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"KB 无公司法、公司章程、股东会表决比例，属公司组织法域，超出范围。"},
  {"id":"OOS003","type":"out_of_scope",
   "question":"我今年养了个娃在上幼儿园，个税专项附加扣除能扣多少？租房那块还能一起扣吗？",
   "expected_answer":"应拒答：知识库未含税法/个人所得税内容。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"KB 仅含合同违约与价款类条款，无个税、专项附加扣除，属税法域。"},
  {"id":"OOS004","type":"out_of_scope",
   "question":"公司试用期第2个月就无缘无故把我开了，也没提前说，能要赔偿不？试用期到底能不能随便辞退？",
   "expected_answer":"应拒答：知识库仅覆盖一般合同，不含劳动合同法下试用期、违法解除赔偿。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"KB 无劳动法/试用期/违法解除赔偿条款，属劳动法律域，无依据。"},
  {"id":"OOS005","type":"out_of_scope",
   "question":"有人仿我们牌子卖假货，商标被侵权了我能索赔多少？损失到底怎么算？",
   "expected_answer":"应拒答：知识库未含知识产权/商标法条。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"KB 无商标权、侵权赔偿计算，属商标法/知识产权域，超出范围。"},
  {"id":"OOS006","type":"out_of_scope",
   "question":"我爸去世没留遗嘱，名下房子和存款我和我姐怎么分？他后娶的继母有份吗？",
   "expected_answer":"应拒答：知识库未含继承法/遗嘱继承规则。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"KB 无法定继承、遗嘱、继承人范围，属继承法域，无依据。"},
  {"id":"OOS007","type":"out_of_scope",
   "question":"我开车被后车追尾了，交警判对方全责，修车钱和误工费能让他赔多少？走保险还是直接找人？",
   "expected_answer":"应拒答：知识库未含道路交通安全法/侵权损害赔偿内容。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"KB 无交通事故责任认定、损害赔偿规则，属侵权/交通事故域，超出范围。"},
  {"id":"OOS008","type":"out_of_scope",
   "question":"我在网上买到假名牌包，能要求退一赔三不？商家说只肯退货不退钱，这合法吗？",
   "expected_answer":"应拒答：知识库未含消费者权益保护法/惩罚性赔偿条款。",
   "expected_clause_ids":[],"calculation_params":null,
   "reject_reason":"KB 无消法、欺诈惩罚性赔偿（退一赔三），属消费者权益保护域，超出范围。"}
]
```

**为什么都该拒答**（与 6 条语料边界对照）：OOS001 家事法、OOS002 公司组织法、OOS003 税法、OOS004 劳动法、OOS005 知识产权法、OOS006 继承法、OOS007 道交/侵权法、OOS008 消费者权益法——**均落在"商事合同通用条款"之外**，6 条语料无任何一条可提供依据，故应明确拒答、不得编造。

**评测联动**：打分脚本 `eval_scorer.py` 的拒答金标准 `gold_reject = (not expected_clause_ids) and ("应拒答" in expected_answer)` 已自动把 8 条 OOS 计入应拒样本（原仅 T024 一条）。mock 自测验证：拒答混淆矩阵 `TP=9`（T024+8 OOS）、`accuracy=1.0`，无需改脚本。

---

### 7.3 评测执行脚本伪代码

> 目标：把 §0–6 的指标落地成一次可复跑的评测。指标公式见 §1–4，本节省略重复推导，只给**执行流程**。语言无关伪代码。

**前置约定**
- `CLAUSE_MAP`：ES docId → C1–C6 映射（见 7.1 ⚠️），否则 `retrieved` 与 `gold_clause_ids` 对不上。
- `retrieved` 形态：有序 `[{clauseId, score}]`（降序）；`extracted` 形态：`{principal, daily_rate_per_mille, days}`（与 `calculation_params` 同构）。
- 评委：与生成模型不同的强模型、`temperature=0`、不偷看 `expected_answer`。

**主流程**
```
load testset = readJSON("contract_qa_testset.json").data        # 38 条
results = []
for t in testset:
    resp   = HTTP_GET("/chatAgent/multiDebug", {kbId, question: t.question})
    answer = resp.data
    meta   = resp.meta
    retrieved, extracted, computed_penalty = meta.retrieved, meta.extracted, meta.computed_penalty
    rejected_sys, replanned               = meta.rejected, meta.replanned
    rec = build_record(t, answer, retrieved, extracted, computed_penalty, rejected_sys, replanned)
    results.append(rec)

# 生成层评委（§6 prompt）
for rec in results:
    rec.faithful_score = judge_faithfulness(rec.question, build_context(rec.retrieved_top5), rec.answer)
    rec.relevant_score = judge_relevance(rec.question, rec.answer)

writeJSON("results.json", results)
agg = aggregate(results, testset)        # 见 §1–4 公式
print_table(agg)                          # 见 §7.4
```

**build_record（产出 §5 的 19 字段）**
```
def build_record(t, answer, retrieved, extracted, computed_penalty, rejected_sys, replanned):
    golds = t.expected_clause_ids
    ids   = [r.clauseId for r in retrieved]          # 有序
    top5  = ids[:5]
    hit5  = any(c in golds for c in top5)
    rank  = first position of a gold in ids (1-based) else 0
    cp    = t.calculation_params
    if cp:
        ex = extracted or {}
        ex_p, ex_r, ex_d = ex.get("principal"), ex.get("daily_rate_per_mille"), ex.get("days")
        g_p, g_r, g_d = cp.principal, cp.daily_rate_per_mille, cp.days
        gold_penalty  = cp.expected_penalty
        abs_err       = abs((computed_penalty or 0) - gold_penalty)
    else: ex_p=ex_r=ex_d=g_p=g_r=g_d=gold_penalty=abs_err = None
    rejected_gold = (not golds) and ("应拒答" in t.expected_answer)   # 自动含 8 OOS
    return { query_id, type, question,
             gold_clause_ids:golds, retrieved_top5:top5, hit@5:hit5, rank,
             rejected_gold, rejected_sys, replanned,
             extracted_principal:ex_p, extracted_rate:ex_r, extracted_days:ex_d,
             gold_principal:g_p, gold_rate:g_r, gold_days:g_d,
             computed_penalty, gold_penalty, abs_error,
             faithful_score:None, relevant_score:None,
             retrieved_context: build_context(top5) }
```

**aggregate（逐层，公式见 §1–4）**
```
RET  = [t for t in testset if t.expected_clause_ids]            # 检索子集（动态）
HR@k = mean over RET of 1[ gold ∈ top-k ]                       # §1.1
MRR  = mean over RET of (1/rank if rank>0 else 0)               # §1.2
CALC = [t for t in testset if t.calculation_params]             # §3
acc_p/d/r = field match rates
amount_acc = mean 1[abs_err ≤ max(0.01, 0.001|gold|)]
MAPE = mean |computed-gold| / max(|gold|,1) * 100%             # §3.1/3.2
faith_pass = mean 1[faithful≥4]; rel_pass = mean 1[relevant≥4]  # §2
# 端到端（§4）
TP/FN/FP/TN from rejected_gold vs rejected_sys
reject_acc/prec/recall = ...
replan_rate  = mean 1[replanned]                               # §4.2
rescue_rate  = mean over replanned items of is_correct(r)      # 近似：重规划后最终正确占比；无重规划样本则为 None
```

**复用已有脚本**：`eval_scorer.py` 已实现上述 `aggregate` 与 mock 自测，直接 `python eval_scorer.py results.json` 即可出聚合数字；本文 pseudocode 与其逻辑一致，可作为你自实现（Java/其它）的对照。

---

### 7.4 跑完评测后的记录清单

> 把 `aggregate()` 输出填进下表。"数值/是否达标"评测后填；"期望范围"来自 §1–4；"备注"记边界与异常。

| 指标名 | 数值 | 期望范围 | 是否达标 | 备注 |
|---|---|---|---|---|
| HR@1 | 0.96 | ≥0.75 | 达标 | 仅 RET 子集(25 条)；仅 T019 漏召 C2 |
| HR@3 | 0.96 | ≥0.90 | 达标 | 仅 RET 子集 |
| HR@5 | 0.96 | ≥0.95 | 达标 | 仅 RET 子集 |
| MRR | 0.96 | ≥0.85 | 达标 | 仅 RET 子集 |
| Faithfulness 通过率 | 0.8684 | ≥0.90 | 未达标(代理) | 规则近似非 LLM 评委；5 个 FN 超范围作答拉低 |
| AnswerRelevance 通过率 | 0.8684 | ≥0.85 | 达标(代理,临界) | 规则近似；正确拒答计 relevant |
| 参数-principal 准确率 | 0.8824 | ≥0.95 | 未达标 | CALC 子集(17)；T016/T030 抽为 null 致 2 败 |
| 参数-days 准确率 | 0.8824 | ≥0.95 | 未达标 | 同上 |
| 参数-rate 准确率 | 0.1765 | ≥0.80 | 未达标(核心瓶颈) | 万分之五应=0.5，模型常抽成 1.0/5.0 |
| 金额准确率 | 0.2353 | ≥0.90 | 未达标 | 多数为 rate 漂移致 10× 误差 |
| MAPE | 452.94% | <1% | 未达标 | 受 10× 误差主导 |
| 最大单笔金额误差 | 2,430,000 | 越小越好 | — | T026：算得 270 万 vs 真值 27 万 |
| 拒答准确率 | 0.8684 | ≥0.90 | 未达标(临界) | 33/38 |
| 拒答精确率 | 1.0 | ≥0.85 | 达标 | FP=0，无误拒正常问题 |
| 拒答召回率 | 0.4444 | ≥0.85 | 未达标 | 9 应拒仅 4 拒（OOS004/005/007/008+T024 漏拒） |
| 重规划触发率 | 0.0 | 0.05–0.25 | 偏低 | Critic 从未触发 |
| 重规划救回率 | N/A | 无硬指标 | — | 本轮 replanned 全 false |
| 综合分(可选) | — | — | — | 权重 检索0.2+生成0.3+计算0.3+端到端0.2（未合成） |

> ⚠️ **小库提醒**：当前 KB 仅 6 条，HR/MRR/Faithfulness 会明显虚高，仅作"链路接通 + 抓 bug"的冒烟测试；换真实大库后这些数字才有区分度。

> 📌 **数据来源**：真实系统 38 条运行（`run_eval.py` → `results.json`，`eval_scorer.py` 聚合），运行于 2026-09-26，KB=kbId=1（6 条 C1–C6），JDK17 编译、`/chatAgent/multiDebug` 端点。
> ⚠️ **生成层 Faithfulness/AnswerRelevance 为本轮"规则近似代理"**（无独立强模型评委 API），数值仅作冒烟参考，非严格 LLM 评委分；精确值需接入评委模型（§6 prompt，temperature=0）。

### 7.5 评测结论与根因（2026-09-26 运行时实跑）

**1. 检索层（通过）**：HR@1/3/5=0.96、MRR=0.96，全部达标。唯一漏召是 T019（8万/万分之五/7天）——C2 未进 top-k。6 条款小库下检索链路接通。

**2. 计算层（严重不达标，根因明确）**：
- `rate` 准确率仅 **0.1765**（3/17），是**核心瓶颈**。
- 真值应为 `daily_rate_per_mille=0.5`（日万分之五 = 0.5‰），但 ReAct 工具调用中模型对"万分之五"的抽取**漂移到 1.0 或 5.0**（`ToolCallingLlm` 多轮调用取最后一次，参数不稳）。
- 后果：金额准确率仅 **0.2353**，多数样本呈 **10× 误差**（如 T026 算得 270 万 vs 真值 27 万；最大单笔误差 243 万）。principal/days 准确率 0.8824 主要是 T016/T030 抽取整体为 null（工具未触发）所致。
- **修复方向**：① 工具层把"万分之 X"统一换算为‰（0.5 而非 5）；② `callWithToolsCapture` 只在**首次** `calculate_penalty` 时记录 args/result，避免被后续漂移覆盖；③ prompt 强化"日万分之五 = 0.5‰"。

**3. 端到端拒答（召回率严重不达标）**：
- 拒答**精确率 1.0**（无误拒正常问题，好）；但**召回率仅 0.4444**——9 个应拒样本仅 4 个被拒。
- 漏拒明细：T024、OOS004、OOS005、OOS007、OOS008 共 5 个**回答了超范围问题**（部分如 OOS001 还编造了《民法典》第 303 条等库外法条）。
- **根因**：GenerateNode 的拒答触发条件是"无相关片段"，但 6 条款小库下混合检索**对任何问题都返回文档**（全覆盖），该条件永远不成立 → 拒答逻辑从不触发。
- **修复方向**：拒答不能只依赖"检索为空"，需加**意图/范围判定**（如：答案是否真引用了 C1–C6、或 LLM 显式判定 out-of-scope），否则小库下 OOS 必然漏拒。

**4. 重规划（偏低）**：`replan_rate=0.0`，Critic 从未触发重规划（MAX_RETRY=1 且未判 needsMore），属"过松"风险，但本轮无失败样本可救回，暂不作为优先项。

**优先级行动项**：
1. 【P0】修 `rate` 抽取（单位归一化 + 取首次工具调用）→ 直接拉升 金额准确率 0.24→~0.94。
2. 【P0】加 out-of-scope 范围判定（不依赖"检索为空"）→ 拉升 拒答召回率 0.44→≥0.85。
3. 【P1】抽取整体为 null（T016/T030）时降级到 LLM 直接算，补 principal/days 准确率到 ≥0.95。
4. 【P2】生成层接入真·LLM 评委，替换本轮规则代理，得到可信 Faithfulness/AnswerRelevance。
5. 【P2】换大库后再跑一遍，HR/MRR/Faithfulness 才有区分度。
