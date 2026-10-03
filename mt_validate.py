# -*- coding: utf-8 -*-
"""G2/G3 多轮记忆验证（针对 Redis 持久化后的实现）。"""
import urllib.request, urllib.parse, json

BASE = "http://localhost:8082/api/rag/chatAgent/multiDebug"


def call(sid, q):
    url = BASE + "?" + urllib.parse.urlencode({"kbId": 1, "sessionId": sid, "question": q})
    with urllib.request.urlopen(url, timeout=90) as r:
        b = json.loads(r.read().decode())
    m = b.get("meta", {}) or {}
    return b.get("data", ""), m


print("=== G3 风格：借贷利率封顶 + 跨轮继承（session=mt_loan）===")
d1, m1 = call("mt_loan", "我向朋友借款100万元，约定年利率18%，借期半年，到期应付利息多少？")
print("T1 computed_penalty=", m1.get("computed_penalty"),
      "extracted=", m1.get("extracted"), "rejected=", m1.get("rejected"))
d2, m2 = call("mt_loan", "改成借60天呢？")
print("T2 computed_penalty=", m2.get("computed_penalty"),
      "extracted=", m2.get("extracted"), "rejected=", m2.get("rejected"))
print("  >> T2 应继承 T1 的本金100万与 calcType=LOAN_INTEREST，重算封顶值（非重新从空抽）")

print()
print("=== G2 风格：指代消解得拒答（session=mt_g2）===")
d1b, m1b = call("mt_g2", "合同里逾期付款的违约金一般怎么算？")
print("T1 rejected=", m1b.get("rejected"))
d2b, m2b = call("mt_g2", "它和滞纳金有什么区别？")
print("T2 rejected=", m2b.get("rejected"),
      "（期望 False：入口守卫结合历史放行指代追问，不被误拒）")
