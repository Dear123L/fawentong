# -*- coding: utf-8 -*-
"""
评测执行脚本：读取 contract_qa_testset.json（38 条），
逐条调用 GET /api/rag/chatAgent/multiDebug?kbId=1&sessionId=<id>&question=...
把返回 answer + meta 整理成 eval_scorer.py 需要的 results.json 格式：
  {id, retrieved:[{clauseId,score}], extracted:{principal,rate,days},
   computed_penalty, rejected, replanned, answer, faithful_score, relevant_score}
（faithful/relevant 由 LLM 评委另填，这里置 None）
"""
import json
import sys
import time
import urllib.request
import urllib.error
import urllib.parse

BASE = "http://localhost:8080/api/rag"
KB_ID = 1
TESTSET = "contract_qa_testset.json"
OUT = "results.json"


def call_multi_debug(question, session_id, retries=2):
    params = urllib.parse.urlencode({
        "kbId": KB_ID,
        "sessionId": session_id,
        "question": question,
    })
    url = f"{BASE}/chatAgent/multiDebug?{params}"
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=180) as r:
                obj = json.loads(r.read().decode("utf-8"))
            return obj
        except Exception as e:
            if attempt < retries:
                print(f"  [retry {attempt + 1}] {e}")
                time.sleep(3)
            else:
                return {"code": 500, "data": "", "meta": {"error": str(e)}}


def normalize_extracted(raw):
    """把多智能体返回的 extracted 参数统一成 {principal, rate, days}。
    rate 对应 daily_rate_per_mille。兼容多种键名。"""
    if not isinstance(raw, dict):
        return None
    principal = raw.get("principal")
    days = raw.get("days")
    rate = (raw.get("daily_rate_per_mille")
            if raw.get("daily_rate_per_mille") is not None
            else raw.get("dailyRate")
            if raw.get("dailyRate") is not None
            else raw.get("rate")
            if raw.get("rate") is not None
            else raw.get("daily_rate"))
    # 清洗：保证数值
    try:
        principal = float(principal) if principal is not None else None
    except Exception:
        principal = None
    try:
        days = int(days) if days is not None else None
    except Exception:
        days = None
    try:
        rate = float(rate) if rate is not None else None
    except Exception:
        rate = None
    if principal is None and days is None and rate is None:
        return None
    return {"principal": principal, "rate": rate, "days": days}


def main():
    with open(TESTSET, encoding="utf-8") as f:
        ts = json.load(f)
    items = ts["data"] if "data" in ts else ts

    results = []
    for idx, t in enumerate(items, 1):
        qid = t["id"]
        question = t["question"]
        print(f"[{idx}/{len(items)}] {qid}: {question[:30]}...")
        resp = call_multi_debug(question, qid)
        meta = resp.get("meta", {}) or {}
        answer = resp.get("data", "")

        # retrieved: 取 clauseId，给出占位 score（评测只看排序与命中）
        retrieved = []
        raw_ret = meta.get("retrieved") or []
        for i, doc in enumerate(raw_ret):
            if isinstance(doc, dict) and doc.get("clauseId"):
                retrieved.append({"clauseId": str(doc["clauseId"]), "score": round(1.0 - i * 0.001, 4)})

        extracted = normalize_extracted(meta.get("extracted"))
        computed = meta.get("computed_penalty")
        try:
            computed = float(computed) if computed is not None else None
        except Exception:
            computed = None

        results.append({
            "id": qid,
            "retrieved": retrieved,
            "extracted": extracted,
            "computed_penalty": computed,
            "rejected": bool(meta.get("rejected", False)),
            "replanned": bool(meta.get("replanned", False)),
            "answer": answer if isinstance(answer, str) else json.dumps(answer, ensure_ascii=False),
            "faithful_score": None,
            "relevant_score": None,
        })

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n已写入 {OUT}（{len(results)} 条）")


if __name__ == "__main__":
    main()
