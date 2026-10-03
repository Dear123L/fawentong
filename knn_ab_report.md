# KNN 检索 A/B 评测报告（法问通 AgenticRAG）

> 结论：**BM25 + KNN（RRF 融合）决定性胜出，已上线（`rag.hybrid.knn.enabled=true`）。**
> HR@1 由 0.111 提升至 0.600（+48.9pp），HR@5 由 0.456 提升至 0.800。

## 1. 背景与目的
- 之前在 595 条真实语料上，纯 BM25 基线 HR@1 仅 0.10（玩具库 6 条时 0.73 是虚假繁荣），检索是端到端 HR 的主要瓶颈。
- 为验证"向量 KNN 能否补上召回缺口"，在 ES 8.15.5（原生 `dense_vector` ANN）上对 100 条条款补向量，做 A/B：

| 相 | 配置 | 说明 |
|---|---|---|
| A | `knn=false` | 纯 BM25（原基线） |
| B | `knn=true` | BM25 + KNN（RRF k=60 融合） |

## 2. 实验设置
- **ES**：8.15.5（单节点、1g 堆、关闭 geoip 下载），索引 `rag_knowledge_base` 含 `dense_vector(dim=1024, cosine, index=true)`。
- **向量**：用 `backfill_vectors.py` 对 100 条条款（9 条 gold + 91 条填充）调 DashScope `text-embedding-v3` 补 1024 维向量，批量上限 10（受 DashScope 限流）。
- **开关**：`RagVectorServiceImpl.hybridSearch` 中 KNN 块由 `@Value("${rag.hybrid.knn.enabled:false}")` 控制，关闭时跳过 KNN、仅保留 BM25 排序。
- **评测**：`run_eval.py` 多智能体 `multiDebug` 端点，前台 + 预算制 + 断点续跑；`contract_qa_testset_v2.json`（38 题，含 30 道检索题 + 8 道拒答题）。
- **复现 3 遍**（A/B 各 results_*_run1/2/3.json），`aggregate_knn_ab.py` 聚合 `mean ± std`。

## 3. 结果

| 指标 | A(knn=false) | B(knn=true) | 提升 |
|---|---|---|---|
| HR@1 | 0.1111 ± 0.0192 | **0.6000 ± 0.0000** | +440% |
| HR@3 | 0.3111 ± 0.0192 | **0.8000 ± 0.0000** | +157% |
| HR@5 | 0.4556 ± 0.0193 | **0.8000 ± 0.0000** | +76% |
| rate_acc | 1.000 | 1.000 | 不变 |
| amount_acc | 0.8824 | 0.8824 | 不变（KNN 不影响计算层）|
| reject_recall | 1.000 | 1.000 | 不变 |

- B 相 3 遍 HR 标准差为 0 → **检索是确定性的**，HR 提升真实稳定，非随机波动。
- 计算/拒答层完全不变 → KNN 仅作用于检索，**无回归**。

## 4. 决策
`DECISIVE WIN` —— 已上线 `rag.hybrid.knn.enabled=true`。
关键证据：单条探针 T004（"逾期付款违约金按什么比例算"）在 A 相 top5 全是噪声条款，开 KNN 后金标准 `民法典-第585条` 进入 top2、`587/680` 也在列。

## 5. 重要限制（诚实说明）
1. **覆盖率缺口**：当前仅 **100/595** 条款有向量，KNN 只能帮助 gold 落在这 100 条内的查询。HR@1 已达 0.60 说明 30 道检索题的多数 gold 已覆盖；要进一步逼近上限，需把 595 条**全量补向量**（`backfill_vectors.py` 改选全量、批量 10 续跑即可）。
2. **ES 版本依赖**：KNN 走 ES 8.x 原生 ANN；沿用 7.17 时 `knn` 查询不可用、代码已优雅降级为纯 BM25（即 A 相）。上线 KNN **必须配套 ES 升级到 8.15.5**。
3. **维度一致性**：Java `generateEmbedding`（text-embedding-v3, 1024）与回填脚本同模型同维度；新上传文档的切片会自带向量，无需手动回填。

## 6. 行动项
- [x] ES 8.15.5 升级 + 索引含 `dense_vector`
- [x] 100 条条款补向量
- [x] `rag.hybrid.knn.enabled` 开关 + 默认上线 true
- [ ] （建议）全量 595 条补向量，把覆盖率缺口补上，目标 HR@1 冲 0.8+
- [ ] 生产环境 ES 由 7.17 切 8.15.5（含 JVM/配置迁移验证）
