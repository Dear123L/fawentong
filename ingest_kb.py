# -*- coding: utf-8 -*-
"""
把 6 条评测 KB 条款灌入本地 ES 索引 rag_knowledge_base（kbId=1, _id=C1..C6）。
本地 ES 为 7.17（不支持 dense_vector ANN），故只建 BM25 字段，不做向量。
hybridSearch 已做 knn 降级，纯 BM25 也能检索，便于跑真实评测。
"""
import json
import urllib.request
import urllib.error

ES = "http://localhost:9200"
IDX = "rag_knowledge_base"
KB_ID = 1

CLAUSES = {
    "C1": "本合同自双方签字盖章之日起生效，有效期为三年。",
    "C2": "甲方应在货物交付后三十日内支付全部价款，逾期按日万分之五支付违约金。",
    "C3": "因不可抗力导致不能履行合同的，根据不可抗力影响部分或全部免除责任。",
    "C4": "保密条款在本合同终止后三年内继续有效。",
    "C5": "定金不得超过主合同标的额的百分之二十，超过部分不产生定金效力。",
    "C6": "当事人一方不履行合同义务或者履行合同义务不符合约定的，应当承担继续履行、采取补救措施或者赔偿损失等违约责任。",
}


def es(method, path, body=None):
    url = ES + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def main():
    # 若已存在则删除（确保 mapping 干净）
    st, _ = es("HEAD", "/" + IDX)
    if st == 200:
        es("DELETE", "/" + IDX)
        print("已删除旧索引", IDX)

    # 建索引：仅 BM25 字段（ES7 兼容，无 dense_vector）
    mapping = {
        "mappings": {
            "properties": {
                "content": {"type": "text"},
                "kbId": {"type": "long"},
                "docId": {"type": "long"},
                "userId": {"type": "long"},
                "chunkIndex": {"type": "integer"},
                "createdAt": {"type": "long"},
            }
        }
    }
    st, msg = es("PUT", "/" + IDX, mapping)
    print("建索引:", st, msg[:200])

    # 逐条写入，_id = C1..C6，检索返回的 clauseId 即与测试集 expected_clause_ids 对齐
    for i, (cid, text) in enumerate(CLAUSES.items(), start=1):
        doc = {
            "content": text,
            "kbId": KB_ID,
            "docId": i,
            "userId": 1,
            "chunkIndex": 0,
            "createdAt": 0,
        }
        st, msg = es("PUT", f"/{IDX}/_doc/{cid}", doc)
        print(f"写入 {cid}: {st}")

    # 刷新并统计
    es("POST", f"/{IDX}/_refresh")
    st, msg = es("GET", f"/{IDX}/_count")
    print("当前文档数:", msg)


if __name__ == "__main__":
    main()
