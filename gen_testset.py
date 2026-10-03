# -*- coding: utf-8 -*-
"""生成 30 条合同/法规知识库测试问答对。
知识库来源：rag_agent/multi_agent_rag.py 的 CORPUS（6 条演示条款），编号 C1-C6。
违约金公式：penalty = principal * (daily_rate_per_mille / 1000) * days
"""
import json

# 知识库条款（取自 rag_agent/multi_agent_rag.py CORPUS），编号 C1-C6
KB = {
    "C1": "本合同自双方签字盖章之日起生效，有效期为三年。",
    "C2": "甲方应在货物交付后三十日内支付全部价款，逾期按日万分之五支付违约金。",  # 日利率 = 0.5‰
    "C3": "因不可抗力导致不能履行合同的，根据不可抗力影响部分或全部免除责任。",
    "C4": "保密条款在本合同终止后三年内继续有效。",
    "C5": "定金不得超过主合同标的额的百分之二十，超过部分不产生定金效力。",
    "C6": "当事人一方不履行合同义务或者履行合同义务不符合约定的，应当承担继续履行、采取补救措施或者赔偿损失等违约责任。",
}

pairs = []


def add(pid, ptype, question, answer, clauses, params):
    pairs.append({
        "id": pid,
        "type": ptype,
        "question": question,
        "expected_answer": answer,
        "expected_clause_ids": clauses,
        "calculation_params": params,
    })


# ----------------------------- 纯检索类（12 条）-----------------------------
add("T001", "retrieve",
    "合同签完字啥时候开始生效？有效期是几年？",
    "依据 C1：合同自双方签字盖章之日起生效，有效期为三年。",
    ["C1"], None)

add("T002", "retrieve",
    "我们双方都盖章了，这合同立马就算生效了吗？",
    "依据 C1：是的，合同自双方签字盖章之日起生效。",
    ["C1"], None)

add("T003", "retrieve",
    "甲方最晚得在多少天内把货款付了？",
    "依据 C2：甲方应在货物交付后三十日内支付全部价款。",
    ["C2"], None)

add("T004", "retrieve",
    "逾期付款的违约金按什么比例算的？",
    "依据 C2：逾期按日万分之五（即每日 0.5‰）支付违约金。",
    ["C2"], None)

add("T005", "retrieve",
    "要是碰上地震这种不可抗力，没法履行合同还得赔钱担责不？",
    "依据 C3：因不可抗力导致不能履行合同的，根据不可抗力影响部分或全部免除责任。",
    ["C3"], None)

add("T006", "retrieve",
    "不可抗力是能全部免责，还是只能免掉一部分啊？",
    "依据 C3：根据不可抗力的影响，可以部分免除，也可以全部免除责任。",
    ["C3"], None)

add("T007", "retrieve",
    "合同都终止了，之前的保密义务还算不算数？",
    "依据 C4：保密条款在本合同终止后三年内继续有效。",
    ["C4"], None)

add("T008", "retrieve",
    "保密条款到底管几年？",
    "依据 C4：保密条款在合同终止后继续有效三年。",
    ["C4"], None)

add("T009", "retrieve",
    "定金最高能约定多少？有没有上限？",
    "依据 C5：定金不得超过主合同标的额的百分之二十。",
    ["C5"], None)

add("T010", "retrieve",
    "定金要是约定了标的额的 30%，超出的部分有效吗？",
    "依据 C5：定金超过主合同标的额百分之二十的部分不产生定金效力（即超出部分无效）。",
    ["C5"], None)

add("T011", "retrieve",
    "对方明明不履行合同，我能要求他继续把事情干完吗？",
    "依据 C6：一方不履行合同义务的，对方可要求其承担继续履行的违约责任。",
    ["C6"], None)

add("T012", "retrieve",
    "一方违约了，一般都要承担哪些责任？",
    "依据 C6：应当承担继续履行、采取补救措施或者赔偿损失等违约责任。",
    ["C6"], None)

# ----------------------------- 纯计算类（9 条）-----------------------------
# 金额0 / 天数0 / 利率异常高 边界已含（T015/T016/T017）


def calc_penalty(principal, rate, days):
    return round(principal * (rate / 1000.0) * days, 2)


# T013 日万分之五(0.5)，30天
p, r, d = 1000000, 0.5, 30
add("T013", "calculate",
    "本金 100 万，日万分之五，逾期 30 天，违约金得多少？",
    f"违约金 = 1,000,000 × (0.5/1000) × 30 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T014 日万分之五，15天
p, r, d = 500000, 0.5, 15
add("T014", "calculate",
    "我欠 50 万货款，按日万分之五算，拖了 15 天要赔多少？",
    f"违约金 = 500,000 × (0.5/1000) × 15 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T015 金额为 0（边界）
p, r, d = 0, 0.5, 30
add("T015", "calculate",
    "本金是 0 的话，逾期 30 天违约金是多少？",
    f"本金为 0，违约金 = 0 × (0.5/1000) × 30 = {calc_penalty(p,r,d):.2f} 元（即 0 元）。",
    [],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T016 天数为 0（边界）
p, r, d = 200000, 0.5, 0
add("T016", "calculate",
    "货款 20 万，日万分之五，但一天都没逾期，违约金多少？",
    f"逾期天数为 0，违约金 = 200,000 × (0.5/1000) × 0 = {calc_penalty(p,r,d):.2f} 元（即 0 元）。",
    [],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T017 利率异常高（日 5%，即 50‰）（边界）
p, r, d = 1000000, 50, 10
add("T017", "calculate",
    "本金 100 万，日利率高达 5%（千分比 50），借了 10 天，违约金多少？",
    f"违约金 = 1,000,000 × (50/1000) × 10 = {calc_penalty(p,r,d):.2f} 元（日利率 5% 明显异常偏高）。",
    [],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T018 日万分之五，45天
p, r, d = 2000000, 0.5, 45
add("T018", "calculate",
    "200 万本金，日万分之五，逾期一个半月（45 天）要赔多少？",
    f"违约金 = 2,000,000 × (0.5/1000) × 45 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T019 小额，日万分之五，7天
p, r, d = 80000, 0.5, 7
add("T019", "calculate",
    "就 8 万块钱，日万分之五，晚了 7 天付款，违约金多少？",
    f"违约金 = 80,000 × (0.5/1000) × 7 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T020 日千分之一（1.0），20天
p, r, d = 300000, 1.0, 20
add("T020", "calculate",
    "本金 30 万，约定日千分之一，逾期 20 天违约金多少？",
    f"违约金 = 300,000 × (1.0/1000) × 20 = {calc_penalty(p,r,d):.2f} 元（注：日千分之一非知识库 C2 条款，仅作计算校验）。",
    [],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T021 日万分之五，60天
p, r, d = 1500000, 0.5, 60
add("T021", "calculate",
    "150 万货款，日万分之五，拖了两个月（60 天）得赔多少？",
    f"违约金 = 1,500,000 × (0.5/1000) × 60 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# ----------------------------- 混合类（9 条）-----------------------------
# 既要查条款又要算金额；T024 为条款未约定（边界）

# T022
p, r, d = 1000000, 0.5, 30
add("T022", "both",
    "甲方逾期了，100 万货款拖了 30 天没付，按合同得赔多少？",
    f"依据 C2（逾期按日万分之五支付违约金）：本金 1,000,000、逾期 30 天，违约金 = 1,000,000 × (0.5/1000) × 30 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T023
p, r, d = 500000, 0.5, 20
add("T023", "both",
    "我方是甲方，晚付了 50 万货款，拖了 20 天，违约金多少？",
    f"依据 C2（逾期按日万分之五）：本金 500,000、逾期 20 天，违约金 = 500,000 × (0.5/1000) × 20 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T024 条款未约定（边界）：乙方延迟交货，知识库无对应违约金条款
add("T024", "both",
    "我是乙方，我延迟交货了，要赔甲方多少违约金？",
    "知识库未覆盖，应拒答（知识库 C2 仅约定甲方逾期付款的违约金，未约定乙方延迟交货的违约金；C6 仅列责任形式无具体金额，无法计算）。",
    [], None)

# T025
p, r, d = 2000000, 0.5, 10
add("T025", "both",
    "甲方逾期 10 天没付 200 万货款，按合同赔多少？",
    f"依据 C2（逾期按日万分之五）：本金 2,000,000、逾期 10 天，违约金 = 2,000,000 × (0.5/1000) × 10 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T026
p, r, d = 3000000, 0.5, 180
add("T026", "both",
    "甲方拖了半年（180 天）都没付 300 万，得赔多少？",
    f"依据 C2（逾期按日万分之五）：本金 3,000,000、逾期 180 天，违约金 = 3,000,000 × (0.5/1000) × 180 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T027
p, r, d = 80000, 0.5, 7
add("T027", "both",
    "甲方欠了 8 万货款，晚了 7 天才付，违约金怎么算？",
    f"依据 C2（逾期按日万分之五）：本金 80,000、逾期 7 天，违约金 = 80,000 × (0.5/1000) × 7 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T028
p, r, d = 50000, 0.5, 3
add("T028", "both",
    "就 5 万块的小额，甲方逾期 3 天没付，赔多少？",
    f"依据 C2（逾期按日万分之五）：本金 50,000、逾期 3 天，违约金 = 50,000 × (0.5/1000) × 3 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T029
p, r, d = 1500000, 0.5, 60
add("T029", "both",
    "甲方逾期付款，本金 150 万，逾期了 60 天，按合同算多少违约金？",
    f"依据 C2（逾期按日万分之五）：本金 1,500,000、逾期 60 天，违约金 = 1,500,000 × (0.5/1000) × 60 = {calc_penalty(p,r,d):.2f} 元。",
    ["C2"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": calc_penalty(p, r, d)})

# T030 混合+不可抗力：逾期但遇不可抗力，应结合 C2 与 C3 认定
p, r, d = 1000000, 0.5, 30
add("T030", "both",
    "甲方因为不可抗力逾期付了 100 万货款 30 天，这违约金还要不要赔？",
    "依据 C2（逾期按日万分之五，理论金额 = 1,000,000 × (0.5/1000) × 30 = 15,000.00 元）；但依据 C3，因不可抗力不能履行的，可部分或全部免除责任。最终是否赔付及赔付多少，需根据不可抗力对逾期的影响认定，不能机械按公式直接计算。",
    ["C2", "C3"],
    {"principal": p, "daily_rate_per_mille": r, "days": d, "expected_penalty": 0.00})

# ----------------------------- 落盘 -----------------------------
out = {
    "meta": {
        "knowledge_base_source": "rag_agent/multi_agent_rag.py -> CORPUS（6 条演示条款）",
        "clause_id_mapping": KB,
        "penalty_formula": "penalty = principal * (daily_rate_per_mille / 1000) * days",
        "rate_note": "日万分之五 = 0.5‰，故 daily_rate_per_mille=0.5；注意 multi_agent_rag.py:131 注释写'日万分之五填5'会按本公式得到10倍错误值",
        "counts": {"retrieve": 12, "calculate": 9, "both": 9, "total": len(pairs)},
        "edge_cases": {
            "principal_0": "T015",
            "days_0": "T016",
            "abnormal_high_rate": "T017",
            "clause_not_stipulated": "T024",
        },
    },
    "data": pairs,
}

with open("contract_qa_testset.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)

print("生成完成，共", len(pairs), "条")
print("类型统计：", {t: sum(1 for x in pairs if x['type'] == t) for t in ['retrieve', 'calculate', 'both']})
# 校验：重算所有带 calculation_params 的 penalty
bad = []
for x in pairs:
    cp = x.get("calculation_params")
    if cp:
        exp = round(cp["principal"] * (cp["daily_rate_per_mille"] / 1000.0) * cp["days"], 2)
        if abs(exp - cp["expected_penalty"]) > 0.001:
            bad.append((x["id"], exp, cp["expected_penalty"]))
print("金额校验异常：", bad if bad else "无")
