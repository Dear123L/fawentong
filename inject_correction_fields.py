# -*- coding: utf-8 -*-
"""
给核验清单每题注入「修正记录」空行，供人工填写后由 backfill_checklist.py 回填到草稿 JSON。
用法:
  python inject_correction_fields.py [清单md] [草稿json]
幂等：已含「修正记录」的题会跳过。
"""
import json, re, sys

CHECKLIST = sys.argv[1] if len(sys.argv) > 1 else "resume-output/testset_v3_核验清单.md"
DRAFT = sys.argv[2] if len(sys.argv) > 2 else "testset_v3_draft.json"

draft = json.load(open(DRAFT, encoding="utf-8"))
gold_map = {t["id"]: (t.get("expected_clause_ids") or []) for t in draft["data"]}

lines = open(CHECKLIST, encoding="utf-8").read().split("\n")
head_re = re.compile(r"^###\s+(T\d+)\b")
heads = [i for i, l in enumerate(lines) if head_re.match(l)]

out = []
for k, start in enumerate(heads):
    end = heads[k + 1] if k + 1 < len(heads) else len(lines)
    block = lines[start:end]
    # 已注入则跳过
    if any("修正记录" in b for b in block):
        out.extend(block)
        continue
    # 找到最后一个打勾项，插入其后
    insert_at = None
    for j in range(len(block) - 1, -1, -1):
        if block[j].lstrip().startswith("- [ ]"):
            insert_at = j
            break
    if insert_at is None:
        insert_at = len(block) - 1
    tid = head_re.match(block[0]).group(1)
    g = gold_map.get(tid, [])
    gdisp = ", ".join(g) if g else "（OOS/无）"
    corr = [
        f"- 当前 gold：`{gdisp}`",
        "**修正记录**（无需修正请留空；有修正填后运行 `python backfill_checklist.py --apply`）",
        "- 正确 gold：` `",
        "- 正确答：` `",
    ]
    newblock = block[:insert_at + 1] + [""] + corr + [""] + block[insert_at + 1:]
    out.extend(newblock)

text = "\n".join(out)
# 头部加一句机制说明
note = ("\n> 修正机制：每题末尾有「修正记录」，填 `正确 gold` / `正确答` 后运行 "
        "`python backfill_checklist.py --apply` 即可回填到草稿 JSON（dry-run 默认只预览）。")
text = text.replace("请逐题确认下列打勾项。", "请逐题确认下列打勾项。" + note, 1)

open(CHECKLIST, "w", encoding="utf-8").write(text)
print("已为 %d 题注入「修正记录」空行（幂等）。" % len(heads))
