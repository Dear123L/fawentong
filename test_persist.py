import json, urllib.parse, urllib.request

BASE = "http://localhost:8082/api/rag/chatAgent/multiDebug"

def ask(session_id, question):
    qs = urllib.parse.urlencode({"kbId": 1, "sessionId": session_id, "question": question})
    url = f"{BASE}?{qs}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))

def show(tag, q):
    print(f"\n===== {tag}: {q} =====")
    resp = ask("mt_g3", q)
    m = resp.get("meta", {})
    print("rejected      =", m.get("rejected"))
    print("computed_pen  =", m.get("computed_penalty"))
    print("extracted     =", json.dumps(m.get("extracted"), ensure_ascii=False))
    print("retrieved#    =", len(m.get("retrieved") or []))
    print("answer(head)  =", (resp.get("data") or "")[:120].replace("\n", " "))

# Persistence test: question has NO principal/rate/days.
# If memory survived restart, app must read principal=100万 + PENALTY from Redis
# and compute 1000000 * 0.0005 * 90 = 45000.
show("T3 (after restart, clean UTF-8)", "改成90天呢？")
