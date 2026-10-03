# -*- coding: utf-8 -*-
"""
kb_seed_v3.py — 法问通评测集 KB 播种脚本（v2 -> v3 第一批，+42 题）

功能：
  1. 从 ES(rag_knowledge_base, 2186 条法典法条) 拉取全部条款；
  2. 按关键词把法条归类到 C1-C6（合同六类），排除 v2 已用过的 gold（防泄漏）；
  3. 按 单跳40%/多跳25%/对比15%/超范围20% 分配 42 题，调用 DashScope LLM 生成口语化问题草稿；
  4. 输出与 v2 兼容的草稿 testset_v3_draft.json（新增 difficulty / gold_clause_type / query_pattern 字段，eval_scorer 忽略未知字段，向后兼容）；
  5. 同时生成人工核验清单 testset_v3_核验清单.md。

注意：本脚本只【生成草稿】，不修改 v2，也不自动合入。人工核验 gold 与答案后再由用户合入。

运行：
  python kb_seed_v3.py                # 全量生成 42 题
  python kb_seed_v3.py --single-only # 只重跑单跳（强制 L1/L2），保留多跳/对比/OOS 不动

依赖：仅 Python 标准库（urllib / json / re / argparse）。
"""
import json, re, sys, time, argparse, urllib.request, urllib.error
from collections import Counter

# ----------------------------- 配置 -----------------------------
ES_URL = "http://localhost:9200/rag_knowledge_base/_search?size=2186"
V2_PATH = "contract_qa_testset_v2.json"
OUT_DRAFT = "testset_v3_draft.json"
OUT_CHECK = "resume-output/testset_v3_核验清单.md"

TOTAL_NEW = 42
N_SINGLE, N_MULTI, N_COMPARE, N_OOS = 17, 10, 7, 8   # = 42 ; 40/25/15/20

# DashScope（OpenAI 兼容端点）。key 默认从 application.yml 读取，可用环境变量覆盖。
DASH_KEY = None
MODEL = "qwen-flash"
try:
    import os
    DASH_KEY = os.environ.get("DASHSCOPE_API_KEY")
except Exception:
    pass
if not DASH_KEY:
    try:
        yml = open("src/main/resources/application.yml", encoding="utf-8").read()
        m = re.search(r"key:\s*(sk-\S+)", yml)
        if m:
            DASH_KEY = m.group(1)
    except Exception as e:
        print("[warn] 无法从 application.yml 读取 key:", e)

# C1-C6 关键词（优先级从高到低；命中即归类）
CATEGORY_RULES = [
    ("C3", ["不可抗力"]),
    ("C4", ["保密"]),
    ("C5", ["定金", "订金", "标的额", "百分之二十", "百分之三十"]),
    ("C2", ["违约金", "逾期", "日万分之", "迟延支付", "付款期限", "利息"]),
    ("C1", ["合同生效", "自双方", "签字", "盖章", "合同成立", "有效期"]),
    ("C6", ["违约", "继续履行", "采取补救措施", "赔偿损失", "违约责任"]),
]
# 仅保留合同相关（民法典）法条作为 in-domain 池；其余法典不进 C1-C6
IN_DOMAIN_KEYWORDS = ["合同", "买卖", "借款", "租赁", "承揽", "保管", "委托", "赠与",
                      "定金", "违约", "不可抗力", "保密", "逾期", "标的额", "价款",
                      "民法典", "合同法"]

# 严格合同编过滤器：gold 只能落在「民法典-第N条」且 463<=N<=988（=合同编全文）
# 排除：民间借贷规定 / 民事诉讼法 / 劳动争议解释 / 社会保险法 / 诉讼费用交纳办法 /
#       合同编通则解释（司法解释，非民法典正文）等一切非民法典条款。
CONTRACT_RE = re.compile(r"^民法典-第(\d+)条$")
def is_contract_clause(canon):
    if not canon:
        return False
    m = CONTRACT_RE.match(canon)
    if not m:
        return False
    n = int(m.group(1))
    return 463 <= n <= 988

def valid_gold_ids(ids):
    """gold 全部为合同编(463-988)才合法；OOS（空）视为合法。"""
    if not ids:
        return True
    for g in ids:
        if not is_contract_clause(g):
            return False
    return True

# 单跳每类配额（合计 17）
SINGLE_QUOTA = {"C1": 3, "C2": 3, "C3": 3, "C4": 2, "C5": 3, "C6": 3}
# 多跳配对方案（合计 10）：mostly calc
MULTI_PAIRS = [
    ("C2", "C5"), ("C2", "C5"), ("C2", "C5"),
    ("C2", "C1"), ("C2", "C1"),
    ("C5", "C6"), ("C5", "C6"),
    ("C1", "C4"), ("C3", "C6"), ("C2", "C6"),
]
# 对比配对方案（合计 7，同类不同法条对比）
COMPARE_PAIRS = [
    ("C2", "C2"), ("C2", "C2"),
    ("C5", "C5"), ("C5", "C5"),
    ("C1", "C1"), ("C6", "C6"), ("C4", "C4"),
]

# ============================================================
# 扩容到 150 配置（v3 第一批 47 题 -> +103 题 = 150）
# ============================================================
# 每条款作为 gold 出现次数上限（跨全 150 集统计；旧 47 已超频的 8 条整体排除于新生成）
MAX_CLAUSE_USE = 2
# 第一批 47 题中过度集中的 8 个条款：新生成一律避开（不再新增它们的曝光）
EXCLUDED_CLAUSES = {
    "民法典-第513条", "民法典-第545条", "民法典-第470条", "民法典-第483条",
    "民法典-第584条", "民法典-第509条", "民法典-第588条", "民法典-第563条",
}
# 扩容配额（+103）
EXP_SINGLE, EXP_MULTI, EXP_COMPARE, EXP_OOS = 58, 15, 15, 15
# 扩容单跳按类别分配（按 SINGLE_QUOTA 比例放大到 58）
EXP_SINGLE_QUOTA = {"C1": 10, "C2": 10, "C3": 10, "C4": 7, "C5": 10, "C6": 11}  # = 58
# 扩容多跳配对（15 对，含 C2/C5 计算组合）
EXP_MULTI_PAIRS = [
    ("C2", "C5"), ("C2", "C5"), ("C2", "C5"),
    ("C2", "C1"), ("C2", "C1"),
    ("C5", "C6"), ("C5", "C6"),
    ("C1", "C4"), ("C3", "C6"), ("C2", "C6"),
    ("C2", "C5"), ("C2", "C1"), ("C5", "C6"), ("C1", "C4"), ("C3", "C6"),
]
# 扩容对比配对（15 对，同类不同法条对比）
EXP_COMPARE_PAIRS = [
    ("C2", "C2"), ("C2", "C2"), ("C5", "C5"), ("C5", "C5"), ("C1", "C1"),
    ("C6", "C6"), ("C4", "C4"), ("C2", "C2"), ("C5", "C5"), ("C1", "C1"),
    ("C6", "C6"), ("C4", "C4"), ("C2", "C5"), ("C1", "C6"), ("C3", "C3"),
]

# ----------------------------- 工具 -----------------------------
def es_fetch_all():
    req = urllib.request.Request(ES_URL, headers={"Content-Type": "application/json"})
    d = json.load(urllib.request.urlopen(req, timeout=30))
    out = []
    for h in d["hits"]["hits"]:
        s = h["_source"]
        out.append({
            "canon": s.get("canonicalId") or "",
            "content": (s.get("content") or "").strip(),
            "source": s.get("sourceKey") or "",
        })
    return out

def classify(clause):
    txt = clause["content"]
    if not any(k in txt for k in IN_DOMAIN_KEYWORDS):
        return None
    for cat, kws in CATEGORY_RULES:
        if any(k in txt for k in kws):
            return cat
    return None

def load_v2_used():
    d = json.load(open(V2_PATH, encoding="utf-8"))
    used = set()
    for it in d["data"]:
        for g in (it.get("expected_clause_ids") or []):
            used.add(g)
    return d, used

def call_llm(system, user, retries=3):
    if not DASH_KEY:
        raise RuntimeError("缺少 DashScope key（设置 DASHSCOPE_API_KEY 或确保 application.yml 可读）")
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": 0.85,
    }).encode("utf-8")
    req = urllib.request.Request(
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        data=body,
        headers={"Authorization": "Bearer " + DASH_KEY,
                 "Content-Type": "application/json"},
        method="POST",
    )
    last = None
    for _ in range(retries):
        try:
            r = json.load(urllib.request.urlopen(req, timeout=60))
            return r["choices"][0]["message"]["content"]
        except Exception as e:
            last = e
            time.sleep(2)
    raise RuntimeError("LLM 调用失败: %s" % last)

def extract_json(text):
    # 去掉 ```json 围栏，截取首个 [ 或 { 到末尾匹配
    text = re.sub(r"^```(?:json)?\s*", "", text.strip())
    text = re.sub(r"\s*```$", "", text.strip())
    s = text.find("[")
    o = text.find("{")
    if s == -1 and o == -1:
        return None
    if s == -1:
        s = o
    elif o != -1 and o < s:
        s = o
    return json.loads(text[s:])

def compute_penalty(p, permille, days):
    try:
        return round(p * (permille / 1000.0) * days, 2)
    except Exception:
        return None

# ----------------------------- 生成逻辑 -----------------------------
def gen_single(cat, clauses):
    """每条条款生成 1 道单跳问题（强制 L1/L2，禁止 L3/L4）"""
    sys_p = ("你是法律合同问答系统的评测集构造器。只输出 JSON 数组，不要多余文字。"
             "为每条条款生成 1 道用户口语化【单跳】提问（只问这一条，不引入其他条款），"
             "并给参考答案与难度。")
    lines = []
    for c in clauses:
        lines.append("canonId=%s\n%s" % (c["canon"], c["content"][:400]))
    prefix = ("以下是 %d 条真实法条。请为每条各生成 1 个元素，结构："
              "{canonId, question, expected_answer, difficulty}。\n"
              "要求：question 像真实用户口语（可同义改写）；expected_answer 以'依据 <canonId>：'开头写条款要点；"
              "difficulty 只能取 L1(用户使用条款原词提问) 或 L2(用户用同义改写/口语化提问)，【禁止 L3/L4】；"
              "本批 L1:L2 比例约 3:1（约 75%% L1、25%% L2）。必须原样回写 canonId。\n") % len(clauses)
    usr = prefix + "\n---\n".join(lines)
    raw = call_llm(sys_p, usr)
    arr = extract_json(raw)
    items = []
    if not isinstance(arr, list):
        return items
    for e in arr:
        cid = e.get("canonId")
        if cid not in [c["canon"] for c in clauses]:
            continue
        dif = e.get("difficulty", "L2")
        if dif not in ("L1", "L2"):          # 强制约束：单跳只允许 L1/L2，越界一律降为 L2
            dif = "L2"
        items.append({
            "canon": cid,
            "question": e.get("question", "").strip(),
            "expected_answer": e.get("expected_answer", "").strip(),
            "difficulty": dif,
            "gold_clause_type": cat,
            "type": "retrieve",
            "query_pattern": "single",
            "calculation_params": None,
        })
    # 按目标比例强制难度分布（单跳需贡献大部分 L1，以逼近整体 30/40/25/5）
    n = len(items)
    if n:
        k = round(0.75 * n)   # 约 75% L1
        items.sort(key=lambda x: 0 if x.get("difficulty") == "L1" else 1)
        for i, it in enumerate(items):
            it["difficulty"] = "L1" if i < k else "L2"
    return items

def gen_pairs(kind, pairs, cat_pairs):
    """多跳/对比：每对条款生成 1 题。cat_pairs 为对应的 (catA, catB) 类别元组列表。"""
    is_calc = (kind == "multi")
    sys_p = ("你是法律合同问答系统的评测集构造器。只输出 JSON 数组，不要多余文字。"
             "为每对法条生成 1 道用户问题，需要同时用到两条条款。")
    usr_parts = []
    for i, (a, b) in enumerate(pairs):
        usr_parts.append(
            "【第%d对】\nA canonId=%s\n%s\nB canonId=%s\n%s" % (
                i + 1, a["canon"], a["content"][:350], b["canon"], b["content"][:350]))
    task_prefix = ("生成【%s】问题：需同时引用两条条款（可联合判断、组合计算或对比异同）。\n"
            "每个元素结构：{ids:[idA,idB], question, expected_answer, difficulty:'L2'|'L3'（本批约 60%% L3、40%% L2）, "
            "calc: true/false, calculation_params: {principal, daily_rate_per_mille, days} 或 null}。\n"
            "若需算违约金，calculation_params 给出参数（principal 单位元；daily_rate_per_mille 如 0.5 表示日万分之五；days 为逾期天数），"
            "expected_answer 写出'违约金 = principal × (daily_rate_per_mille/1000) × days = <结果> 元'。\n"
            "必须原样回写两条 canonId 到 ids。\n"
            "【C2/C5 强制计算】若本批配对涉及 C2(违约金) 或 C5(定金)，对应题目【必须为计算题】：calc 必须为 true 且 calculation_params 必须给出具体数值。"
            "违约金场景：principal 单位元、daily_rate_per_mille 如 0.5 表示日万分之五、days 为逾期天数。"
            "定金场景也复用同一 penalty 公式表达：双倍返还取 principal=已付定金、daily_rate_per_mille=2000、days=1（→2×principal）；"
            "定金上限取 principal=主合同标的额、daily_rate_per_mille=200、days=1（→20%%×principal）。"
            "expected_answer 必须写出完整计算式与结果。问题正文只用'日万分之X'等表述，不要出现'利息/利率/借贷/借款'以免路由错计算器。\n") % (
                "多跳组合（含计算）" if is_calc else "对比异同",)
    task = task_prefix + "\n====\n".join(usr_parts)
    raw = call_llm(sys_p, task)
    arr = extract_json(raw)
    items = []
    if not isinstance(arr, list):
        return items
    for i, e in enumerate(arr):
        ids = e.get("ids") or []
        if len(ids) != 2:
            continue
        cat = cat_pairs[i][0] if i < len(cat_pairs) else (pairs[0][0] if pairs else "C6")
        calc = e.get("calc")
        cp = None
        if calc and isinstance(e.get("calculation_params"), dict):
            cp = e["calculation_params"]
            p = cp.get("principal"); perm = cp.get("daily_rate_per_mille"); ds = cp.get("days")
            exp = compute_penalty(p, perm, ds)
            if exp is not None:
                cp = {"principal": p, "daily_rate_per_mille": perm, "days": ds, "expected_penalty": exp}
        items.append({
            "canon": ids,
            "question": e.get("question", "").strip(),
            "expected_answer": e.get("expected_answer", "").strip(),
            "difficulty": e.get("difficulty", "L3"),
            "gold_clause_type": cat,
            "type": "both" if (is_calc and calc) else "retrieve",
            "query_pattern": "multi" if kind == "multi" else "compare",
            "calculation_params": cp,
        })
    # 按目标比例强制难度分布（多跳/对比约 60% L3 / 40% L2，逼近整体 30/40/25/5）
    n = len(items)
    if n:
        k = round(0.6 * n)   # 约 60% L3
        items.sort(key=lambda x: 0 if x.get("difficulty") == "L3" else 1)
        for i, it in enumerate(items):
            it["difficulty"] = "L3" if i < k else "L2"
    return items

def gen_oos(n):
    sys_p = ("你是法律合同问答系统的评测集构造器。只输出 JSON 数组，不要多余文字。")
    usr = ("生成 %d 道明显超出以下 6 类合同范围的问题：生效/付款违约金/不可抗力/保密/定金/违约一般责任。"
           "主题示例：离婚财产、公司法、税务、劳动法、商标专利、继承、交通事故、消费者权益、乙方延迟交货等"
           "（注意：'乙方延迟交货'不在范围内，因为合同法仅覆盖甲方逾期付款情形）。\n"
           "每个元素：{question, difficulty:'L2'|'L4'（本批约 6 道 L2、2 道 L4）, reason:'为何超范围'}。"
           "expected_answer 统一为'应拒答（超出知识库范围）'。") % n
    raw = call_llm(sys_p, usr)
    arr = extract_json(raw)
    items = []
    if not isinstance(arr, list):
        return items
    for e in arr:
        items.append({
            "canon": [],
            "question": e.get("question", "").strip(),
            "expected_answer": "应拒答（超出知识库范围）",
            "difficulty": e.get("difficulty", "L2"),
            "gold_clause_type": "OOS",
            "type": "out_of_scope",
            "query_pattern": "oos",
            "calculation_params": None,
            "reject_reason": e.get("reason", ""),
        })
    # 按目标比例强制难度分布（OOS 约 6 道 L2 + 2 道 L4，逼近整体 30/40/25/5 中的 L4=5%）
    n = len(items)
    if n:
        l4 = min(2, n)
        l2 = n - l4
        for i, it in enumerate(items):
            it["difficulty"] = "L4" if i >= l2 else "L2"
    return items

# ----------------------------- 主流程 -----------------------------
def build_pool():
    print("[1/3] 拉取 ES 全部条款 ...")
    all_clauses = es_fetch_all()
    print("       ES 条款总数:", len(all_clauses))
    print("[2/3] 归类 C1-C6（合同法条）并排除 v2 已用 gold ...")
    v2, used = load_v2_used()
    print("       v2 已用 gold canonicalId 数:", len(used))
    pool = {}
    for c in all_clauses:
        if not c["canon"] or c["canon"] in used:
            continue
        if not is_contract_clause(c["canon"]):   # 严格：只进合同编 463-988
            continue
        cat = classify(c)
        if cat:
            pool.setdefault(cat, []).append(c)
    for cat in sorted(pool):
        print("       %s: %d 条候选" % (cat, len(pool[cat])))
    return all_clauses, v2, used, pool

def sample(pool, cat, k):
    lst = pool.get(cat, [])
    if len(lst) >= k:
        return lst[:k]
    if lst:
        print("       [warn] %s 候选仅 %d < %d，将复用" % (cat, len(lst), k))
        return (lst * ((k // len(lst)) + 1))[:k]
    raise RuntimeError("类别 %s 无任何候选条款，无法生成" % cat)

def assemble_item(it, tid, all_clauses, used):
    canon = it["canon"]
    gold_ids = canon if isinstance(canon, list) else ([canon] if canon else [])
    item = {
        "id": tid,
        "type": it["type"],
        "question": it["question"],
        "expected_clause_ids": gold_ids,
        "calculation_params": it["calculation_params"],
        "expected_answer": it["expected_answer"],
        "difficulty": it["difficulty"],
        "gold_clause_type": it["gold_clause_type"],
        "query_pattern": it["query_pattern"],
    }
    if it["type"] == "out_of_scope":
        item["reject_reason"] = it.get("reject_reason", "")
    return item

def write_outputs(all_clauses, v2, used, out_data):
    es_canon_set = {c["canon"] for c in all_clauses}
    check_rows = []
    for it in out_data:
        tid = it["id"]
        gold_ids = it["expected_clause_ids"]
        leak = [g for g in gold_ids if g in used]
        missing = [g for g in gold_ids if g not in es_canon_set]
        check_rows.append((tid, it["query_pattern"], it["gold_clause_type"],
                           it["difficulty"], it["question"], leak, missing))
    # meta（兼容 v2）
    meta = dict(v2["meta"])
    cnt = Counter(t["query_pattern"] for t in out_data)
    meta["v3_draft"] = {
        "generated_by": "kb_seed_v3.py (DashScope %s)" % MODEL,
        "source": "ES rag_knowledge_base (2186 条法典法条)",
        "leakage_guard": "已排除 v2 用过的 %d 个 canonicalId" % len(used),
        "counts": {"single": cnt.get("single", 0), "multi": cnt.get("multi", 0),
                   "compare": cnt.get("compare", 0), "oos": cnt.get("oos", 0),
                   "total_new": len(out_data)},
        "note": "草稿，待人工核验 gold 与答案后再合入 v2 -> v3。",
    }
    meta["counts"]["total"] = meta["counts"].get("total", 38) + len(out_data)

    draft = {"meta": meta, "data": out_data}
    with open(OUT_DRAFT, "w", encoding="utf-8") as f:
        json.dump(draft, f, ensure_ascii=False, indent=2)
    print("[写出] %s （%d 题）" % (OUT_DRAFT, len(out_data)))

    # 核验清单
    lines = ["# 评测集 v3 草稿 — 人工核验清单", "",
             "> 生成：kb_seed_v3.py | 模型：%s | 来源：ES 2186 法典法条（已排除 v2 gold，防泄漏）" % MODEL,
             "> 共 %d 题（单跳%d / 多跳%d / 对比%d / 超范围%d）。请逐题确认下列打勾项。" % (
                 len(out_data), cnt.get("single", 0), cnt.get("multi", 0),
                 cnt.get("compare", 0), cnt.get("oos", 0)), "",
             "## 逐题核验", ""]
    for (tid, pat, gct, dif, q, leak, missing) in check_rows:
        warn = ""
        if leak:
            warn += " ⚠️ **泄漏**：gold 命中 v2 已用 id %s" % leak
        if missing:
            warn += " ⚠️ **gold 不在 ES**：%s" % missing
        lines.append("### %s 〔%s / %s / 难度%s〕%s" % (tid, pat, gct, dif, warn))
        lines.append("- 问题：`%s`" % q)
        lines.append("- [ ] **gold 正确**：expected_clause_ids 指向真实相关法条（非幻觉）")
        lines.append("- [ ] **答案准确**：expected_answer 与条款内容一致" + (
            "，计算式与 expected_penalty 正确" if pat == "multi" and "expected_penalty" in str(
                [d for d in out_data if d["id"] == tid]) else ""))
        lines.append("- [ ] **类型恰当**：type=%s 合理" % (
            [d for d in out_data if d["id"] == tid][0]["type"]))
        lines.append("- [ ] **难度合理**：difficulty=%s" % dif)
        if pat in ("multi", "compare"):
            lines.append("- [ ] **覆盖完整**：expected_clause_ids 涵盖回答所需全部条款")
        if pat == "oos":
            lines.append("- [ ] **确属超范围**：理由成立（不在 C1-C6 内）")
        lines.append("")
    with open(OUT_CHECK, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("[写出] 核验清单 %s" % OUT_CHECK)
    print("DONE. 泄漏项:%d, gold缺失项:%d" % (
        sum(1 for r in check_rows if r[5]), sum(1 for r in check_rows if r[6])))

def incremental_regen(all_clauses, v2, used, pool):
    """保留 gold 合法的题（合同编463-988 或 OOS 或 计算题），只重跑受污染的题（gold 非合同编）。"""
    old = json.load(open(OUT_DRAFT, encoding="utf-8"))
    old_data = old["data"]

    def is_keep(t):
        if t.get("type") == "both":
            return True   # 计算题不重跑（稍后由 add_calc_multihop.py 改 gold 为纯民法典）
        return valid_gold_ids(t.get("expected_clause_ids") or [])
    kept = [t for t in old_data if is_keep(t)]
    kept_ids = {t["id"] for t in kept}
    # 计算题(type=both, query_pattern 同为 multi) 不计入「模板多跳」配额，
    # 否则会把 5 道 calc 算进 need_multi，导致少重跑 5 道受污染模板题。
    kp = Counter()
    for t in kept:
        if t.get("type") == "both":
            continue
        kp[t["query_pattern"]] += 1
    kept_single_cat = Counter(t["gold_clause_type"] for t in kept
                              if t["query_pattern"] == "single" and t.get("type") != "both")

    need_single = max(0, N_SINGLE - kp.get("single", 0))
    need_multi = max(0, N_MULTI - kp.get("multi", 0))
    need_compare = max(0, N_COMPARE - kp.get("compare", 0))
    # 单跳按类别补差（已保留的类别不再生成）
    single_need_cat = {cat: max(0, q - kept_single_cat.get(cat, 0)) for cat, q in SINGLE_QUOTA.items()}
    print("       保留 %d 题；重跑 单跳%d / 多跳%d / 对比%d" % (len(kept), need_single, need_multi, need_compare))

    new_single = []
    for cat, q in single_need_cat.items():
        if q == 0:
            continue
        items = gen_single(cat, sample(pool, cat, q))
        new_single.extend(items)
        print("       单跳 %s 生成 %d" % (cat, len(items)))
    multi_pairs = [(sample(pool, a, 1)[0], sample(pool, b, 1)[0]) for a, b in MULTI_PAIRS[:need_multi]]
    new_multi = gen_pairs("multi", multi_pairs, MULTI_PAIRS[:need_multi])
    comp_pairs = [(sample(pool, a, 1)[0], sample(pool, b, 1)[0]) for a, b in COMPARE_PAIRS[:need_compare]]
    new_compare = gen_pairs("compare", comp_pairs, COMPARE_PAIRS[:need_compare])

    # 复用受污染题的 ID 空位，保证核验清单连续性
    single_slots = [("T%03d" % i) for i in range(39, 56) if ("T%03d" % i) not in kept_ids]
    multi_slots = [("T%03d" % i) for i in range(56, 66) if ("T%03d" % i) not in kept_ids]
    comp_slots = [("T%03d" % i) for i in range(66, 73) if ("T%03d" % i) not in kept_ids]
    if not (len(single_slots) == len(new_single) and len(multi_slots) == len(new_multi) and len(comp_slots) == len(new_compare)):
        print("[warn] slot 数不匹配：single %d/%d, multi %d/%d, compare %d/%d（将顺序填充）"
              % (len(single_slots), len(new_single), len(multi_slots), len(new_multi), len(comp_slots), len(new_compare)))
    out = list(kept)
    for it, tid in zip(new_single, single_slots):
        out.append(assemble_item(it, tid, all_clauses, used))
    for it, tid in zip(new_multi, multi_slots):
        out.append(assemble_item(it, tid, all_clauses, used))
    for it, tid in zip(new_compare, comp_slots):
        out.append(assemble_item(it, tid, all_clauses, used))
    out.sort(key=lambda x: x["id"])
    write_outputs(all_clauses, v2, used, out)

# ----------------------------- 扩容（47 -> 150）-----------------------------
def build_expansion_pool(old_gold_ids):
    """构建扩容候选池：全部合同编条款，仅排除 v2 已用 ∪ 8 个超频条款（不做 C1-C6 硬编码分桶）。

    说明：合同编 526 条里仅 ~90 条命中 C1-C6 关键词，且 C3(不可抗力)/C5(定金) 极稀疏（仅 2-3 条），
    若按 C1-C6 分桶 + 排除旧 47 gold 会直接饿死 C3/C5。故改用扁平池随机抽样，自然覆盖全合同编、
    满足「>=100 不同条款」且每条款 gold<=MAX_CLAUSE_USE（上限=2，主要靠新鲜优先抽样达成）。
    """
    print("[expand 1/3] 拉取 ES 全部条款 ...")
    all_clauses = es_fetch_all()
    print("       ES 条款总数:", len(all_clauses))
    v2, used = load_v2_used()
    avoid = set(old_gold_ids) | used | EXCLUDED_CLAUSES
    print("[expand 2/3] 过滤合同编条款并排除 (v2已用 ∪ 8超频) ...")
    print("       排除 canonicalId 数:", len(avoid))
    exp_pool = []
    for c in all_clauses:
        if not c["canon"] or c["canon"] in avoid:
            continue
        if not is_contract_clause(c["canon"]):
            continue
        exp_pool.append(c)
    exp_pool.sort(key=lambda c: c["canon"])
    print("       扩容候选池: %d 条合同编条款（扁平）" % len(exp_pool))
    return all_clauses, v2, used, exp_pool


def sample_capped_flat(pool, k, usage):
    """从扁平池取 k 条，优先 usage 最小（新鲜优先），跳过 usage>=MAX_CLAUSE_USE。"""
    cand = [c for c in pool if usage.get(c["canon"], 0) < MAX_CLAUSE_USE]
    cand.sort(key=lambda c: usage.get(c["canon"], 0))
    return cand[:k]


def pick_two_flat(pool, usage):
    """取两条不同条款（多跳/对比配对），优先新鲜，更新 usage。"""
    cand = [c for c in pool if usage.get(c["canon"], 0) < MAX_CLAUSE_USE]
    cand.sort(key=lambda c: usage.get(c["canon"], 0))
    if len(cand) < 2:
        return None
    a, b = cand[0], cand[1]
    usage[a["canon"]] = usage.get(a["canon"], 0) + 1
    usage[b["canon"]] = usage.get(b["canon"], 0) + 1
    return (a, b)


def expand(all_clauses, v2, used, pool, old_data):
    """在旧 47 draft 基础上追加 103 题（single+58/multi+15/compare+15/oos+15）。"""
    usage = Counter()
    for t in old_data:
        for g in (t.get("expected_clause_ids") or []):
            usage[g] += 1
    start_id = max(int(t["id"][1:]) for t in old_data) + 1   # = T086
    print("[expand 3/3] 生成 单跳%d / 多跳%d / 对比%d / 超范围%d ..." % (
        EXP_SINGLE, EXP_MULTI, EXP_COMPARE, EXP_OOS))

    new_items = []
    # 单跳：从扁平池抽样 EXP_SINGLE 条不同条款，每 ~15 条一组调 LLM（per-clause 标类别）
    single_clauses = sample_capped_flat(pool, EXP_SINGLE, usage)
    for c in single_clauses:
        usage[c["canon"]] = usage.get(c["canon"], 0) + 1
    print("       单跳抽样 %d 条不同条款" % len(single_clauses))
    for i in range(0, len(single_clauses), 15):
        chunk = single_clauses[i:i + 15]
        items = gen_single(classify(chunk[0]) or "C6", chunk)
        cby = {c["canon"]: c for c in chunk}
        for it in items:
            c = cby.get(it["canon"])
            if c:
                it["gold_clause_type"] = classify(c) or "C6"
        new_items.extend(items)
        print("       单跳批次生成 %d" % len(items))

    # 多跳：15 对（每对 2 条不同条款）
    multi_pairs, multi_cats = [], []
    for _ in range(EXP_MULTI):
        p = pick_two_flat(pool, usage)
        if p:
            multi_pairs.append(p)
            multi_cats.append((classify(p[0]) or "C6", classify(p[1]) or "C6"))
    multi_items = gen_pairs("multi", multi_pairs, multi_cats)
    new_items.extend(multi_items)
    print("       多跳生成 %d (配对 %d)" % (len(multi_items), len(multi_pairs)))

    # 对比：15 对
    comp_pairs, comp_cats = [], []
    for _ in range(EXP_COMPARE):
        p = pick_two_flat(pool, usage)
        if p:
            comp_pairs.append(p)
            comp_cats.append((classify(p[0]) or "C6", classify(p[1]) or "C6"))
    comp_items = gen_pairs("compare", comp_pairs, comp_cats)
    new_items.extend(comp_items)
    print("       对比生成 %d (配对 %d)" % (len(comp_items), len(comp_pairs)))

    # 超范围
    oos_items = gen_oos(EXP_OOS)
    new_items.extend(oos_items)
    print("       超范围生成 %d" % len(oos_items))

    out = list(old_data)
    for i, it in enumerate(new_items):
        out.append(assemble_item(it, "T%03d" % (start_id + i), all_clauses, used))
    out.sort(key=lambda x: x["id"])
    write_outputs(all_clauses, v2, used, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--single-only", action="store_true",
                    help="只重跑单跳（强制 L1/L2），保留多跳/对比/OOS 不动")
    ap.add_argument("--incremental", action="store_true",
                    help="保留 gold 合法的题，仅重跑受污染（gold 非合同编463-988）的题")
    ap.add_argument("--checklist-only", action="store_true",
                    help="仅依据当前 draft 重新生成核验清单（并标注非合同编 gold）")
    ap.add_argument("--expand", action="store_true",
                    help="扩容：在旧 47 draft 基础上追加 103 题（single+58/multi+15/compare+15/oos+15），"
                         "强制每条款 gold<=MAX_CLAUSE_USE 且避开 8 个超频条款")
    args = ap.parse_args()
    if not DASH_KEY:
        print("[error] 未找到 DashScope key，无法调用 LLM。设置 DASHSCOPE_API_KEY 后重试。")
        sys.exit(1)

    all_clauses, v2, used, pool = build_pool()
    start = len(v2["data"]) + 1   # = 39 -> T039

    if args.expand:
        print("=== 扩容模式：47 -> 150 ===")
        old = json.load(open(OUT_DRAFT, encoding="utf-8"))
        old_data = old["data"]
        old_gold = set(g for t in old_data for g in (t.get("expected_clause_ids") or []))
        ex_all, ex_v2, ex_used, ex_pool = build_expansion_pool(old_gold)
        expand(ex_all, ex_v2, ex_used, ex_pool, old_data)
        return

    if args.checklist_only:
        draft = json.load(open(OUT_DRAFT, encoding="utf-8"))
        write_outputs(all_clauses, v2, used, draft["data"])
        return

    if args.incremental:
        print("[3/3] 增量重跑：保留合法 gold，重跑受污染题 ...")
        incremental_regen(all_clauses, v2, used, pool)
        return

    if args.single_only:
        print("[3/3] 单跳-only 重跑（多跳/对比/OOS 保留不动）...")
        old = json.load(open(OUT_DRAFT, encoding="utf-8"))
        kept = [t for t in old["data"] if t["query_pattern"] != "single"]
        single_raw = []
        for cat, q in SINGLE_QUOTA.items():
            items = gen_single(cat, sample(pool, cat, q))
            single_raw.extend(items)
            print("       %s 单跳得到 %d 条" % (cat, len(items)))
        out_data = []
        for i, it in enumerate(single_raw):
            out_data.append(assemble_item(it, "T%03d" % (start + i), all_clauses, used))
        out_data.extend(kept)
        out_data.sort(key=lambda x: x["id"])
        write_outputs(all_clauses, v2, used, out_data)
        return

    # 全量生成（默认）
    data = []
    print("[3/3] 生成单跳(%d) + 多跳(%d) + 对比(%d) + 超范围(%d) ..." % (
        N_SINGLE, N_MULTI, N_COMPARE, N_OOS))
    for cat, q in SINGLE_QUOTA.items():
        items = gen_single(cat, sample(pool, cat, q))
        data.extend(items)
        print("       %s 单跳得到 %d 条" % (cat, len(items)))
    multi_pairs = []
    for a, b in MULTI_PAIRS:
        sa, sb = sample(pool, a, 1)[0], sample(pool, b, 1)[0]
        multi_pairs.append((sa, sb))
    data.extend(gen_pairs("multi", multi_pairs, MULTI_PAIRS))
    comp_pairs = []
    for a, b in COMPARE_PAIRS:
        sa, sb = sample(pool, a, 1)[0], sample(pool, b, 1)[0]
        comp_pairs.append((sa, sb))
    data.extend(gen_pairs("compare", comp_pairs, COMPARE_PAIRS))
    data.extend(gen_oos(N_OOS))

    data = data[:TOTAL_NEW]
    if len(data) < TOTAL_NEW:
        print("[warn] 实际生成 %d < %d，请检查 LLM 返回" % (len(data), TOTAL_NEW))
    out_data = [assemble_item(it, "T%03d" % (start + i), all_clauses, used)
                for i, it in enumerate(data)]
    write_outputs(all_clauses, v2, used, out_data)

if __name__ == "__main__":
    main()
