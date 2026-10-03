# -*- coding: utf-8 -*-
"""跨领域检索探针：确认 ScopeCheck 放行全部 12 法域后，跨域题能检索到对应条款；
真正 ES 缺失的域（商标/股东/税务）仍被拒。"""
import json, urllib.parse, urllib.request

BASE = "http://localhost:8082/api/rag/chatAgent/multiDebug"
QUESTIONS = [
    ("试用期辞退合法吗", "in:劳动合同法"),
    ("离婚财产怎么分", "in:民法典婚姻编"),
    ("工伤认定标准是什么", "in:社保法/工伤保险"),
    ("民间借贷利率上限多少", "in:民间借贷规定"),
    ("继承开始后遗产怎么分配", "in:民法典继承编"),
    ("商标侵权怎么赔偿", "out:商标(HARD_OOS)"),
    ("股东分红纠纷怎么处理", "out:股东(HARD_OOS)"),
    ("虚开发票有什么法律后果", "out:税务(HARD_OOS)"),
]

def call(q, sid):
    url = f"{BASE}?kbId=1&sessionId={urllib.parse.quote(sid)}&question={urllib.parse.quote(q)}"
    req = urllib.request.Request(url, headers={"Authorization": "Bearer test"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        return {"_error": str(e)}

for i, (q, exp) in enumerate(QUESTIONS):
    sid = f"probe_xd_{i}"
    try:
        data = call(q, sid)
    except Exception as e:
        print(f"[ERR] {q}: {e}")
        continue
    meta = data.get("meta", {})
    rej = meta.get("rejected")
    retrieved = meta.get("retrieved") or []
    top = []
    for it in retrieved[:3]:
        cid = it.get("clauseId") or it.get("id") or "?"
        src = it.get("sourceKey") or it.get("source") or ""
        top.append(f"{cid}/{src}")
    answer = (data.get("answer") or data.get("ragAnswer") or "")[:60]
    print(f"Q={q!r} 期望={exp}")
    print(f"   rejected={rej}  top_retrieved={top}")
    print(f"   answer={answer!r}")
    print()
