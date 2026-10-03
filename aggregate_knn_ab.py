# -*- coding: utf-8 -*-
"""
KNN A/B 聚合对比：A 相(knn=false, 纯 BM25) vs B 相(knn=true, BM25+KNN RRF)
逐指标输出 均值 ± 标准差 与 提升幅度，给出上线决策。
"""
import json, os, statistics, sys
import eval_scorer as es

TESTSET = os.environ.get("EVAL_TESTSET", "contract_qa_testset_v2.json")

A_FILES = ["results_knnoff_run1.json", "results_knnoff_run2.json", "results_knnoff_run3.json"]
B_FILES = ["results_knnon_run1.json", "results_knnon_run2.json", "results_knnon_run3.json"]


def phase_agg(files):
    ts = es.load_testset(TESTSET)
    agg, per_run = es.aggregate_runs(ts, files)
    return agg, per_run


def main():
    a_agg, _ = phase_agg(A_FILES)
    b_agg, _ = phase_agg(B_FILES)

    keys = ["HR@1", "HR@3", "HR@5", "rate_acc", "amount_acc", "reject_recall"]
    print(f"{'指标':<14}{'A(knn=false)':<22}{'B(knn=true)':<22}{'提升':<12}")
    print("-" * 70)
    for k in keys:
        av = a_agg[k]["mean"]; asd = a_agg[k]["std"]
        bv = b_agg[k]["mean"]; bsd = b_agg[k]["std"]
        if av:
            delta = f"+{(bv - av) / av * 100:.1f}%"
        else:
            delta = "-"
        print(f"{k:<14}{av:.4f}±{asd:.4f}        {bv:.4f}±{bsd:.4f}        {delta:<12}")

    # 决策
    hr1_a = a_agg["HR@1"]["mean"]; hr1_b = b_agg["HR@1"]["mean"]
    hr5_a = a_agg["HR@5"]["mean"]; hr5_b = b_agg["HR@5"]["mean"]
    print("\n=== 决策 ===")
    print(f"HR@1: {hr1_a:.3f} -> {hr1_b:.3f}  ({(hr1_b-hr1_a)*100:+.1f} pp)")
    print(f"HR@5: {hr5_a:.3f} -> {hr5_b:.3f}  ({(hr5_b-hr5_a)*100:+.1f} pp)")
    if hr1_b - hr1_a >= 0.20:
        verdict = "DECISIVE WIN — 建议上线 KNN（rag.hybrid.knn.enabled=true）"
    elif hr1_b - hr1_a >= 0.05:
        verdict = "WIN — 建议上线 KNN（建议再补样本确认）"
    else:
        verdict = "NO CLEAR GAIN — 维持纯 BM25"
    print("结论:", verdict)
    # 计算/拒答层 KNN 应不变（仅检索层受影响），打印以确认无回归
    print(f"计算/拒答层 rate_acc={b_agg['rate_acc']['mean']:.3f} amount_acc={b_agg['amount_acc']['mean']:.3f} reject_recall={b_agg['reject_recall']['mean']:.3f}（KNN 不应改变，验证无回归）")


if __name__ == "__main__":
    main()
