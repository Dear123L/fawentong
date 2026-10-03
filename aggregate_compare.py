# -*- coding: utf-8 -*-
"""聚合 v2(595条库) 与 小库基线(6条库) 的 3-run 评测，输出 均值±std + 对比表。"""
import importlib.util, os

BASE = "D:/Quanta_Back_end/back_project/fawentong"
spec = importlib.util.spec_from_file_location("eval_scorer", os.path.join(BASE, "eval_scorer.py"))
es = importlib.util.module_from_spec(spec); spec.loader.exec_module(es)

V2 = os.path.join(BASE, "contract_qa_testset_v2.json")
V1 = os.path.join(BASE, "contract_qa_testset.json")
v2_files = [os.path.join(BASE, f"results_bm25_v2_run{i}.json") for i in (1, 2, 3)]
sm_files = [os.path.join(BASE, f"results_run{i}.json") for i in (1, 2, 3)]

def agg(ts_path, files):
    ts = es.load_testset(ts_path)
    return es.aggregate_runs(ts, files)

v2_agg, _ = agg(V2, v2_files)
sm_agg, _ = agg(V1, sm_files)

keys = ["HR@1", "HR@3", "HR@5", "rate_acc", "amount_acc", "reject_recall"]
hdr = f"{'metric':14s}{'小库(6条) mean±std':>24s}{'大库(595条) mean±std':>26s}{'Δ(大-小)':>12s}"
print(hdr); print("-" * len(hdr))
for k in keys:
    a, b = sm_agg[k], v2_agg[k]
    am = f"{a['mean']:.4f}±{a['std']:.4f}"
    bm = f"{b['mean']:.4f}±{b['std']:.4f}"
    d = b['mean'] - a['mean']
    print(f"{k:14s}{am:>24s}{bm:>26s}{d:>+12.4f}")

print("\n== 各指标详细（各次 run 的值）==")
for k in keys:
    a, b = sm_agg[k], v2_agg[k]
    print(f"{k}: 小库={a['values']}  大库={b['values']}")
