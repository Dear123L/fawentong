# 法问通 · AgenticRAG 合同问答智能体（Python 版）

对应原 Java 项目（AceCompetition 中整合的 LangGraph4j RAG 工作流）的 **Python 重写**，
用于投递「全栈软件工程师（AI 智能体方向）」岗位时，如实展示 Python + LangGraph + RAG + 多节点智能体能力。

## 它实现了什么（一一对应你简历里写的亮点）

| 简历里写的（Java/LangGraph4j） | Python 版落点 |
|---|---|
| BM25 + KNN 混合检索 + RRF 融合排序 | `HybridRetriever`：rank_bm25 + sentence-transformers 向量 + RRF（k=60） |
| 质量评分节点（规避幻觉） | `_grade()`：LLM 对相关度打分，低于阈值过滤 |
| 查询重写节点（自我纠错） | `_rewrite()`：相关片段不足时改写问题并重检，最多 2 轮 |
| AgenticRAG 工作流 | `ContractAgenticRAG`：LangGraph `StateGraph` + 条件路由闭环 |

## 运行

```bash
pip install -r requirements.txt
export DASHSCOPE_API_KEY="你的通义千问 API Key"
python agentic_rag.py
```

- 向量检索默认用 numpy 点积（语料不大时足够）；百万级片段换成 faiss（已注释示例）。
- 嵌入模型默认 `BAAI/bge-small-zh-v1.5`，可换 `text-embedding-v2`（DashScope）等。
- 真实场景把 `corpus` 换成合同 / 法条切片（建议按条款 + 元数据切片，便于引用溯源）。

## ⚠️ 简历诚信红线（务必看）

1. **只有你真的跑通过这份 Python 代码，简历才能写"用 Python 实现 / 重构 RAG 智能体"。**
   如果只是准备投、还没真正写/跑，建议写"正在用 Python(LangGraph) 重构"或保留 Java 版描述，不要写成已交付。
2. **89% 准确率**：这是你原 Java 版简历里的数字。若没在 Python 版实测，
   不要写"Python 版达 89%"，可写"Java 版实测 89%，Python 版架构对齐"。
3. **不要跨项目冒领**：Java 平台的成果（WebSocket 集群、OSS 直传、点赞削峰、ES 检索）
   留在 AceCompetition；Python 的 RAG 智能体留在法问通。两个项目分别写实。

## 与岗位 JD 的契合点（岗位一：全栈软件工程师 · AI 智能体方向）

- "精通 Python" → 这份代码就是 Python 实证。
- "构建 AI 智能体 / RAG / 多智能体编排" → LangGraph 多节点 + 自我纠错闭环。
- "把 AI 投入生产" → 后端 API + Web 前端承接智能体（法问通平台侧）。
- "提示词工程" → `_grade` / `_rewrite` / `_generate` 三个节点的 system prompt 即提示词设计。
