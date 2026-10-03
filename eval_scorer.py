# -*- coding: utf-8 -*-
"""
法问通 Java 多智能体 RAG 系统评测打分脚本
=========================================
输入：
  - 测试集 contract_qa_testset_v3.json（150 题，12 法域口径，由 merge_v3.py / relabel_scope12.py 生成）
  - 系统输出 results.json：每条 {id, retrieved:[{clauseId,score}], extracted:{principal,rate,days},
                              computed_penalty, rejected, replanned, answer, faithful_score, relevant_score}
运行：
  python eval_scorer.py                # 自带 mock 自测，无需真实系统
  python eval_scorer.py results.json   # 用你的真实系统输出评测
"""
import json
import sys
import os

TESTSET = os.environ.get("EVAL_TESTSET", "contract_qa_testset_v3.json")


def load_testset(path=TESTSET):
    with open(path, encoding="utf-8") as f:
        obj = json.load(f)
    return obj["data"] if "data" in obj else obj


# ---------------- 检索层 ----------------
def retrieval_metrics(testset, results):
    by_id = {r["id"]: r for r in results}
    ret = [t for t in testset if t.get("expected_clause_ids")]
    n = len(ret)
    hr = {1: 0, 3: 0, 5: 0}
    mrr_sum = 0.0
    rows = []
    for t in ret:
        golds = set(t["expected_clause_ids"])
        res = by_id.get(t["id"], {})
        retrieved = res.get("retrieved", []) or []
        # rank of first relevant (1-based)
        rank = None
        for i, item in enumerate(retrieved, start=1):
            if item.get("clauseId") in golds:
                rank = i
                break
        for k in (1, 3, 5):
            topk = retrieved[:k]
            if any(it.get("clauseId") in golds for it in topk):
                hr[k] += 1
        mrr_sum += (1.0 / rank) if rank else 0.0
        rows.append({"id": t["id"], "rank": rank,
                     "hit@5": rank is not None and rank <= 5})
    return {
        "subset_size": n,
        "HR@1": round(hr[1] / n, 4) if n else None,
        "HR@3": round(hr[3] / n, 4) if n else None,
        "HR@5": round(hr[5] / n, 4) if n else None,
        "MRR": round(mrr_sum / n, 4) if n else None,
        "rows": rows,
    }


# ---------------- 计算层 ----------------
def calc_metrics(testset, results):
    by_id = {r["id"]: r for r in results}
    principals = days = rates = 0
    total_fields = 0
    amount_pass = 0
    amount_total = 0
    errors = []
    for t in testset:
        cp = t.get("calculation_params")
        if not cp:
            continue  # T024 等无计算参数
        res = by_id.get(t["id"], {})
        ext = res.get("extracted") or {}
        # 字段准确率（None 视为抽取失败 → 判错）
        p = ext.get("principal")
        d = ext.get("days")
        r = ext.get("rate")
        p_ok = (p is not None) and abs(p - cp["principal"]) <= 1
        d_ok = (d is not None) and (d == cp["days"])
        r_ok = (r is not None) and abs(r - cp["daily_rate_per_mille"]) < 1e-9
        principals += p_ok; days += d_ok; rates += r_ok
        total_fields += 3
        # 金额准确率
        comp = res.get("computed_penalty")
        exp = cp["expected_penalty"]
        tol = max(0.01, 0.001 * abs(exp))
        ok = comp is not None and abs(comp - exp) <= tol
        amount_pass += ok; amount_total += 1
        if not ok:
            errors.append({"id": t["id"], "computed": comp, "expected": exp})
    m = amount_total or 1
    return {
        "field_acc": {
            "principal": round(principals / amount_total, 4),
            "days": round(days / amount_total, 4),
            "rate": round(rates / amount_total, 4),
        },
        "overall_param_acc": round((principals + days + rates) / (total_fields), 4),
        "amount_acc": round(amount_pass / m, 4),
        "amount_failures": errors,
    }


# ---------------- 端到端 ----------------
def e2e_metrics(testset, results):
    by_id = {r["id"]: r for r in results}
    tp = fn = fp = tn = 0
    replan = 0
    for t in testset:
        res = by_id.get(t["id"], {})
        gold_reject = (not t.get("expected_clause_ids")) and ("应拒答" in t["expected_answer"])
        sys_reject = bool(res.get("rejected"))
        if gold_reject and sys_reject:
            tp += 1
        elif gold_reject and not sys_reject:
            fn += 1
        elif (not gold_reject) and sys_reject:
            fp += 1
        else:
            tn += 1
        if res.get("replanned"):
            replan += 1
    n = len(testset)
    return {
        "confusion": {"TP": tp, "FN": fn, "FP": fp, "TN": tn},
        "reject_accuracy": round((tp + tn) / n, 4),
        "reject_precision": round(tp / (tp + fp), 4) if (tp + fp) else None,
        "reject_recall": round(tp / (tp + fn), 4) if (tp + fn) else None,
        "replan_rate": round(replan / n, 4),
    }


# ---------------- 生成层（若评委分已给） ----------------
def generation_metrics(testset, results):
    by_id = {r["id"]: r for r in results}
    f_scores, r_scores = [], []
    f_pass = r_pass = 0
    for t in testset:
        res = by_id.get(t["id"], {})
        fs = res.get("faithful_score")
        rs = res.get("relevant_score")
        if fs is not None:
            f_scores.append(fs); f_pass += fs >= 4
        if rs is not None:
            r_scores.append(rs); r_pass += rs >= 4
    return {
        "faithfulness_mean": round(sum(f_scores) / len(f_scores), 3) if f_scores else None,
        "faithfulness_passrate": round(f_pass / len(f_scores), 4) if f_scores else None,
        "answer_relevance_mean": round(sum(r_scores) / len(r_scores), 3) if r_scores else None,
        "answer_relevance_passrate": round(r_pass / len(r_scores), 4) if r_scores else None,
        "note": "评委分数需由 LLM 评委对每条打 faithful_score / relevant_score 后填入 results.json",
    }


def evaluate(testset, results):
    return {
        "retrieval": retrieval_metrics(testset, results),
        "calculation": calc_metrics(testset, results),
        "e2e": e2e_metrics(testset, results),
        "generation": generation_metrics(testset, results),
    }


# ---------------- Mock 自测（无需真实系统） ----------------
def mock_results(testset):
    """构造一个'大部分对、少数错'的样例，验证公式能跑、能抓错。"""
    out = []
    import random
    random.seed(7)
    for t in testset:
        golds = t.get("expected_clause_ids") or []
        cp = t.get("calculation_params")
        retrieved = [{"clauseId": c, "score": 0.9} for c in golds] if golds else []
        # 模拟：大部分 rank1 命中；T004 故意排第3
        if t["id"] == "T004" and golds:
            retrieved = [{"clauseId": "C9", "score": 0.95}, {"clauseId": "C8", "score": 0.9}, {"clauseId": golds[0], "score": 0.8}]
        extracted = None
        computed = None
        rejected = (not golds) and ("应拒答" in t["expected_answer"])
        if cp:
            extracted = {"principal": cp["principal"], "rate": cp["daily_rate_per_mille"], "days": cp["days"]}
            computed = cp["expected_penalty"]
        # 故意制造 2 个错误：T017 rate 被错抽成 5（10倍bug），T030 未结合免责算出15000
        if t["id"] == "T017":
            extracted = {"principal": cp["principal"], "rate": 5, "days": cp["days"]}
            computed = round(cp["principal"] * (5 / 1000) * cp["days"], 2)
        if t["id"] == "T030":
            computed = 15000.00  # 未结合 C3 免责
        replanned = random.random() < 0.15
        out.append({
            "id": t["id"], "retrieved": retrieved, "extracted": extracted,
            "computed_penalty": computed, "rejected": rejected, "replanned": replanned,
            "answer": t["expected_answer"], "faithful_score": 5, "relevant_score": 5,
        })
    return out


def _key_metrics(rep):
    """从单次评测报告中抽取用于跨 run 聚合的核心指标。"""
    return {
        "rate_acc": rep["calculation"]["field_acc"]["rate"],
        "amount_acc": rep["calculation"]["amount_acc"],
        "reject_recall": rep["e2e"]["reject_recall"],
        "HR@1": rep["retrieval"]["HR@1"],
        "HR@3": rep["retrieval"]["HR@3"],
        "HR@5": rep["retrieval"]["HR@5"],
    }


def aggregate_runs(testset, files):
    """聚合多次评测（同测试集、不同 results 文件），输出 均值 ± 标准差。"""
    import statistics
    per_run = []
    for fn in files:
        with open(fn, encoding="utf-8") as f:
            results = json.load(f)
        rep = evaluate(testset, results)
        per_run.append({"file": fn, "metrics": _key_metrics(rep), "full": rep})
    keys = list(per_run[0]["metrics"].keys())
    agg = {}
    for k in keys:
        vals = [r["metrics"][k] for r in per_run]
        mean = statistics.mean(vals)
        std = statistics.stdev(vals) if len(vals) > 1 else 0.0
        agg[k] = {"values": vals, "mean": round(mean, 4), "std": round(std, 4)}
    return agg, per_run


if __name__ == "__main__":
    files = [a for a in sys.argv[1:] if os.path.exists(a)]
    if not files:
        testset = load_testset()
        results = mock_results(testset)
        print("未提供 results.json，运行 MOCK 自测（含 2 个故意错误：T017 利率错抽、T030 未免责）")
        rep = evaluate(testset, results)
        print(json.dumps(rep, ensure_ascii=False, indent=2))
    elif len(files) == 1:
        testset = load_testset()
        with open(files[0], encoding="utf-8") as f:
            results = json.load(f)
        print("使用真实系统输出：", files[0])
        rep = evaluate(testset, results)
        print(json.dumps(rep, ensure_ascii=False, indent=2))
    else:
        testset = load_testset()
        agg, per_run = aggregate_runs(testset, files)
        print(f"=== 聚合 {len(files)} 次评测（均值 ± 标准差，样本标准差 ddof=1）===")
        for k, v in agg.items():
            print(f"  {k:12s} 各次={v['values']}  均值={v['mean']}  标准差={v['std']}")
        # 也打印每轮完整报告，便于定位方差来源
        print("\n=== 各次完整报告 ===")
        for r in per_run:
            print(f"\n--- {r['file']} ---")
            print(json.dumps(r["full"], ensure_ascii=False, indent=2))
