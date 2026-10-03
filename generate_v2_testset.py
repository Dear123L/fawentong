# -*- coding: utf-8 -*-
"""
从 contract_qa_testset.json 生成 v2（C1-C6 重锚到真实 民法典条款 id）。
- 备份原文件到 baselines/contract_qa_testset_v1_backup.json
- 25 条直接改 expected_clause_ids
- T015/T016/T020/T024 完整重写
- T017 挂 民法典-第680条 并标注扩展层待改
- OOS001-008 不变
输出 contract_qa_testset_v2.json，并打印差异摘要。
"""
import json, shutil, os

BASE = r"D:\Quanta_Back_end\back_project\fawentong"
SRC = os.path.join(BASE, "contract_qa_testset.json")
BAK = os.path.join(BASE, "baselines", "contract_qa_testset_v1_backup.json")
OUT = os.path.join(BASE, "contract_qa_testset_v2.json")

# 1. 备份
shutil.copy(SRC, BAK)
print(f"[backup] {SRC} -> {BAK}")

with open(SRC, encoding="utf-8") as f:
    orig_doc = json.load(f)
doc = json.loads(json.dumps(orig_doc))  # 深拷贝
data = doc["data"]
by_id = {t["id"]: t for t in data}

# 2. 25 条直接改映射（只改 expected_clause_ids）
direct = {
    "T001": ["民法典-第502条"],
    "T002": ["民法典-第502条"],
    "T003": ["民法典-第626条"],                                  # 决策：只挂626（付款期限）
    "T004": ["民法典-第585条"],
    "T005": ["民法典-第590条"],
    "T006": ["民法典-第590条"],
    "T007": ["民法典-第501条"],
    "T008": ["民法典-第501条"],
    "T009": ["民法典-第586条"],
    "T010": ["民法典-第586条", "民法典-第587条"],                 # 决策：586+587
    "T011": ["民法典-第577条"],
    "T012": ["民法典-第577条"],
    "T013": ["民法典-第585条"],
    "T014": ["民法典-第585条"],
    "T018": ["民法典-第585条"],
    "T019": ["民法典-第585条"],
    "T021": ["民法典-第585条"],
    "T022": ["民法典-第585条"],
    "T023": ["民法典-第585条"],
    "T025": ["民法典-第585条"],
    "T026": ["民法典-第585条"],
    "T027": ["民法典-第585条"],
    "T028": ["民法典-第585条"],
    "T029": ["民法典-第585条"],
    "T030": ["民法典-第585条", "民法典-第590条"],
}
for tid, ids in direct.items():
    by_id[tid]["expected_clause_ids"] = ids

# 3. 4 条需重写
by_id["T015"].update({
    "expected_clause_ids": ["民法典-第585条"],
    "calculation_params": {"principal": 0, "daily_rate_per_mille": 0.5, "days": 30, "expected_penalty": 0.0},
    "expected_answer": "本金为 0，违约金 = 0 × (0.5/1000) × 30 = 0.00 元。依据 民法典-第585条（违约金）。参数说明：principal=0 对应'本金是 0'；daily_rate_per_mille=0.5 对应'日万分之五（0.5‰）'；days=30 对应'逾期 30 天'；expected_penalty=0.0。",
})
by_id["T016"].update({
    "expected_clause_ids": ["民法典-第585条"],
    "calculation_params": {"principal": 200000, "daily_rate_per_mille": 0.5, "days": 0, "expected_penalty": 0.0},
    "expected_answer": "逾期天数为 0，违约金 = 200,000 × (0.5/1000) × 0 = 0.00 元。依据 民法典-第585条（违约金）。参数说明：principal=200000 对应'货款 20 万'；daily_rate_per_mille=0.5 对应'日万分之五'；days=0 对应'一天都没逾期'；expected_penalty=0.0。",
})
by_id["T020"].update({
    "expected_clause_ids": ["民法典-第585条"],
    "calculation_params": {"principal": 300000, "daily_rate_per_mille": 1.0, "days": 20, "expected_penalty": 6000.0},
    "expected_answer": "违约金 = 300,000 × (1.0/1000) × 20 = 6000.00 元。依据 民法典-第585条（违约金，当事人可约定违约金）。参数说明：principal=300000 对应'本金 30 万'；daily_rate_per_mille=1.0 对应'日千分之一（1‰）'；days=20 对应'逾期 20 天'；expected_penalty=6000.0。",
})
by_id["T024"].update({
    "type": "retrieve",
    "expected_clause_ids": ["民法典-第577条", "民法典-第585条"],
    "calculation_params": None,
    "expected_answer": "依据 民法典-第577条（违约责任）：当事人一方不履行合同义务或履行不符合约定的，应承担继续履行、采取补救措施或者赔偿损失等违约责任；依据 民法典-第585条（违约金）：当事人可约定违约金。乙方延迟交货构成违约，应承担相应违约责任（具体违约金数额取决于合同是否约定及约定标准）。",
})

# 4. T017 挂 680
by_id["T017"].update({
    "expected_clause_ids": ["民法典-第680条"],
    "calculation_params": {"principal": 1000000, "daily_rate_per_mille": 50, "days": 10, "expected_penalty": 500000.0},
    "expected_answer": "约定日利率 5%（即 50‰，daily_rate_per_mille=50）明显畸高，依 民法典-第680条（禁止高利放贷）该利率不受法律保护；按字面公式违约金 = 1,000,000 × (50/1000) × 10 = 500000.00 元，但属高利贷约定，实际可主张的违约金应依法调减。参数：principal=1000000，daily_rate_per_mille=50，days=10，expected_penalty=500000.0（公式值，法律层面应下调）。",
    "gold_note": "扩展层入库后 gold 改为 民间借贷规定-第25条（利率上限）。",
})

# 5. meta 更新
doc["meta"]["clause_id_mapping"] = {
    "C1": "民法典-第502条（合同生效）",
    "C2": "付款期限=民法典-第626条；违约金=民法典-第585条",
    "C3": "民法典-第590条（不可抗力）",
    "C4": "民法典-第501条（保密义务）",
    "C5": "民法典-第586条（定金上限20%）+ 民法典-第587条（定金罚则）",
    "C6": "民法典-第577条（违约责任）",
}
doc["meta"]["version"] = "v2 (re-anchored to real 民法典 clause ids; C1-C6 synthetic removed)"
doc["meta"]["counts"] = {
    "retrieve": 13, "calculate": 9, "both": 8, "total": 38, "out_of_scope": 8,
}

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=2)
print(f"[write] {OUT}")

# 6. 差异摘要
print("\n========== 差异摘要 ==========")
changed = 0
for t in data:
    tid = t["id"]
    o = next(x for x in orig_doc["data"] if x["id"] == tid)
    diffs = []
    if o.get("type") != t.get("type"):
        diffs.append(f"type: {o.get('type')} -> {t.get('type')}")
    if o.get("expected_clause_ids") != t.get("expected_clause_ids"):
        diffs.append(f"clause_ids: {o.get('expected_clause_ids')} -> {t.get('expected_clause_ids')}")
    if o.get("calculation_params") != t.get("calculation_params"):
        diffs.append("calculation_params: 已更新")
    if o.get("expected_answer") != t.get("expected_answer"):
        diffs.append("expected_answer: 已重写")
    if "gold_note" in t and "gold_note" not in o:
        diffs.append(f"gold_note: {t.get('gold_note')}")
    if diffs:
        changed += 1
        print(f"[{tid}] " + " | ".join(diffs))
print(f"\n变更题数：{changed} / 38（其余 OOS001-008 及问题文本未动）")
