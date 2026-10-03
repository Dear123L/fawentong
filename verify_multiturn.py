#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ScopeCheckNode「多轮指代追问」守卫的系统验证。
每组两连问，同一 sessionId，检查 Q2 是否正确消解到 Q1 上下文。
走 multiDebug 端点（UTF-8 urllib，避免 GBK 编码问题）。
"""
import json
import urllib.request
import urllib.parse
import time

BASE = "http://localhost:8080/api/rag/chatAgent/multiDebug"
KB = 1

GROUPS = [
    {
        "id": "G1", "cat": "指代(它)", "sid": "verify_g1",
        "q1": "甲方逾期付款一百万元，按每日万分之五支付违约金，逾期30天，应支付多少违约金？",
        "q2": "它对应的条款是第几条？",
        "expect": "in_scope；Q2 应回指 Q1 的逾期付款违约金场景，点明付款期限与逾期违约金条款",
    },
    {
        "id": "G2", "cat": "指代(上面)", "sid": "verify_g2",
        "q1": "保密义务在合同终止后多久内有效？",
        "q2": "上面的期限从什么时候起算？",
        "expect": "in_scope；Q2 应回指 Q1 的保密义务 3 年，从合同终止起算",
    },
    {
        "id": "G3", "cat": "追问(改成60天呢)", "sid": "verify_g3",
        "q1": "甲方逾期付款一百万元，按每日万分之五支付违约金，逾期30天，应支付多少违约金？",
        "q2": "改成60天呢",
        "expect": "in_scope；Q2 应在 Q1(本金100万/日万分之五)基础上重算60天≈30万",
    },
    {
        "id": "G4", "cat": "追问(本金改200万)", "sid": "verify_g4",
        "q1": "甲方逾期付款五十万元，逾期付款违约金按日万分之五，逾期20天要付多少？",
        "q2": "再把本金改成200万呢",
        "expect": "in_scope；Q2 应在 Q1(日万分之五/20天)基础上把本金换成200万≈20万",
    },
    {
        "id": "G5", "cat": "跨域切换(那离婚怎么分)", "sid": "verify_g5",
        "q1": "甲方逾期付款一百万元，按每日万分之五支付违约金，逾期30天，应支付多少违约金？",
        "q2": "那离婚财产怎么分？",
        "expect": "应 out_of_scope 拒答；不消解到 Q1（正确识别跨域，非误拒）",
    },
]


def call(q, sid):
    params = urllib.parse.urlencode({"kbId": KB, "sessionId": sid, "question": q})
    url = BASE + "?" + params
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=180) as r:
        data = json.loads(r.read().decode("utf-8"))
    # 响应结构: {code, data(答案文本), meta}
    return data.get("data", ""), data.get("meta", {})


def main():
    out = []
    for g in GROUPS:
        print(f"\n===== {g['id']} [{g['cat']}] sid={g['sid']} =====")
        try:
            a1, m1 = call(g["q1"], g["sid"])
            print(f"[Q1] rejected={m1.get('rejected')} computed={m1.get('computed_penalty')}")
            print("     ", a1[:240].replace("\n", " "))
            time.sleep(1.5)
            a2, m2 = call(g["q2"], g["sid"])
            print(f"[Q2] rejected={m2.get('rejected')} computed={m2.get('computed_penalty')}")
            print("     ", a2[:480].replace("\n", " "))
            out.append({
                "id": g["id"], "cat": g["cat"], "expect": g["expect"],
                "q1": g["q1"], "a1": a1, "q1_rejected": m1.get("rejected"),
                "q2": g["q2"], "a2": a2, "q2_rejected": m2.get("rejected"),
                "q2_computed": m2.get("computed_penalty"),
            })
        except Exception as e:
            print("ERROR:", repr(e))
            out.append({"id": g["id"], "cat": g["cat"], "error": repr(e)})
        time.sleep(1.0)
    with open("verify_multiturn_result.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n=== saved verify_multiturn_result.json ===")


if __name__ == "__main__":
    main()
