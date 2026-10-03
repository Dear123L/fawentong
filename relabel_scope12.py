# -*- coding: utf-8 -*-
"""把 v3 测试集按 2026-10-02 生效的『12 法域』口径重标：
- 12 道陈旧 OOS（离婚/工伤/继承/交通事故/试用期等，实为 12 法域内）-> 标为在范围，附 gold clause id
- 11 道真·域外（商标/股东/税务/消保）-> 维持 out_of_scope
输出 contract_qa_testset_v3_scope12.json，用于诚实评测新范围。
"""
import json

SRC = "contract_qa_testset_v3.json"
DST = "contract_qa_testset_v3_scope12.json"

# 12 道陈旧 OOS -> 在范围，gold clause id（合法正确条款，用于 HR 测算）
RELABEL_IN = {
    "T073": ["民法典-第1087条"],          # 离婚财产分割
    "T174": ["民法典-第1092条"],          # 离婚隐瞒房产
    "T182": ["民法典-第1092条"],          # 离婚隐瞒房产(重复变体)
    "T076": ["社会保险法-第36条"],         # 工伤用人单位责任
    "T177": ["社会保险法-第36条"],         # 工伤未缴保险
    "T078": ["民法典-第1161条"],          # 继承债务清偿
    "T187": ["民法典-第1161条"],          # 继承债务范围
    "T079": ["民法典-第1213条"],          # 交通事故理赔
    "T180": ["民法典-第1213条"],          # 交通事故认定书
    "T188": ["民法典-第1213条"],          # 交通事故无过错赔付
    "T179": ["民法典-第1123条"],          # 遗嘱继承冲突
    "T185": ["劳动合同法-第39条"],        # 试用期解除
}

ts = json.load(open(SRC, encoding="utf-8"))
data = ts["data"]
relabeled = 0
for t in data:
    tid = t["id"]
    if tid in RELABEL_IN:
        t["type"] = "in_scope"
        t["expected_clause_ids"] = RELABEL_IN[tid]
        # 清除旧的应拒答标记（如有）
        if "应拒答" in (t.get("expected_answer") or ""):
            t["expected_answer"] = t["expected_answer"].replace("应拒答", "应作答")
        relabeled += 1
    # 其余维持原样（含 11 道真·OOS 仍为空 expected_clause_ids + out_of_scope）

print("重标为在范围:", relabeled, "条")
oos_left = [t["id"] for t in data if t.get("type") == "out_of_scope" or not t.get("expected_clause_ids")]
print("剩余 out_of_scope:", len(oos_left), oos_left)
json.dump(ts, open(DST, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("已写出", DST)
