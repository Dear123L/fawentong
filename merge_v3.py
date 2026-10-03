# -*- coding: utf-8 -*-
"""
合并脚本：testset_v3_draft.json -> contract_qa_testset_v3.json
===========================================================
用法：
  python merge_v3.py

行为：
  - 读取 testset_v3_draft.json（v3 全部 47 题，gold 已 100% 落在民法典合同编 463-988）
  - v2(38) 已退役为独立回归基线（schema 不兼容：type=retrieve/calculate/both/out_of_scope，
    无 difficulty/query_pattern/gold_clause_type），**不混入 v3**，避免拉高 HR 与口径冲突。
  - 校验：所有题含 eval_scorer 必需字段
        （id/question/expected_clause_ids/calculation_params/expected_answer/type）
  - 输出 contract_qa_testset_v3.json（47 题）

说明：difficulty / gold_clause_type / query_pattern 为 v3 新增，eval_scorer.py 只读取
expected_clause_ids 与 calculation_params，其余字段一律忽略，向后兼容。
"""
import json
import os
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
DRAFT = os.path.join(HERE, "testset_v3_draft.json")
OUT = os.path.join(HERE, "contract_qa_testset_v3.json")

# eval_scorer 实际消费的字段（其余忽略）
REQUIRED = ["id", "question", "expected_clause_ids", "calculation_params", "expected_answer", "type"]


def load(path):
    with open(path, encoding="utf-8") as f:
        obj = json.load(f)
    return obj


def main():
    draft = load(DRAFT)
    data = draft["data"]

    # 逐题必填字段校验
    for t in data:
        miss = [k for k in REQUIRED if k not in t]
        if miss:
            raise SystemExit("FATAL: 题 %s 缺字段 %s" % (t.get("id"), miss))

    # 统计分布
    gct = Counter(t.get("gold_clause_type") or "C?" for t in data)
    dif = Counter(t.get("difficulty") or "?" for t in data)
    pat = Counter(t.get("query_pattern") or t.get("type") for t in data)
    # 计算/拒答子集统计
    calc_n = sum(1 for t in data if t.get("calculation_params"))
    oos_n = sum(1 for t in data if not t.get("expected_clause_ids"))

    meta = dict(draft.get("meta", {}))
    meta["v3_info"] = {
        "source_draft": os.path.basename(DRAFT),
        "total": len(data),
        "v2_status": "已退役为独立回归基线，不混入 v3",
        "new_fields_added": ["difficulty", "gold_clause_type", "query_pattern"],
        "eval_scorer_compatibility": (
            "eval_scorer 仅读取 id/question/expected_clause_ids/calculation_params/"
            "expected_answer/type，新增字段被忽略，向后兼容"),
        "gold_clause_type_dist": dict(gct),
        "difficulty_dist": dict(dif),
        "query_pattern_dist": dict(pat),
        "calc_count": calc_n,
        "oos_count": oos_n,
    }

    out = {"meta": meta, "data": data}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    print("写出 %s" % OUT)
    print("  总题数   : %d" % len(data))
    print("  难度     : %s" % dict(sorted(dif.items())))
    print("  条款类别 : %s" % dict(gct))
    print("  查询模式 : %s" % dict(pat))
    print("  计算题   : %d   超范围: %d" % (calc_n, oos_n))


if __name__ == "__main__":
    main()
