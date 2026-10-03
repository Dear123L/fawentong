"""最小可运行验证脚本（不依赖 api.py / 多智能体图）：
建索引 → 写入语料 → 跑查询 → 分别看 BM25 路 / KNN 路 / RRF 融合结果。

前置：
  1) 本地 ES 已启动且装好 ik 插件（见 docker 命令）
  2) 已安装依赖：pip install -r requirements.txt
  3) 可选环境变量：ES_HOST（默认 http://localhost:9200）、ES_INDEX（默认 fawentong_clauses）

运行：
  python test_es_retriever.py
"""
import logging
from agentic_rag import HybridRetriever

logging.basicConfig(level=logging.INFO)

CORPUS = [
    "本合同自双方签字盖章之日起生效，有效期为三年。",
    "甲方应在货物交付后三十日内支付全部价款，逾期按日万分之五支付违约金。",
    "因不可抗力导致不能履行合同的，根据不可抗力影响部分或全部免除责任。",
    "保密条款在本合同终止后三年内继续有效。",
    "定金不得超过主合同标的额的百分之二十，超过部分不产生定金效力。",
    "当事人一方不履行合同义务或者履行合同义务不符合约定的，应当承担继续履行、"
    "采取补救措施或者赔偿损失等违约责任。",
]


def main() -> None:
    retriever = HybridRetriever(CORPUS)
    mode = "Elasticsearch" if retriever._use_es else "内存降级(rank_bm25+NumPy)"
    print(f"\n[当前模式] {mode}\n")

    query = "逾期付款要付多少违约金？"
    k = 5

    bm25 = retriever._bm25_top(query, k)
    knn = retriever._knn_top(query, k)
    fused = retriever.retrieve(query, k)

    print(f"== BM25 路 Top{k} doc_id ==")
    print("  ", bm25)
    print(f"== KNN  路 Top{k} doc_id ==")
    print("  ", knn)
    print("== RRF 融合结果（按 score 降序）==")
    for r in fused:
        print(f"  score={r['score']:.4f}  doc_id={r['idx']}  {r['text']}")


if __name__ == "__main__":
    main()
