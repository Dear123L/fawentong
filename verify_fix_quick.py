#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""快速验证确定性修复：覆盖此前系统性错误 + 跑间方差题 + 跨轮携带。"""
import json, urllib.request, urllib.parse, time

BASE = "http://localhost:8080/api/rag/chatAgent/multiDebug"

def call(q, sid):
    url = BASE + "?" + urllib.parse.urlencode({"kbId":1,"sessionId":sid,"question":q})
    with urllib.request.urlopen(url, timeout=180) as r:
        d = json.loads(r.read().decode("utf-8"))
    return d.get("data",""), d.get("meta",{})

print("=== 1) 此前系统性错误题 ===")
cases = [
    ("T017 本金100万 日利率5%(=50‰) 10天", "本金 100 万，日利率高达 5%（千分比 50），借了 10 天，违约金多少？", 500000.0, "verify_t017"),
    ("T022 100万货款拖30天(无显式利率)", "甲方逾期了，100 万货款拖了 30 天没付，按合同得赔多少？", 15000.0, "verify_t022"),
    ("T023 50万货款拖20天(无显式利率)", "我方是甲方，晚付了 50 万货款，拖了 20 天，违约金多少？", 5000.0, "verify_t023"),
    ("T029 150万逾期60天", "甲方逾期付款，本金 150 万，逾期了 60 天，按合同算多少违约金？", 45000.0, "verify_t029"),
    ("T030 不可抗力100万30天", "甲方因为不可抗力逾期付了 100 万货款 30 天，这违约金还要不要赔？", 0.0, "verify_t030"),
    ("T015 本金0", "本金是 0 的话，逾期 30 天违约金是多少？", 0.0, "verify_t015"),
    ("T016 一天都没逾期", "货款 20 万，日万分之五，但一天都没逾期，违约金多少？", 0.0, "verify_t016"),
    ("T020 日千分之一30万20天", "本金 30 万，约定日千分之一，逾期 20 天违约金多少？", 6000.0, "verify_t020"),
]
for name, q, exp, sid in cases:
    a, m = call(q, sid)
    comp = m.get("computed_penalty")
    ok = (comp is not None) and abs(comp - exp) <= max(0.01, 0.001*abs(exp))
    print(f"  [{ 'OK' if ok else 'FAIL'}] {name}: computed={comp} expected={exp}")

print("\n=== 2) 跑间方差题 连跑3次看是否完全一致 ===")
for tid, q, exp in [("T026","甲方拖了半年（180 天）都没付 300 万，得赔多少？",270000.0),
                    ("T027","甲方欠了 8 万货款，晚了 7 天才付，违约金怎么算？",280.0),
                    ("T028","就 5 万块的小额，甲方逾期 3 天没付，赔多少？",75.0)]:
    vals=[]
    for i in range(3):
        _, m = call(q, f"verify_{tid}_{i}")
        vals.append(m.get("computed_penalty"))
    stable = len(set(vals)) == 1 and vals[0] == exp
    print(f"  [{'STABLE' if stable else 'VARY'}] {tid}: {vals} expected={exp}")

print("\n=== 3) 跨轮携带 G3：Q1(100万/万分之五/30天) -> Q2(改成60天呢) ===")
q1 = "甲方逾期付款一百万元，按每日万分之五支付违约金，逾期30天，应支付多少违约金？"
q2 = "改成60天呢"
a1, m1 = call(q1, "verify_g3")
a2, m2 = call(q2, "verify_g3")
print(f"  Q1 computed={m1.get('computed_penalty')} (expect 15000)")
print(f"  Q2 computed={m2.get('computed_penalty')} (expect 300000 via carry)")
print(f"  Q2 rejected={m2.get('rejected')}")

print("\n=== 4) 跨轮携带 G4：Q1(50万/万分之五/20天) -> Q2(本金改200万) ===")
q1b = "甲方逾期付款五十万元，逾期付款违约金按日万分之五，逾期20天要付多少？"
q2b = "再把本金改成200万呢"
a1b, m1b = call(q1b, "verify_g4")
a2b, m2b = call(q2b, "verify_g4")
print(f"  Q1 computed={m1b.get('computed_penalty')} (expect 5000)")
print(f"  Q2 computed={m2b.get('computed_penalty')} (expect 20000 via carry)")
