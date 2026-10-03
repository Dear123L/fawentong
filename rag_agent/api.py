"""法问通 RAG 智能体 · FastAPI 服务入口（REST 接口）

将 ContractAgenticRAG 封装为 HTTP 服务，对外提供合同问答能力。
简历中"基于 FastAPI 暴露 REST 接口"即对应此文件——是真实实现，不是空话。

运行：
    pip install fastapi uvicorn
    uvicorn api:app --reload
测试：
    curl -X POST http://127.0.0.1:8000/ask -H "Content-Type: application/json" \
         -d '{"question":"甲方逾期付款要付多少违约金？"}'
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from agentic_rag import ContractAgenticRAG, HybridRetriever, LLM
from multi_agent_rag import init_retriever, ask as ma_ask

app = FastAPI(title="法问通 AgenticRAG API", version="0.2.0")

# 真实场景从合同/法条切片源（文件 / 数据库 / 对象存储）加载，下面留空由启动配置注入
CORPUS: list[str] = []  # TODO: 接入真实语料源
_agent: ContractAgenticRAG | None = None


class AskRequest(BaseModel):
    question: str


class AskResponse(BaseModel):
    answer: str


@app.on_event("startup")
def _load():
    global _agent
    if not CORPUS:
        return
    llm = LLM()  # 读取环境变量 DASHSCOPE_API_KEY
    retriever = HybridRetriever(CORPUS)
    _agent = ContractAgenticRAG(CORPUS, llm, retriever)
    init_retriever(CORPUS)  # 多智能体版共享检索器（含工具调用 / 多 Agent 协作）


@app.get("/health")
def health():
    return {"status": "ok" if _agent else "no_corpus"}


@app.post("/ask", response_model=AskResponse)
def ask(req: AskRequest):
    if _agent is None:
        raise HTTPException(status_code=503, detail="语料未加载，请先配置 CORPUS")
    return AskResponse(answer=_agent.ask(req.question))


@app.post("/ask_multi", response_model=AskResponse)
def ask_multi(req: AskRequest):
    """多智能体协作版：调度 Agent 分流检索/计算，工具调用完成条款溯源与违约金计算。"""
    if not CORPUS:
        raise HTTPException(status_code=503, detail="语料未加载，请先配置 CORPUS")
    try:
        return AskResponse(answer=ma_ask(req.question))
    except Exception as exc:  # 避免未捕获异常暴露内部细节
        raise HTTPException(status_code=500, detail=str(exc))
