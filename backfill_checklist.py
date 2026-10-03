# -*- coding: utf-8 -*-
"""
把核验清单里的「修正记录」回填到草稿 JSON。
用法:
  python backfill_checklist.py                 # dry-run：只打印将要执行的改动
  python backfill_checklist.py --apply         # 写入 testset_v3_draft.json 并同步清单的「当前 gold」
  python backfill_checklist.py --checklist X.md --draft Y.json --apply

解析规则:
  - 每题 `### Txxx` 下的 `正确 gold：` 与 `正确答：`（值可加或不加反引号）。
  - 正确 gold 留空/OOS/超范围/无 → 该题 expected_clause_ids 清空并标 OOS。
  - 否则按逗号/顿号/空格拆分，写入 expected_clause_ids。
  - 正确答 非空 → 覆盖 expected_answer。
  - gold_clause_type 不自动改（eval 不读该字段，仅展示）；若类别变了会给出提示。
"""
import json, re, sys, argparse


def parse_gold(s):
    s = s.strip()
    if s.lower() in {"", "oos", "超范围", "无", "none", "na"}:
        return "OOS"
    toks = re.split(r"[，,、\s]+", s)
    toks = [t.strip().strip("`").strip() for t in toks]
    return [t for t in toks if t]


def sync_checklist(path, draft):
    by_id = {t["id"]: t for t in draft["data"]}
    lines = open(path, encoding="utf-8").read().split("\n")
    head_re = re.compile(r"^###\s+(T\d+)\b")
    goldline_re = re.compile(r"^- 当前 gold：")
    cur = None
    for i, l in enumerate(lines):
        m = head_re.match(l)
        if m:
            cur = m.group(1)
        elif cur and goldline_re.match(l):
            g = by_id.get(cur, {}).get("expected_clause_ids") or []
            disp = ", ".join(g) if g else "（OOS/无）"
            lines[i] = f"- 当前 gold：`{disp}`"
    open(path, "w", encoding="utf-8").write("\n".join(lines))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checklist", default="resume-output/testset_v3_核验清单.md")
    ap.add_argument("--draft", default="testset_v3_draft.json")
    ap.add_argument("--apply", action="store_true", help="写入 draft（默认 dry-run 仅打印）")
    args = ap.parse_args()

    text = open(args.checklist, encoding="utf-8").read()
    head_re = re.compile(r"^###\s+(T\d+)\b", re.M)
    positions = [(m.start(), m.group(1)) for m in head_re.finditer(text)]
    positions.append((len(text), None))

    gold_re = re.compile(r"正确 gold[：:]\s*`?([^`\n]*?)`?\s*$", re.M)
    ans_re = re.compile(r"正确答[：:]\s*`?([^`\n]*?)`?\s*$", re.M)

    corr = {}
    for i in range(len(positions) - 1):
        start, tid = positions[i]
        end = positions[i + 1][0]
        seg = text[start:end]
        gm = gold_re.search(seg)
        am = ans_re.search(seg)
        g = gm.group(1).strip() if gm else ""
        a = am.group(1).strip() if am else ""
        if g or a:
            corr[tid] = (g, a)

    if not corr:
        print("[dry-run] 未发现任何修正记录（所有「正确 gold/答」均为空）。无需回填。")
        return

    draft = json.load(open(args.draft, encoding="utf-8"))
    by_id = {t["id"]: t for t in draft["data"]}

    changes = []
    for tid, (g, a) in corr.items():
        item = by_id.get(tid)
        if not item:
            print(f"[warn] {tid} 在 draft 中未找到，跳过")
            continue
        if g:
            pg = parse_gold(g)
            if pg == "OOS":
                item["expected_clause_ids"] = []
                item["gold_clause_type"] = "OOS"
                changes.append((tid, "gold → []（标记 OOS）", "oos"))
            else:
                item["expected_clause_ids"] = pg
                changes.append((tid, f"gold → {pg}", "gold"))
                if item.get("gold_clause_type") and item["gold_clause_type"] not in ("OOS",):
                    print(f"[提示] {tid} gold 已改，但 gold_clause_type 仍为 "
                          f"{item['gold_clause_type']}（eval 不读该字段，仅作展示，可视情况手改）")
        if a:
            item["expected_answer"] = a
            changes.append((tid, "答 → 已更新", "ans"))
        if item.get("calculation_params") and (g or a):
            print(f"[提示] {tid} 含 calculation_params.expected_penalty，"
                  f"若答案数字变更请同步修改该字段")

    print(f"发现 {len(changes)} 处修正：")
    for tid, desc, _ in changes:
        print(f"  {tid}: {desc}")

    if not args.apply:
        print("\n[dry-run] 未写入。加 --apply 执行回填。")
        return

    json.dump(draft, open(args.draft, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"\n[apply] 已写入 {args.draft}（{len(changes)} 处）。")
    sync_checklist(args.checklist, draft)


if __name__ == "__main__":
    main()
