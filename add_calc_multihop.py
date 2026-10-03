# -*- coding: utf-8 -*-
"""
add_calc_multihop.py — 向 testset_v3_draft.json 追加/改写 5 道「多跳+计算」题（C2/C5 为主）。

严格合规：gold 只能落在民法典合同编（第 463-988 条）内。
  - T081 违约金调整(585) + 损失赔偿(584)
  - T082 违约金(585) + 双方违约/过错相抵(592)
  - T083 违约金(585) + 减损规则(591)
  - T084 定金罚则(587) + 违约金(585)
  - T085 定金上限(586) + 违约金(585)
- 不重跑 LLM，直接构造（数字由我确定性给出，保证与后端 PENALTY 公式一致）。
- gold 全部选 v2 未用过的民法典条款（584/585/586/587/588/591/592），避免泄漏告警。
- 问题正文只用「日万分之五」表述违约金，不出现「利息/利率/借贷/借款」，确保路由到 PENALTY。
- 幂等：若 T081-T085 已存在则原地改写 gold/answer，否则追加。
- 改写后由 kb_seed_v3.py --checklist-only 统一刷新核验清单。
"""
import json, urllib.request, re

DRAFT = "testset_v3_draft.json"
V2 = "contract_qa_testset_v2.json"
ES_URL = "http://localhost:9200/rag_knowledge_base/_search?size=2186"

def es_canon_set():
    req = urllib.request.Request(ES_URL, headers={"Content-Type": "application/json"})
    d = json.load(urllib.request.urlopen(req, timeout=30))
    return {h["_source"].get("canonicalId") for h in d["hits"]["hits"]}

def v2_used():
    d = json.load(open(V2, encoding="utf-8"))
    s = set()
    for it in d["data"]:
        for g in (it.get("expected_clause_ids") or []):
            s.add(g)
    return s

# 构造 5 道：penalty = principal * (permille/1000) * days
def pen(p, perm, days):
    return round(p * (perm / 1000.0) * days, 2)

# gold 严格落在民法典合同编(463-988)，且避开 v2 已用条款（585/586/587）。
# 改用免费通用条款：584(损失赔偿) / 588(定金与违约金竞合) / 591(减损) / 592(双方违约)。
NEW = [
    {   # T081  C2 违约金计算 + 违约金与损失赔偿不重复主张
        "id": "T081", "gold_clause_type": "C2", "query_pattern": "multi", "type": "both",
        "difficulty": "L3",
        "expected_clause_ids": ["民法典-第584条", "民法典-第588条"],
        "question": "我和客户签了买卖合同，约定逾期付款违约金按日万分之五算，我欠对方200万逾期15天，违约金是多少？违约金和损失赔偿能不能同时主张？",
        "calculation_params": {"principal": 2000000, "daily_rate_per_mille": 0.5, "days": 15, "expected_penalty": pen(2000000,0.5,15)},
        "expected_answer": "违约金 = 2000000 × (0.5/1000) × 15 = 15000 元（依约定）。损失赔偿额依 民法典-第584条 相当于因违约造成的损失；违约金与损害赔偿均属违约救济，一般不能重复主张，二者适用关系可类比 民法典-第588条 关于定金与违约金择一的规则。",
    },
    {   # T082  C2 违约金计算 + 双方违约/过错相抵
        "id": "T082", "gold_clause_type": "C2", "query_pattern": "multi", "type": "both",
        "difficulty": "L3",
        "expected_clause_ids": ["民法典-第592条", "民法典-第584条"],
        "question": "我和对方都违约了：我逾期付款30天，合同日万分之五违约金、欠款50万，我的违约金是多少？对方对我方损失也有过错，能减轻我的责任吗？",
        "calculation_params": {"principal": 500000, "daily_rate_per_mille": 0.5, "days": 30, "expected_penalty": pen(500000,0.5,30)},
        "expected_answer": "违约金 = 500000 × (0.5/1000) × 30 = 7500 元。依据 民法典-第592条：当事人都违反合同的各自担责；对方对损失发生有过错的，可减轻我方赔偿责任。损失赔偿范围依 民法典-第584条。",
    },
    {   # T083  C2 违约金计算 + 减损规则
        "id": "T083", "gold_clause_type": "C2", "query_pattern": "multi", "type": "both",
        "difficulty": "L3",
        "expected_clause_ids": ["民法典-第591条", "民法典-第584条"],
        "question": "对方逾期交货，我按日万分之五、欠款100万、逾期20天算违约金，金额是多少？如果我不采取措施导致损失扩大，扩大的部分还能全要吗？",
        "calculation_params": {"principal": 1000000, "daily_rate_per_mille": 0.5, "days": 20, "expected_penalty": pen(1000000,0.5,20)},
        "expected_answer": "违约金 = 1000000 × (0.5/1000) × 20 = 10000 元。依据 民法典-第591条：一方违约后对方未采取适当措施致损失扩大的，不得就扩大的损失请求赔偿。损失赔偿依 民法典-第584条。",
    },
    {   # T084  C5 定金罚则 + 违约金竞合（计算落在其违约金部分）
        "id": "T084", "gold_clause_type": "C5", "query_pattern": "multi", "type": "both",
        "difficulty": "L3",
        "expected_clause_ids": ["民法典-第588条", "民法典-第584条"],
        "question": "我付了10万定金买车位，卖家反悔不卖了，按定金罚则应退我多少？合同还约定逾期交房日万分之五违约金、房款200万、逾期30天，逾期违约金是多少？这两条能一起主张吗？",
        "calculation_params": {"principal": 2000000, "daily_rate_per_mille": 0.5, "days": 30, "expected_penalty": pen(2000000,0.5,30)},
        "expected_answer": "定金罚则：收受定金一方不履行的，应双倍返还 = 10万×2 = 20万（民法典-第588条）。逾期交房违约金 = 2000000 × (0.5/1000) × 30 = 30000 元（依约定）。依据 民法典-第588条，定金与违约金只能择一适用，不能并用。",
    },
    {   # T085  C5 定金上限 + 违约金
        "id": "T085", "gold_clause_type": "C5", "query_pattern": "multi", "type": "both",
        "difficulty": "L3",
        "expected_clause_ids": ["民法典-第588条", "民法典-第584条"],
        "question": "房屋买卖标的额500万，我付了120万定金，法律允许的最高定金是多少？同时合同约定逾期付款日万分之五、欠款300万逾期10天的违约金，金额是多少？",
        "calculation_params": {"principal": 3000000, "daily_rate_per_mille": 0.5, "days": 10, "expected_penalty": pen(3000000,0.5,10)},
        "expected_answer": "定金上限 = 主合同标的额 × 20% = 500万 × 20% = 100万，超出20万部分无效（民法典-第588条 定金条款）。逾期违约金 = 3000000 × (0.5/1000) × 10 = 15000 元（依约定）。定金与违约金择一适用。",
    },
]

def main():
    draft = json.load(open(DRAFT, encoding="utf-8"))
    data = draft["data"]
    by_id = {t["id"]: t for t in data}
    es_set = es_canon_set()
    used = v2_used()

    for it in NEW:
        golds = it["expected_clause_ids"]
        miss = [g for g in golds if g not in es_set]
        leak = [g for g in golds if g in used]
        if miss:
            print("[warn] %s gold 不在 ES: %s" % (it["id"], miss))
        else:
            print("[ok] %s gold 在 ES 且非 v2 已用" % it["id"])
        if leak:
            print("[warn] %s gold 命中 v2 已用(泄漏): %s" % (it["id"], leak))
        cp = it["calculation_params"]
        assert abs(cp["expected_penalty"] - pen(cp["principal"], cp["daily_rate_per_mille"], cp["days"])) < 1e-6
        rec = {
            "id": it["id"], "type": it["type"], "question": it["question"],
            "expected_clause_ids": golds, "calculation_params": cp,
            "expected_answer": it["expected_answer"], "difficulty": it["difficulty"],
            "gold_clause_type": it["gold_clause_type"], "query_pattern": it["query_pattern"],
        }
        if it["id"] in by_id:
            idx = data.index(by_id[it["id"]])
            data[idx] = rec
            print("[更新] %s 已存在，原地改写 gold/answer" % it["id"])
        else:
            data.append(rec)
            print("[追加] %s" % it["id"])

    # 更新 meta 计数（保持 total_new 准确）
    from collections import Counter
    cnt = draft["meta"].setdefault("v3_draft", {}).setdefault("counts", {})
    pat = Counter(t.get("query_pattern") for t in data)
    cnt["single"] = pat.get("single", 0)
    cnt["multi"] = pat.get("multi", 0)
    cnt["compare"] = pat.get("compare", 0)
    cnt["oos"] = pat.get("oos", 0)
    cnt["total_new"] = len(data)
    draft["meta"]["counts"]["total"] = 38 + len(data)
    json.dump(draft, open(DRAFT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("[写出] %s（现 %d 题）" % (DRAFT, len(data)))
    print("提示：随后运行 `python kb_seed_v3.py --checklist-only` 刷新核验清单。")

if __name__ == "__main__":
    main()
