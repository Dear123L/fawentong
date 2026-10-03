# 评测执行脚本设计（伪代码 + 自然语言）

> 目标：读取 `contract_qa_testset.json`（38 条：30 常规 + 8 OOS），逐条调用 `/chatAgent/multiDebug` 拿到 `{answer, meta}`，抽取 5 个 meta 字段，算出四层指标，落盘 `results.json`（每条含第 5 节全部字段），最后汇总成一张"指标 / 数值 / 期望范围 / 是否达标"表。
>
> 本文是**设计说明 + 伪代码**，非完整可运行代码，也不含 Java。你按自己的语言（Java/Python）实现即可。所有公式与 `rag-evaluation-scheme.md` 对齐。

---

## 0. 前置约定（实现前必须确认）

1. **clauseId 对齐**：测试集金标准是 `C1–C6`。系统 `retrieved` 返回的是 ES docId，必须能在 runner 侧映射成 `C1–C6`（要么入库时 docId 直接写成 C1…，要么 runner 持有一张 `esDocId → Cx` 映射表）。否则 `gold_clause_ids` 与 `retrieved` 永远对不上，`HR/MRR` 恒为 0。
2. **`retrieved` 形态**：约定 `meta.retrieved` 是**有序列表**，每个元素是 `{clauseId, score}`，按 score 降序。runner 取 `clauseId` 序列即可。
3. **`extracted` 形态**：约定 `meta.extracted = {principal, daily_rate_per_mille, days}`（与测试集 `calculation_params` 同构），缺失字段为 `null`。
4. **`rejected` / `replanned`**：`meta.rejected` 是 bool（是否判定拒答）；`meta.replanned` 是 bool（Critic 是否触发过重规划，`retryCount>0`）。
5. **评委模型**：生成层用与生成模型不同的强模型、temperature=0、每题打 1 次（或 2 次取众数降方差）。评委**不偷看** `expected_answer`，只给 `question / retrieved_context / answer`。

---

## 1. 主流程伪代码

```
load testset = readJSON("contract_qa_testset.json").data   # 38 条
results = []

for t in testset:
    # ---- 1) 调接口 ----
    resp = HTTP_GET("/chatAgent/multiDebug",
                    {kbId: <你的kbId>, question: t.question})
    answer = resp.data                 # 生产 answer
    meta   = resp.meta                 # 评测 meta

    # ---- 2) 抽 5 个 meta 字段 ----
    retrieved       = meta.retrieved            # [{clauseId, score}, ...] 有序
    extracted       = meta.extracted            # {principal, daily_rate_per_mille, days}
    computed_penalty= meta.computed_penalty     # number | null
    rejected_sys    = meta.rejected             # bool
    replanned       = meta.replanned            # bool

    # ---- 3) 计算本条各层中间量 ----
    rec = build_record(t, answer, retrieved, extracted,
                       computed_penalty, rejected_sys, replanned)
    results.append(rec)

# ---- 4) 生成层：评委打分（可放在循环内或单独批量）----
for rec in results:
    rec.faithful_score, _ = judge_faithfulness(rec.question, rec.retrieved_context, rec.answer)
    rec.relevant_score, _ = judge_relevance(rec.question, rec.answer)

# ---- 5) 落盘 ----
writeJSON("results.json", results)

# ---- 6) 汇总 ----
agg = aggregate(results, testset)
print_table(agg)        # 见 §6 模板
```

> `build_record` 在 §4 细化；`judge_*` 在 §3 细化；`aggregate` 在 §5 细化。

---

## 2. 检索层（只在 RET 子集上算）

```
RET = [t for t in testset if t.expected_clause_ids not empty]   # 动态算，约 25 条

# 单条：在 build_record 里先算
def retrieval_per_item(t, retrieved):
    golds = set(t.expected_clause_ids)                 # {C2, ...}
    ids   = [r.clauseId for r in retrieved]            # 有序 clauseId 序列
    top5  = ids[:5]
    hit5  = any(c in golds for c in top5)              # 前5是否含任一金标准
    # rank = 第一个金标准条款出现的位置（1-indexed），不在则 0
    rank  = 0
    for i, c in enumerate(ids, start=1):
        if c in golds:
            rank = i
            break
    return ids, top5, hit5, rank

# 聚合
N = len(RET)
HR@k = (1/N) * Σ_{t∈RET} 1[ 前k个里含金标准条款 ]
MRR  = (1/N) * Σ_{t∈RET} ( 1/rank  if rank>0 else 0 )
```
- `HR@1/3/5` 用同一定义、不同 k。
- `rank` 用**完整** `ids`（不截断到 5），这样 rank 可能 >5，对应 `HR@5=false` 但 MRR 仍记 `1/rank`。
- 记录到 `rec`：`retrieved_top5`(=top5 的 clauseId 列表)、`hit@5`、`rank`。

---

## 3. 生成层（LLM 评委）

```
# 先由 retrieved clauseId 反查条款文本，拼成 context
def build_context(retrieved, clause_text_map):
    return "\n".join(f"[{r.clauseId}] {clause_text_map[r.clauseId]}"
                     for r in retrieved if r.clauseId in clause_text_map)

# Faithfulness 评委（prompt 见 scheme §6，直接复制）
def judge_faithfulness(question, context, answer):
    out = LLM(prompt=FaithfulnessJudge,
              vars={question, retrieved_context: context, answer},
              temperature=0)
    return out.score        # 1..5；也可取 out.faithful 再映射
                           # 拒答式回答只要没编造条款/数字 → 判 faithful=true(5)

# Answer Relevance 评委
def judge_relevance(question, answer):
    out = LLM(prompt=RelevanceJudge,
              vars={question, answer}, temperature=0)
    return out.score        # 1..5；正确拒答(未编造)也算 relevant=true(5)
```
聚合（N_gen = 全部 38 条，含 OOS）：
```
faith_mean   = mean(rec.faithful_score)
faith_pass   = (1/N_gen) * Σ 1[ rec.faithful_score >= 4 ]      # 期望 ≥0.90
rel_mean     = mean(rec.relevant_score)
rel_pass     = (1/N_gen) * Σ 1[ rec.relevant_score  >= 4 ]      # 期望 ≥0.85
low_faith    = [rec.id for rec in results if rec.faithful_score < 4]
low_rel      = [rec.id for rec in results if rec.relevant_score  < 4]
```

---

## 4. 计算层 + 每条记录字段（build_record 细化）

```
def build_record(t, answer, retrieved, extracted, computed_penalty, rejected_sys, replanned):
    ids, top5, hit5, rank = retrieval_per_item(t, retrieved)
    golds = t.expected_clause_ids

    # —— 参数提取比对（仅对 calculation_params 非空的题）——
    cp = t.calculation_params            # null 表示无金额可算
    if cp:
        ex_p, ex_r, ex_d = (extracted or {}).get(f)
                            for f in ("principal","daily_rate_per_mille","days")
        g_p, g_r, g_d = cp.principal, cp.daily_rate_per_mille, cp.days
        ext_principal = (ex_p == g_p)
        ext_rate      = (ex_r == g_r)     # ⚠️ C2"日万分之五"金标准是 0.5，错抽成 5 即判错
        ext_days      = (ex_d == g_d)
        gold_penalty  = cp.expected_penalty
        abs_err       = abs((computed_penalty or 0) - gold_penalty)
    else:
        ex_p=ex_r=ex_d=None; g_p=g_r=g_d=None
        ext_principal=ext_rate=ext_days=None
        gold_penalty=None; abs_err=None

    # —— 拒答金标准 ——
    rejected_gold = (not golds) and ("应拒答" in t.expected_answer)   # T024 + 8 OOS

    rec = {
        "query_id": t.id,
        "type": t.type,
        "question": t.question,
        "gold_clause_ids": golds,
        "retrieved_top5": top5,            # 第5节字段
        "hit@5": hit5,
        "rank": rank,
        "rejected_gold": rejected_gold,
        "rejected_sys": rejected_sys,
        "replanned": replanned,
        "extracted_principal": ex_p, "extracted_rate": ex_r, "extracted_days": ex_d,
        "gold_principal": g_p, "gold_rate": g_r, "gold_days": g_d,
        "computed_penalty": computed_penalty, "gold_penalty": gold_penalty,
        "abs_error": abs_err,
        # faithful_score / relevant_score 由 §3 后补
        "faithful_score": None, "relevant_score": None,
        # 辅助（非第5节强制，但便于排查）：
        "retrieved_context": build_context(retrieved, CLAUSE_MAP),
    }
    return rec
```

聚合（计算层）：
```
CALC = [t for t in testset if t.calculation_params]      # 有金额可算的题
M = len(CALC)
acc_principal = (1/M) Σ 1[ rec.extracted_principal == rec.gold_principal ]
acc_days      = (1/M) Σ 1[ rec.extracted_days      == rec.gold_days ]
acc_rate      = (1/M) Σ 1[ rec.extracted_rate      == rec.gold_rate ]   # 期望 ≥0.80

# 金额准确率（容差：与 0.01 和 0.1% 取大）
tol(e) = max(0.01, 0.001 * |gold_penalty|)
amount_acc = (1/M) Σ 1[ abs_error <= tol(gold_penalty) ]
MAPE = (1/M) Σ |computed - gold| / max(|gold|, 1) * 100%      # 期望 <1%
max_err = max(abs_error)
```
> ⚠️ `T030`（不可抗力豁免）：`gold_penalty=0`，系统若算出 15000 → `abs_err=15000`、金额判错，正是要抓的"未结合 C3 免责"bug。

---

## 5. 端到端（聚合）

```
# 拒答混淆矩阵（N = 全部 38）
TP = Σ 1[ rejected_gold and rejected_sys ]
FN = Σ 1[ rejected_gold and not rejected_sys ]
FP = Σ 1[ not rejected_gold and rejected_sys ]
TN = Σ 1[ not rejected_gold and not rejected_sys ]
reject_acc    = (TP+TN)/N
reject_prec   = TP/(TP+FP)     # 若 TP+FP=0 → None
reject_recall = TP/(TP+FN)     # 期望 ≥0.85（现已 9 个应拒样本，有统计意义）

# 重规划触发率
replan_rate = (1/N) Σ 1[ replanned ]            # 合理 0.05–0.25（诊断型，非越大越好）

# 重规划救回率（近似定义）
def is_correct(rec):
    if rec.rejected_gold:                 # 应拒答题
        return rec.rejected_sys == True
    if rec.type in ("calculate","both"):  # 有金额
        return (rec.abs_error is not None
                and rec.abs_error <= tol(rec.gold_penalty)
                and rec.relevant_score >= 4)
    # retrieve / 其它
    return (rec.hit@5 and rec.relevant_score >= 4)

replanned_items = [r for r in results if r.replanned]
if replanned_items:
    rescue_rate = mean( is_correct(r) for r in replanned_items )
else:
    rescue_rate = None   # 无重规划样本，无法估计
```
> `rescue_rate` 是**近似**：我们只能观测"重规划后是否最终正确"，无法观测"若不重规划是否会错"。严格救回率需 A/B（关掉 Critic 重规划再跑一遍同批题）对比。设计中保留此说明即可。

---

## 6. 聚合表（输出模板）

把 `aggregate()` 的结果直接填进下表，`是否达标` 按期望区间自动判定（合理区间类指标标"合理区间"而非 pass/fail）。

```
| 指标 | 数值 | 期望范围 | 是否达标 |
|---|---|---|---|
| HR@1 | {hr1} | ≥0.75 | {ok} |
| HR@3 | {hr3} | ≥0.90 | {ok} |
| HR@5 | {hr5} | ≥0.95 | {ok} |
| MRR | {mrr} | ≥0.85 | {ok} |
| Faithfulness 通过率 | {faith_pass} | ≥0.90 | {ok} |
| AnswerRelevance 通过率 | {rel_pass} | ≥0.85 | {ok} |
| 参数-principal 准确率 | {acc_p} | ≥0.95 | {ok} |
| 参数-days 准确率 | {acc_d} | ≥0.95 | {ok} |
| 参数-rate 准确率 | {acc_r} | ≥0.80 | {ok} |
| 金额准确率 | {amt_acc} | ≥0.90 | {ok} |
| MAPE | {mape}% | <1% | {ok} |
| 最大金额误差 | {max_err} | 越小越好 | — |
| 拒答准确率 | {rej_acc} | ≥0.90 | {ok} |
| 拒答精确率 | {rej_prec} | ≥0.85 | {ok} |
| 拒答召回率 | {rej_rec} | ≥0.85 | {ok} |
| 重规划触发率 | {replan} | 0.05–0.25(合理) | {ok/偏高/偏低} |
| 重规划救回率 | {rescue} | 无硬指标(报告) | — |
```

`ok` 判定函数：
```
达标 = (数值 is not None) and (下界 <= 数值)            # 单边界指标
区间达标 = (低 <= 数值 <= 高)                          # replan_rate
```

---

## 7. 实现检查清单（照着打勾）

- [ ] `CLAUSE_MAP`：ES docId → C1–C6 映射就绪（否则检索全 0）
- [ ] `/chatAgent/multiDebug` 实际回传 5 个 meta 字段（上一轮方案已设计）
- [ ] `extracted` 字段名与 `calculation_params` 对齐（principal / daily_rate_per_mille / days）
- [ ] 评委调用 temperature=0、不偷看 expected_answer
- [ ] RET 子集用 `expected_clause_ids 非空` 动态筛选（不要硬编码 25）
- [ ] T030 的 `gold_penalty=0` 走 0.01 容差分支
- [ ] 拒答金标准用 `(empty clause_ids) and ("应拒答" in answer)` → 自动含 8 条 OOS
- [ ] `results.json` 每条含 §5 全部 19 个字段（faithful/relevant 后补也行，但落地时必须填）

> ⚠️ 小库提醒：当前 KB 仅 6 条，HR/MRR/Faithfulness 会明显虚高，仅作"链路接通 + 抓 bug"的冒烟测试。换真实大库后这些数字才有区分度。
