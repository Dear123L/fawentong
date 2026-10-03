# -*- coding: utf-8 -*-
"""生成 30 道『非条款反推』独立命题（盲命题集 blind test-set）。

方法：先让 LLM 生成真实用户口语问法，再独立从合同编条款池盲标 gold（允许多条款）。
与 kb_seed_v3.py 的 clause->question 反推相反，本脚本是 question->gold 正推，
用于估计真实检索水位（剔除"问题含条款词→trivial 命中"的偏易因素）。

复用 kb_seed_v3 的 es_fetch_all / is_contract_clause / call_llm / extract_json。
产出 contract_qa_testset_blind30.json（独立文件，不污染 v3 主集），schema 兼容 eval_scorer。
"""
import json
import kb_seed_v3 as K


def build_pool():
    """拉取 ES 全部合同编(463-988)条款 canonicalId 作为可选 gold 池。"""
    allc = K.es_fetch_all()
    contract = [c["canon"] for c in allc if K.is_contract_clause(c.get("canon", ""))]
    return contract


def main():
    pool = build_pool()
    print("合同编条款数(可选 gold 池):", len(pool))

    system = (
        "你是资深民法合同编专家与法律检索评测设计师。"
        "任务：构造用于评测 RAG 检索系统的『真实用户问法』问题集。"
        "核心原则：问题必须从真实生活场景出发，绝不能直接从法条反推。"
    )
    user = (
        "请生成 30 道**真实用户口吻**的合同相关法律咨询问题，用于评测检索系统。\n\n"
        "要求：\n"
        "1. 先想象一个真实发生的合同纠纷/咨询场景（买卖、租赁、借款、承揽、运输、"
        "建设工程、债权转让、保管、委托等），再用普通人的口语描述——可以含糊、带情绪、"
        "多个事实交织、不严谨。\n"
        "2. **严格禁止**：出现『根据第X条』『依据民法典』『按照合同法』等法律法规引用式表述；"
        "禁止把法条术语直接当问题主干（如不要写『民法典第585条规定的违约金……』）。\n"
        "3. 每道题**独立判断**：这个场景在《民法典》合同编（第463–988条）中合理涉及哪些具体条款？"
        "从下方【条款池】中选择 **1-2 个最相关的核心条款**（按相关性排序，"
        "只选真正命中问题焦点的，不要凑数；最多 2 个）。同时标注：\n"
        "   - gold_clause_type：C1(生效)/C2(违约金·逾期)/C3(不可抗力)/C4(保密)/C5(定金·标的额)/C6(违约一般责任)\n"
        "   - query_pattern：single(单条款)/multi(需多条款联合)/compare(对比)\n"
        "   - difficulty：L2(口语)/L3(多事实交织)/L4(极度含糊需推断)\n"
        "4. 全部 30 道都应是**合同编范围内**的真实问题（不要出域外题）。\n\n"
        "【条款池】（仅可从中选择 gold，共 %d 条）：\n%s\n\n"
        "返回 JSON 数组，每项严格：\n"
        "{\"question\":\"...\",\"gold_clause_ids\":[\"民法典-第N条\",...],"
        "\"gold_clause_type\":\"Cx\",\"query_pattern\":\"...\",\"difficulty\":\"...\"}\n"
        "只返回 JSON 数组，不要任何解释。"
        % (len(pool), "\n".join("- " + p for p in pool))
    )

    raw = K.call_llm(system, user)
    items = K.extract_json(raw)
    if not isinstance(items, list):
        raise RuntimeError("LLM 返回解析失败: " + str(raw)[:300])

    valid = set(pool)
    out = []
    for i, it in enumerate(items[:30], 1):
        golds = [g for g in it.get("gold_clause_ids", []) if g in valid][:2]
        if not golds:
            print("[warn] B%03d gold 全无效，跳过: %s" % (i, it.get("question", "")[:30]))
            continue
        out.append({
            "id": "B%03d" % i,
            "question": it["question"].strip(),
            "expected_clause_ids": golds,
            "gold_clause_type": it.get("gold_clause_type", "C6"),
            "query_pattern": it.get("query_pattern", "single"),
            "type": "retrieve",
            "difficulty": it.get("difficulty", "L2"),
            "generated_by": "gen_blind.py (盲命题/正推)",
        })

    print("有效题数:", len(out))
    if len(out) < 30:
        print("[warn] 有效题 < 30，考虑补生成")
    doc = {
        "meta": {
            "desc": "非条款反推独立命题集（question->gold 正推），用于估计真实检索水位",
            "n": len(out),
        },
        "data": out,
    }
    with open("contract_qa_testset_blind30.json", "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    print("written contract_qa_testset_blind30.json")


if __name__ == "__main__":
    main()
