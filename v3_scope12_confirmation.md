# v3 测试集正式切换为 12 法域口径 — 零回归确认

> 生成日期：2026-10-03（用户确认按 12 法域口径执行）

## 1. 正式切换动作
- 备份旧 v3（23 道陈旧 OOS 金标）：`contract_qa_testset_v3.json` → `contract_qa_testset_v3_legacy_contractonly.json`
- 用 12 法域重标版覆盖：`contract_qa_testset_v3_scope12.json` → `contract_qa_testset_v3.json`（现即正式 v3 评测集）
- 覆盖后校验：total=150；type 分布 `retrieve 122 / in_scope 12 / out_of_scope 11 / both 5`；仅 11 道真·域外（商标/税务/股东/消保）为空 gold。
- OOS 由 23 → **11**，域内由 127 → **139**。

## 2. 评测脚本默认对齐（消除"致命坑"）
| 文件 | 改动 |
|---|---|
| `run_eval.py` | `EVAL_TESTSET` 默认值 → `contract_qa_testset_v3.json`（原为旧 v2 `contract_qa_testset.json`） |
| `eval_scorer.py` | `EVAL_TESTSET` 默认值 → `contract_qa_testset_v3.json`（原为旧 v2） |
| `supervise_eval.sh` | `EVAL_TESTSET` 默认早已是 v3；`OUT` 默认 → `results_v3.json` |

现无论是否显式设 `EVAL_TESTSET`，两脚本默认都指向重标后的 v3，评测即一致。

## 3. KB_SCOPE 与拒答文案确认
- `KB_SCOPE`（ScopeCheckNode.java:128）已扩为 **全部 12 法域**（民法典含合同/婚姻/继承/侵权编、民事诉讼法、劳动法家族、合同编通则解释、民间借贷、住房租赁等），与 ES 实际 `rag_knowledge_base` **2186 条 / 12 领域**对齐 —— 口径正确，无需改动。
- `OUT_OF_SCOPE_REPLY`（ScopeCheckNode.java:51）已于 2026-10-02 更新为"本系统当前覆盖 12 个法域…"，文案一致，无需改动。
- `HARD_OOS_PHRASES` 仅硬拒 商标/税务/股东/消保（专利/著作权已移除，修复 T166/T167 误拒）。

## 4. 全量重评结果（results_v3.json，150/150，零回归确认）
| 维度 | 指标 | 数值 |
|---|---|---|
| 检索 | HR@1 / HR@3 / HR@5 | **0.705 / 0.856 / 0.935** |
| 检索 | MRR | **0.796**（subset=139） |
| 拒答 | 混淆矩阵 | **TP=11 / FN=0 / FP=0 / TN=139** |
| 拒答 | precision / recall / accuracy | **1.0 / 1.0 / 1.0** |
| 计算 | amount_acc / overall_param_acc | **1.0 / 1.0** |
| 生成 | faithfulness / relevance | 5.0 / 5.0（规则代理分） |

与 scope12 前次结果（0.705/0.856/0.935、MRR=0.796、拒答全 1.0、FP=0）**逐位一致 → 零回归确认**。

## 5. 面试材料已更新（写入"覆盖 12 法域、2186 条法规"）
- `resume-output/v3_150_eval_report.md`：OOS 23→11、域内 127→139；修正 HARD_OOS 列表为现行（商标/税务/股东/消保）；顶部加"覆盖 12 法域、共 2186 条法规"
- `resume-output/面试官视角-20问.md`：150 题 = 139 域内 + 11 域外 OOS（商标/税务/股东/消保），前加"覆盖 12 个法域"
- `resume-output/项目追问/面试救场-5个答不出的问题.md`：同步 OOS 11、加 12 法域
- `resume-output/项目追问/法问通-多智能体RAG项目地图.md`：原"6 条/6 类合同条款"→"12 个法域"
- `resume-output/面试前必读清单.md` / `系统全景说明书.md` / `2分钟项目介绍口述稿.md`：KB 表述加"覆盖 12 个法域、共 2186 条法规"

## 6. 注意事项
- 旧 23-OOS 版已备份为 `contract_qa_testset_v3_legacy_contractonly.json`，如需回看历史口径可查。
- **勿重跑 `merge_v3.py`**：它会从 draft 重新生成 v3.json，回退到陈旧 23-OOS 口径，覆盖本次切换成果。
- 面试统一话术：「服务范围 = 12 个法域（ES 实际覆盖域）、共 2186 条法规；v3 评测集 150 题 = 139 域内 + 11 真·域外」。
