import json, urllib.request, urllib.parse, time

BASE = "http://localhost:8080/api/rag/chatAgent/multiDebug"

def call(q, sid, timeout=180):
    url = BASE + "?" + urllib.parse.urlencode({"kbId": 1, "sessionId": sid, "question": q})
    with urllib.request.urlopen(url, timeout=timeout) as r:
        d = json.loads(r.read().decode("utf-8"))
    return d.get("data", ""), d.get("meta", {})

def show(tag, a, m):
    comp = m.get("computed_penalty")
    ext = m.get("extractedParams")
    rej = m.get("rejected")
    print(f"  [{tag}] rejected={rej} computed_penalty={comp}")
    print(f"         answer: {a[:240]}")
    print(f"         extracted: {ext}")
    print()

print("===== G3: 本金100万/日万分之五/30天 -> 改成60天呢 (应继承本金=30,000) =====")
a1, m1 = call("甲方逾期付款100万元，每日万分之五支付违约金，逾期30天，应支付多少违约金？", "P0G3")
show("Q1", a1, m1)
time.sleep(1.5)
a2, m2 = call("改成60天呢", "P0G3")
show("Q2", a2, m2)
# 期望：Q2 computed=30000（3万），且 answer 含 1,000,000
g3_ok = (m2.get("computed_penalty") == 30000.0) and ("1,000,000" in a2 or "1000000" in a2 or "100万" in a2)
print(f"  >>> G3 跨轮继承本金: {'PASS' if g3_ok else 'FAIL'} (computed_penalty={m2.get('computed_penalty')})\n")

print("===== G2: 保密义务期限 -> 上面 (锚定应回指保密3年) =====")
a1b, m1b = call("合同终止后，保密义务还有效吗？", "P0G2")
show("Q1", a1b, m1b)
time.sleep(1.5)
a2b, m2b = call("上面的期限是多久？", "P0G2")
show("Q2", a2b, m2b)
g2_anchor = ("3年" in a2b or "三年" in a2b) and ("保密" in a2b)
print(f"  >>> G2 锚定回指保密: {'PASS' if g2_anchor else 'CHECK'} (含保密+3年={'保密' in a2b and ('3年' in a2b or '三年' in a2b)})\n")

print("ALL DONE")
