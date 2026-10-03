# -*- coding: utf-8 -*-
"""
评分 + 对比：对一组 (测试集, 结果文件) 跑 eval_scorer，输出对比表与分层 breakdown。
用法：
  python score_compare.py --v2 contract_qa_testset_v2.json results_v2_run1.json \
                          --v3 contract_qa_testset_v3.json results_v3_run1.json
"""
import json, sys, argparse, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eval_scorer as E


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def key_metrics(testset, results):
    rep = E.evaluate(testset, results)
    ret = rep["retrieval"]
    cal = rep["calculation"]
    e2e = rep["e2e"]
    sq_err = sum(1 for r in results if str(r.get("answer", "")).startswith("__ERROR__"))
    tq_err = sum(1 for r in results if str(r.get("answer", "")).startswith("__TIMEOUT__"))
    return {
        "n": len(results),
        "HR@1": ret["HR@1"], "HR@3": ret["HR@3"], "HR@5": ret["HR@5"], "MRR": ret["MRR"],
        "retrieval_subset": ret["subset_size"],
        "rate_acc": cal["field_acc"]["rate"],
        "amount_acc": cal["amount_acc"],
        "reject_P": e2e["reject_precision"], "reject_R": e2e["reject_recall"],
        "reject_acc": e2e["reject_accuracy"],
        "sys_errors": sq_err, "sys_timeouts": tq_err,
    }


def tier_breakdown(testset, results, field):
    by_id = {r["id"]: r for r in results}
    buckets = {}
    for t in testset:
        key = t.get(field)
        if key is None:
            key = "?"
        buckets.setdefault(key, []).append(t)
    rows = []
    for k, ts in sorted(buckets.items()):
        golds = [t for t in ts if t.get("expected_clause_ids")]
        if not golds:
            continue
        sub_res = [by_id[x["id"]] for x in golds if x["id"] in by_id]
        m = E.retrieval_metrics(golds, sub_res)
        rows.append((k, len(golds), m["HR@1"], m["HR@3"], m["HR@5"], m["MRR"]))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--v2", nargs=2, metavar=("TESTSET", "RESULTS"))
    ap.add_argument("--v3", nargs=2, metavar=("TESTSET", "RESULTS"))
    args = ap.parse_args()

    out = {}
    for name, (ts_path, res_path) in [("v2", args.v2), ("v3", args.v3)]:
        if not (ts_path and os.path.exists(res_path)):
            print("[skip] %s 结果文件不存在: %s" % (name, res_path))
            continue
        ts = load(ts_path)["data"]
        res = load(res_path)
        out[name] = (ts, res)

    print("=" * 70)
    hdr = "%-8s %5s %6s %6s %6s %6s %6s %6s %6s %6s" % (
        "set", "n", "HR@1", "HR@3", "HR@5", "MRR", "rate", "amt", "R_P", "R_R")
    print(hdr)
    for name, (ts, res) in out.items():
        m = key_metrics(ts, res)
        print("%-8s %5d %6.3f %6.3f %6.3f %6.3f %6.3f %6.3f %6.3f %6.3f" % (
            name, m["n"], m["HR@1"], m["HR@3"], m["HR@5"], m["MRR"],
            m["rate_acc"], m["amount_acc"], m["reject_P"] or 0, m["reject_R"] or 0))
    print("=" * 70)

    if "v3" in out:
        ts3, res3 = out["v3"]
        print("\n[v3 分层 breakdown — by difficulty]")
        print("%-6s %5s %6s %6s %6s %6s" % ("tier", "n", "HR@1", "HR@3", "HR@5", "MRR"))
        for k, n, h1, h3, h5, mrr in tier_breakdown(ts3, res3, "difficulty"):
            print("%-6s %5d %6.3f %6.3f %6.3f %6.3f" % (k, n, h1, h3, h5, mrr))
        print("\n[v3 分层 breakdown — by gold_clause_type]")
        print("%-8s %5s %6s %6s %6s %6s" % ("type", "n", "HR@1", "HR@3", "HR@5", "MRR"))
        for k, n, h1, h3, h5, mrr in tier_breakdown(ts3, res3, "gold_clause_type"):
            print("%-8s %5d %6.3f %6.3f %6.3f %6.3f" % (k, n, h1, h3, h5, mrr))
        print("\n[v3 系统错误统计]")
        m = key_metrics(ts3, res3)
        print("  sys_ERROR=%d  sys_TIMEOUT=%d  (共 %d)" % (m["sys_errors"], m["sys_timeouts"], m["n"]))


if __name__ == "__main__":
    main()
