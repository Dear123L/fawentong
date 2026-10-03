import json
ts = {t['id']: t for t in json.load(open('contract_qa_testset_v3.json', encoding='utf-8'))['data']}
res = {r['id']: r for r in json.load(open('results_v3_150_scopeopen.json', encoding='utf-8'))}

print("=== FP: 本应作答(in-scope)却被拒答 ===")
for tid, t in ts.items():
    r = res.get(tid)
    if r is None:
        continue
    gold_oos = not t.get('expected_clause_ids')
    sys_rej = bool(r.get('rejected'))
    if (not gold_oos) and sys_rej:
        print(f"  [{tid}] {t['question'][:55]}")
        print(f"      gold_clause_ids={t.get('expected_clause_ids')}  type={t.get('gold_clause_type')}")

print()
print("=== 旧OOS 现被作答(gold OOS but answered) → 现在合法范围内 ===")
n_in = 0
for tid, t in ts.items():
    r = res.get(tid)
    if r is None:
        continue
    gold_oos = not t.get('expected_clause_ids')
    sys_rej = bool(r.get('rejected'))
    if gold_oos and (not sys_rej):
        n_in += 1
        if n_in <= 20:
            print(f"  [{tid}] {t['question'][:44]}")
print(f"  ... 共 {n_in} 道旧OOS现被作答")

print()
print("=== 旧OOS 仍被拒答(正确) ===")
n_rej = 0
for tid, t in ts.items():
    r = res.get(tid)
    if r is None:
        continue
    gold_oos = not t.get('expected_clause_ids')
    sys_rej = bool(r.get('rejected'))
    if gold_oos and sys_rej:
        n_rej += 1
        if n_rej <= 20:
            print(f"  [{tid}] {t['question'][:44]}")
print(f"  ... 共 {n_rej} 道旧OOS仍拒答")
