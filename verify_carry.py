#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, urllib.request, urllib.parse

BASE = "http://localhost:8080/api/rag/chatAgent/multiDebug"
def call(q, sid):
    url = BASE + "?" + urllib.parse.urlencode({"kbId":1,"sessionId":sid,"question":q})
    with urllib.request.urlopen(url, timeout=180) as r:
        d = json.loads(r.read().decode("utf-8"))
    return d.get("data",""), d.get("meta",{})

print("=== G3: Q1(100万/万分之五/30天) -> Q2(改成60天呢) ===")
q1="甲方逾期付款一百万元，按每日万分之五支付违约金，逾期30天，应支付多少违约金？"
q2="改成60天呢"
a1,m1=call(q1,"carry_g3"); a2,m2=call(q2,"carry_g3")
print(f"  Q1 computed={m1.get('computed_penalty')} expect 15000")
print(f"  Q2 computed={m2.get('computed_penalty')} expect 300000 (inherit 100万/0.5‰)")
print(f"  Q2 rejected={m2.get('rejected')}  -> {'PASS' if m2.get('computed_penalty')==300000.0 else 'FAIL'}")

print("=== G4: Q1(50万/万分之五/20天) -> Q2(本金改200万) ===")
q1b="甲方逾期付款五十万元，逾期付款违约金按日万分之五，逾期20天要付多少？"
q2b="再把本金改成200万呢"
a1b,m1b=call(q1b,"carry_g4"); a2b,m2b=call(q2b,"carry_g4")
print(f"  Q1 computed={m1b.get('computed_penalty')} expect 5000")
print(f"  Q2 computed={m2b.get('computed_penalty')} expect 20000 (inherit 0.5‰/20天)")
print(f"  Q2 rejected={m2b.get('rejected')}  -> {'PASS' if m2b.get('computed_penalty')==20000.0 else 'FAIL'}")
