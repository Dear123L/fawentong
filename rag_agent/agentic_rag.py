"""
法问通 · AgenticRAG 合同问答智能体 (Python 版)
=============================================
对应 Java 项目中的 LangGraph4j 工作流，使用 Python LangGraph 重写，
用于投递「全栈软件工程师（AI 智能体方向）」岗位时展示 Python + RAG + 多节点智能体能力。

与 Java 版的对应关系：
  Java (LangGraph4j)                  Python (LangGraph)
  ───────────────────────────────    ───────────────────────────────────
  State / OverAllState               AgentState (TypedDict)
  BM25 + Vector KNN 混合检索          HybridRetriever (Elasticsearch: ik_max_word BM25 + dense_vector KNN, 应用层 RRF)
  RRF 融合排序                        reciprocal_rank_fusion() (RRF 常数 k=60)
  QualityScoreNode (质量评分)         _grade()  过滤低相关片段，规避幻觉
  QueryRewriteNode (查询重写)         _rewrite() 自我纠错
  GenerateNode (生成)                _generate() 带来源引用，防止编造
  条件路由 (self-correct loop)        _decide() -> conditional_edges

自我纠错闭环：
  retrieve -> grade_documents
      ├─ 相关片段不足 & 未超循环上限 -> rewrite_query -> retrieve
      └─ 相关片段足够 / 达到上限      -> generate -> END
"""

from __future__ import annotations

import os
import re
import logging
from typing import List, Dict, Any, TypedDict

import numpy as np
import jieba
from rank_bm25 import BM25Okapi
from sentence_transformers import SentenceTransformer
from openai import OpenAI

logger = logging.getLogger(__name__)


# ----------------------------- LLM 封装（通义千问 DashScope，OpenAI 兼容）-----------------------------
class LLM:
    def __init__(self, api_key: str | None = None,
                 base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                 model: str = "qwen-plus"):
        self.client = OpenAI(api_key=api_key or os.getenv("DASHSCOPE_API_KEY"), base_url=base_url)
        self.model = model

    def chat(self, system: str, user: str, temperature: float = 0.2) -> str:
        resp = self.client.chat.completions.create(
            model=self.model,
            temperature=temperature,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        )
        return resp.choices[0].message.content.strip()


# ----------------------------- 混合检索：Elasticsearch(BM25+KNN) + 应用层 RRF -----------------------------
def tokenize(text: str) -> List[str]:
    # 中文按 jieba 分词，英文保留原词；过滤空串
    return [w for w in jieba.lcut(text) if w.strip()]


class HybridRetriever:
    """混合检索器：BM25 与 KNN 两路均交由 Elasticsearch 承担，应用层用 RRF 融合。

    - text 字段：ik_max_word 分词做 BM25 关键词检索（替代原 rank_bm25）
    - embedding 字段：dense_vector + cosine 做 KNN 向量检索（替代原 FAISS/NumPy）
    - 融合：保留原有 1/(rank+1+60) 的 RRF 逻辑，融合在应用层完成，不交给 ES
    对外接口 retrieve(query, k) 不变，上层 LangGraph 工作流无需改动。
    若 ES 不可用（连接失败 / 插件缺失 / 客户端未装），自动降级为内存版
    rank_bm25 + NumPy，保证检索能力始终可用。
    """

    RRF_K = 60  # RRF 常数，与原实现一致

    def __init__(self, corpus: List[str], embed_model: str = "BAAI/bge-small-zh-v1.5",
                 es_host: str | None = None, index_name: str | None = None):
        self.corpus = corpus
        self.encoder = SentenceTransformer(embed_model)
        self.index = index_name or os.getenv("ES_INDEX", "fawentong_clauses")
        self._use_es = False
        # 优先接入 ES；任何异常都降级为内存 BM25 + NumPy
        try:
            from elasticsearch import Elasticsearch
            from elasticsearch.helpers import bulk
            host = es_host or os.getenv("ES_HOST", "http://localhost:9200")
            self._es = Elasticsearch(hosts=host, request_timeout=30)
            if not self._es.ping():
                raise ConnectionError("Elasticsearch ping 失败（地址错误或安全配置问题）")
            self._bulk = bulk
            if not self._es.indices.exists(index=self.index):
                self._es.indices.create(index=self.index, body=self._mapping())
                logger.info("已创建 ES 索引 %s", self.index)
            self.index_documents(corpus)          # 幂等覆盖（按 _id=doc_id）
            self._use_es = True
            logger.info("HybridRetriever 已接入 ES（index=%s, docs=%d）", self.index, len(corpus))
        except Exception as exc:  # 降级
            logger.warning("ES 不可用，回退内存 rank_bm25+NumPy：%s", exc)
            self._init_fallback(corpus)

    # ---------- ES 索引 / 写入 ----------
    def _embedding_dim(self) -> int:
        return self.encoder.get_sentence_embedding_dimension()

    def _mapping(self) -> Dict[str, Any]:
        # dense_vector 维度必须与 Sentence-Transformers 模型一致（bge-small-zh-v1.5 -> 512）
        return {
            "settings": {"number_of_shards": 1, "number_of_replicas": 0},
            "mappings": {
                "properties": {
                    "text": {"type": "text", "analyzer": "ik_max_word"},
                    "embedding": {
                        "type": "dense_vector",
                        "dims": self._embedding_dim(),
                        "index": True,
                        "similarity": "cosine",
                    },
                    "doc_id": {"type": "integer"},
                }
            },
        }

    def index_documents(self, corpus: List[str]) -> None:
        """用 Sentence-Transformers 生成向量，批量写入 ES（bulk API）。
        doc_id 取语料下标便于融合后回查原文；_id 同 doc_id 以支持幂等覆盖。"""
        embs = self.encoder.encode(corpus, normalize_embeddings=True)
        actions = [
            {
                "_index": self.index,
                "_id": str(i),
                "_source": {"text": text, "embedding": embs[i].tolist(), "doc_id": i},
            }
            for i, text in enumerate(corpus)
        ]
        if actions:
            self._bulk(self._es, actions)
            self._es.indices.refresh(index=self.index)
        logger.info("已写入 %d 条文档到 ES 索引 %s", len(actions), self.index)

    # ---------- 对外统一接口：retrieve（签名不变） ----------
    def retrieve(self, query: str, k: int = 10) -> List[Dict[str, Any]]:
        bm25_top = self._bm25_top(query, k)
        knn_top = self._knn_top(query, k)
        fused: Dict[int, float] = {}
        for rank, idx in enumerate(bm25_top):
            fused[idx] = fused.get(idx, 0.0) + 1.0 / (rank + 1 + self.RRF_K)
        for rank, idx in enumerate(knn_top):
            fused[idx] = fused.get(idx, 0.0) + 1.0 / (rank + 1 + self.RRF_K)
        ranked = sorted(fused.items(), key=lambda x: x[1], reverse=True)
        return [{"idx": i, "text": self.corpus[i], "score": round(float(s), 4)} for i, s in ranked[:k]]

    # ---------- 两路检索（ES 路径 / 降级路径） ----------
    def _bm25_top(self, query: str, k: int) -> List[int]:
        if not self._use_es:
            return self._bm25_top_fallback(query, k)
        res = self._es.search(
            index=self.index,
            body={"size": k, "query": {"match": {"text": query}}, "_source": ["doc_id"]},
        )
        return [int(h["_source"]["doc_id"]) for h in res["hits"]["hits"]]

    def _knn_top(self, query: str, k: int) -> List[int]:
        if not self._use_es:
            return self._knn_top_fallback(query, k)
        qvec = self.encoder.encode([query], normalize_embeddings=True).tolist()[0]
        res = self._es.search(
            index=self.index,
            body={
                "size": k,
                "knn": {
                    "field": "embedding",
                    "query_vector": qvec,
                    "k": k,
                    "num_candidates": max(k * 10, 100),
                },
                "_source": ["doc_id"],
            },
        )
        return [int(h["_source"]["doc_id"]) for h in res["hits"]["hits"]]

    # ---------- 降级方案：内存 rank_bm25 + NumPy ----------
    def _init_fallback(self, corpus: List[str]) -> None:
        self.tokenized = [tokenize(c) for c in corpus]
        self.bm25 = BM25Okapi(self.tokenized)
        self.emb = self.encoder.encode(corpus, normalize_embeddings=True).astype("float32")

    def _bm25_top_fallback(self, query: str, k: int) -> List[int]:
        scores = self.bm25.get_scores(tokenize(query))
        return list(np.argsort(scores)[::-1][:k])

    def _knn_top_fallback(self, query: str, k: int) -> List[int]:
        q = self.encoder.encode([query], normalize_embeddings=True).astype("float32")[0]
        sims = self.emb @ q
        return list(np.argsort(sims)[::-1][:k])


# ----------------------------- 智能体状态与工作流 -----------------------------
class AgentState(TypedDict):
    question: str
    rewritten_question: str
    documents: List[Dict[str, Any]]
    graded_docs: List[Dict[str, Any]]
    answer: str
    loop_count: int


MAX_LOOP = 2            # 自我纠错最大循环次数（对应 Java 版的最大重试）
GRADE_THRESHOLD = 0.6   # 质量评分 gate 阈值


class ContractAgenticRAG:
    def __init__(self, corpus: List[str], llm: LLM, retriever: HybridRetriever):
        self.llm = llm
        self.retriever = retriever
        self.graph = self._build()

    # —— 节点 1：混合检索 ——
    def _retrieve(self, state: AgentState) -> Dict[str, Any]:
        q = state.get("rewritten_question") or state["question"]
        docs = self.retriever.retrieve(q, k=8)
        return {"documents": docs}

    # —— 节点 2：质量评分（过滤低相关片段，规避幻觉）——
    def _grade(self, state: AgentState) -> Dict[str, Any]:
        graded = []
        for d in state["documents"]:
            sys = "你是严谨的合同法务审核助手，只判断片段与问题的相关性。"
            usr = (f"用户问题：{state['question']}\n"
                   f"候选片段：{d['text']}\n"
                   f"请只回答：相关 或 不相关，并给出 0-1 置信度，格式：相关|0.87")
            out = self.llm.chat(sys, usr)
            m = re.search(r"(相关|不相关)\s*\|\s*([0-9.]+)", out)
            if m and m.group(1) == "相关" and float(m.group(2)) >= GRADE_THRESHOLD:
                graded.append(d)
        return {"graded_docs": graded}

    # —— 节点 3：查询重写（自我纠错）——
    def _rewrite(self, state: AgentState) -> Dict[str, Any]:
        sys = "你是合同检索优化助手，负责把用户问题改写为更利于知识库检索的问法，保留法律意图。"
        usr = f"原始问题：{state['question']}\n请输出改写后的检索问句（单行）。"
        new_q = self.llm.chat(sys, usr)
        return {"rewritten_question": new_q.strip(), "loop_count": state["loop_count"] + 1}

    # —— 节点 4：生成（带来源引用，防止编造）——
    def _generate(self, state: AgentState) -> Dict[str, Any]:
        if not state["graded_docs"]:
            return {"answer": "抱歉，知识库中未检索到相关合同条款，无法作答。"}
        ctx = "\n".join(f"[{i + 1}] {d['text']}" for i, d in enumerate(state["graded_docs"]))
        sys = "你是专业合同法务 AI 助手，严格基于给定片段作答，不得编造；如片段不足请说明。"
        usr = (f"参考片段：\n{ctx}\n\n"
               f"用户问题：{state['question']}\n"
               f"请给出解答并标注引用的片段编号。")
        ans = self.llm.chat(sys, usr)
        return {"answer": ans}

    # —— 条件路由：相关不足则重写重试，否则生成 ——
    def _decide(self, state: AgentState) -> str:
        if state["graded_docs"] and state["loop_count"] < MAX_LOOP:
            return "generate"
        if state["loop_count"] >= MAX_LOOP:
            return "generate"   # 达到上限，用现有片段强制生成
        return "rewrite_query"

    def _build(self):
        from langgraph.graph import StateGraph, START, END
        b = StateGraph(AgentState)
        b.add_node("retrieve", self._retrieve)
        b.add_node("grade_documents", self._grade)
        b.add_node("rewrite_query", self._rewrite)
        b.add_node("generate", self._generate)
        b.add_edge(START, "retrieve")
        b.add_edge("retrieve", "grade_documents")
        b.add_conditional_edges(
            "grade_documents", self._decide,
            {"generate": "generate", "rewrite_query": "rewrite_query"}
        )
        b.add_edge("rewrite_query", "retrieve")
        b.add_edge("generate", END)
        return b.compile()

    def ask(self, question: str) -> str:
        result = self.graph.invoke({
            "question": question,
            "rewritten_question": "",
            "documents": [],
            "graded_docs": [],
            "answer": "",
            "loop_count": 0,
        })
        return result["answer"]


# ----------------------------- 演示 -----------------------------
if __name__ == "__main__":
    # 示例语料：真实场景替换为合同 / 法条切片
    corpus = [
        "本合同自双方签字盖章之日起生效，有效期为三年。",
        "甲方应在货物交付后三十日内支付全部价款，逾期按日万分之五支付违约金。",
        "因不可抗力导致不能履行合同的，根据不可抗力影响部分或全部免除责任。",
        "保密条款在本合同终止后三年内继续有效。",
    ]
    llm = LLM()  # 需要环境变量 DASHSCOPE_API_KEY
    retriever = HybridRetriever(corpus)
    agent = ContractAgenticRAG(corpus, llm, retriever)
    print(agent.ask("甲方逾期付款要付多少违约金？"))
