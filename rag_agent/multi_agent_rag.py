"""
法问通 · 多智能体协作版（业务驱动，非为贴 JD 而生）
====================================================

为什么是多智能体，而不是把单图版再堆节点？
------------------------------------------------
合同审查问答的真实场景里，用户问题天然分三类，且机制完全不同：
  1) 检索解释型：『保密条款有什么风险』『违约金条款是否合法』
     → 需要 RAG 检索条款/法条 + 语义生成解释（偏检索与语言）。
  2) 计算型：『本金 100 万、日万分之五、逾期 30 天，违约金多少』
     → 需要解析数值并精确计算（偏数值，LLM 直接算易错）。
  3) 混合型：既要检索条款依据，又要算出金额（最常见，如『逾期付款要赔多少』）。

让一个 Agent 包办两类机制不同的任务，既不利于各司其职，也扩大幻觉面。
因此拆成多智能体协作：

  ├─ Coordinator（调度智能体）：意图识别，决定走 检索 / 计算 / 两者。
  ├─ Retriever Agent（检索智能体）：复用 RAG 核心（混合检索 + RRF +
  │      质量评分 + 查询重写），并挂载 cite_clause 工具做条款溯源。
  ├─ Calculator Agent（计算智能体）：解析数值，挂载 calculate_penalty
  │      工具做精确违约金计算。
  └─ Answer Agent（综合智能体）：汇总检索依据与计算结果，生成最终答复。

工具调用（function calling）—— 是真实业务需要，不是装饰：
  • calculate_penalty(principal, daily_rate_per_mille, days)
        合同违约金 = 本金 × 日利率(千分比) × 天数。
        为什么必须做成工具：法律金额要求确定性，LLM 做多步小数乘法容易算错，
        交给定点 Python 函数最稳，且可被测试、可审计。
  • cite_clause(query, top_k)
        从合同知识库检索最相关条款原文及相似度分数（来源可追溯）。
        为什么必须做成工具：法律回答必须『挂依据』，把『依据哪条』显式化为
        可调用的动作，既压制幻觉，也便于前端展示引用来源。

注：本文件复用单图版已验证的检索核心 HybridRetriever（api.py 已证明 import 安全），
不重复造轮子；新增的是多智能体编排与工具调用这两层能力。
"""

from __future__ import annotations

import os
import json
from typing import TypedDict, List, Dict, Any

from openai import OpenAI
# 复用单图版已验证的检索核心（避免重复实现混合检索/RRF）
from agentic_rag import HybridRetriever, ContractAgenticRAG, LLM

# ----------------------------- 业务语料（与单图版场景一致：合同 + 法条切片）-----------------------------
CORPUS: List[str] = [
    "本合同自双方签字盖章之日起生效，有效期为三年。",
    "甲方应在货物交付后三十日内支付全部价款，逾期按日万分之五支付违约金。",
    "因不可抗力导致不能履行合同的，根据不可抗力影响部分或全部免除责任。",
    "保密条款在本合同终止后三年内继续有效。",
    "定金不得超过主合同标的额的百分之二十，超过部分不产生定金效力。",
    "当事人一方不履行合同义务或者履行合同义务不符合约定的，应当承担继续履行、"
    "采取补救措施或者赔偿损失等违约责任。",
]

# ----------------------------- LLM 客户端（支持 tools / function calling）-----------------------------
_client = OpenAI(
    api_key=os.getenv("DASHSCOPE_API_KEY"),
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
)
_MODEL = "qwen-plus"

# 全局检索器：由 init_retriever() 在启动时注入（与 api.py 的 CORPUS 加载保持一致）
_retriever: HybridRetriever | None = None
# 单图版 AgenticRAG 工作流实例（已含 检索→评分→重写→生成），供 retriever 节点复用
_agentic: "ContractAgenticRAG | None" = None


def init_retriever(corpus: List[str]) -> None:
    """注入语料并初始化共享检索器 + 单图版 AgenticRAG 工作流（服务启动时调用）。"""
    global _retriever, _agentic
    _retriever = HybridRetriever(corpus)
    # 复用单图版已验证的工作流（检索→评分→重写→生成），不重复造轮子
    _agentic = ContractAgenticRAG(
        corpus,
        LLM(api_key=os.getenv("DASHSCOPE_API_KEY")),
        _retriever,
    )


def llm_chat(messages: List[Dict[str, Any]], tools=None, tool_choice: str = "auto"):
    """封装通义千问对话；可携带 tools 实现 function calling。返回原始 message。"""
    kwargs: Dict[str, Any] = {"model": _MODEL, "messages": messages, "temperature": 0.2}
    if tools:
        kwargs["tools"] = tools
        kwargs["tool_choice"] = tool_choice
    resp = _client.chat.completions.create(**kwargs)
    return resp.choices[0].message


# ----------------------------- 工具实现（确定性函数，可单测）-----------------------------
def calculate_penalty(principal: float, daily_rate_per_mille: float, days: int) -> Dict[str, Any]:
    """合同违约金 = 本金 × 日利率(千分比) × 天数。返回结构化结果便于审计与展示。"""
    penalty = principal * (daily_rate_per_mille / 1000.0) * days
    return {
        "principal": principal,
        "daily_rate_per_mille": daily_rate_per_mille,
        "days": days,
        "penalty": round(penalty, 2),
        "formula": f"{principal} × ({daily_rate_per_mille}/1000) × {days}",
    }


def cite_clause(query: str, top_k: int = 3) -> List[Dict[str, Any]]:
    """从合同知识库检索最相关条款原文及相似度（来源可追溯）。"""
    if _retriever is None:
        return []
    docs = _retriever.retrieve(query, k=top_k)
    return [{"text": d["text"], "score": d["score"]} for d in docs]


# 工具 schema（OpenAI 兼容 function calling 描述）
TOOLS: List[Dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "calculate_penalty",
            "description": (
                "计算合同违约金金额。当用户询问逾期/违约赔偿金、按日利率计算的赔偿数额时使用。"
                "法律金额必须精确，交由本工具计算。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "principal": {"type": "number", "description": "违约涉及的本金金额，单位：元"},
                    "daily_rate_per_mille": {
                        "type": "number",
                        "description": "合同约定的日违约金比例，以千分比表示。例如『日万分之五』填 5，『日千分之一』填 1",
                    },
                    "days": {"type": "integer", "description": "逾期或违约的天数"},
                },
                "required": ["principal", "daily_rate_per_mille", "days"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "cite_clause",
            "description": (
                "从合同知识库检索并返回与问题最相关的条款原文及相似度分数，"
                "让回答有据可查、抑制幻觉。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "需要溯源的法律问题或条款关键词"},
                    "top_k": {"type": "integer", "description": "返回的相关条款条数，默认 3"},
                },
                "required": ["query"],
            },
        },
    },
]


def _run_tools(message) -> List[Dict[str, Any]]:
    """执行 message 中声明的 tool_calls，返回 (tool_role_messages, textual_results)。"""
    tool_messages: List[Dict[str, Any]] = []
    textual: List[str] = []
    for tc in (message.tool_calls or []):
        fn_name = tc.function.name
        args = json.loads(tc.function.arguments or "{}")
        if fn_name == "calculate_penalty":
            result = calculate_penalty(**args)
        elif fn_name == "cite_clause":
            result = cite_clause(**args)
        else:
            continue
        tool_messages.append(
            {"role": "tool", "name": fn_name, "content": json.dumps(result, ensure_ascii=False)}
        )
        # 把结构化结果转成可读文本，便于后续综合
        textual.append(f"[{fn_name}] {json.dumps(result, ensure_ascii=False)}")
    return tool_messages, textual


# ----------------------------- 各智能体（独立职责）-----------------------------
def retriever_agent(question: str) -> str:
    """检索智能体：先跑单图版 RAG 工作流（检索→评分→重写→生成）得到带引用的依据，
    再用 LLM + cite_clause 工具做解释与溯源补强。cite_clause 仍是检索 Agent 的一部分，
    但不再是唯一检索手段。"""
    if _agentic is None:
        return "（检索器未初始化）"
    # 1) 主检索：单图版 AgenticRAG 工作流（retrieve→grade→rewrite→generate）
    rag_answer = _agentic.ask(question)
    # 2) 解释 + 溯源：保留 cite_clause 工具调用能力，作为检索 Agent 的补强环节
    sys = "你是合同检索智能体，基于已有检索结论解释条款含义与风险，必要时调用 cite_clause 补强引用。"
    messages = [
        {"role": "system", "content": sys},
        {"role": "user", "content": f"用户问题：{question}\n\n已有检索结论：\n{rag_answer}\n\n请解释并点明依据。"},
    ]
    msg = llm_chat(messages, tools=TOOLS)
    if msg.tool_calls:
        tool_msgs, _ = _run_tools(msg)
        follow = llm_chat(
            messages
            + [{"role": "assistant", "content": None, "tool_calls": msg.tool_calls}]
            + tool_msgs,
        )
        return follow.content or rag_answer
    return msg.content or rag_answer


def calculator_agent(question: str) -> str:
    """计算智能体：解析数值，调用 calculate_penalty 工具得出确定金额。"""
    sys = "你是合同计算智能体，负责解析用户问题中的数值并调用计算工具得出确定金额。"
    messages = [
        {"role": "system", "content": sys},
        {"role": "user", "content": f"请解析并计算：{question}"},
    ]
    msg = llm_chat(messages, tools=TOOLS)
    if msg.tool_calls:
        tool_msgs, _ = _run_tools(msg)
        follow = llm_chat(
            messages
            + [{"role": "assistant", "content": None, "tool_calls": msg.tool_calls}]
            + tool_msgs,
        )
        return follow.content or ""
    return msg.content or ""


def answer_agent(question: str, retrieve_text: str, calc_text: str) -> str:
    """综合智能体：汇总检索依据与计算结果，生成带引用的最终答复，必要时再溯源。"""
    sys = "你是合同问答综合智能体，整合检索依据与计算结果，给出严谨、带引用的最终答复。"
    parts = []
    if retrieve_text:
        parts.append(f"【条款依据】\n{retrieve_text}")
    if calc_text:
        parts.append(f"【计算结果】\n{calc_text}")
    ctx = "\n\n".join(parts) if parts else "（无）"
    messages = [
        {"role": "system", "content": sys},
        {"role": "user", "content": f"用户问题：{question}\n\n已知信息：\n{ctx}\n\n请综合作答，并点明依据。"},
    ]
    msg = llm_chat(messages, tools=TOOLS)
    if msg.tool_calls:
        # 综合阶段也可能再调用 cite_clause 补强引用
        tool_msgs, _ = _run_tools(msg)
        follow = llm_chat(
            messages
            + [{"role": "assistant", "content": None, "tool_calls": msg.tool_calls}]
            + tool_msgs,
        )
        return follow.content or ""
    return msg.content or ""


def classify_intent(question: str) -> str:
    """调度智能体：意图识别，返回 retrieve / calculate / both。"""
    sys = (
        "你是合同问答调度智能体。判断用户问题类型：\n"
        "retrieve = 需要查条款/法条并解释；\n"
        "calculate = 需要计算金额/天数/比例等数值；\n"
        "both = 既要查条款依据又要算金额。\n"
        "只回答一个词：retrieve / calculate / both。"
    )
    msg = llm_chat(
        [{"role": "system", "content": sys}, {"role": "user", "content": question}]
    )
    text = (msg.content or "").strip().lower()
    if "both" in text:
        return "both"
    if "calc" in text:
        return "calculate"
    return "retrieve"


# ----------------------------- LangGraph 多智能体编排 -----------------------------
class MAState(TypedDict):
    question: str
    intent: str
    retrieve_result: str
    calc_result: str
    answer: str


def _coordinator(state: MAState) -> Dict[str, Any]:
    state["intent"] = classify_intent(state["question"])
    return state


def _retriever_node(state: MAState) -> Dict[str, Any]:
    state["retrieve_result"] = retriever_agent(state["question"])
    return state


def _calculator_node(state: MAState) -> Dict[str, Any]:
    state["calc_result"] = calculator_agent(state["question"])
    return state


def _answer_node(state: MAState) -> Dict[str, Any]:
    state["answer"] = answer_agent(
        state["question"], state["retrieve_result"], state["calc_result"]
    )
    return state


def _route_coord(state: MAState) -> str:
    # 纯计算问题直接进计算器；其余（retrieve / both）都先走检索
    return "calculator" if state["intent"] == "calculate" else "retriever"


def _route_after_retriever(state: MAState) -> str:
    # 混合型(both)检索完还要算；纯检索直接综合
    return "calculator" if state["intent"] == "both" else "answer"


def build_graph():
    from langgraph.graph import StateGraph, START, END

    b = StateGraph(MAState)
    b.add_node("coordinator", _coordinator)
    b.add_node("retriever", _retriever_node)
    b.add_node("calculator", _calculator_node)
    b.add_node("answer", _answer_node)
    b.add_edge(START, "coordinator")
    b.add_conditional_edges(
        "coordinator", _route_coord,
        {"retriever": "retriever", "calculator": "calculator"},
    )
    b.add_conditional_edges(
        "retriever", _route_after_retriever,
        {"calculator": "calculator", "answer": "answer"},
    )
    b.add_edge("calculator", "answer")
    b.add_edge("answer", END)
    return b.compile()


def ask(question: str) -> str:
    """端到端多智能体问答。"""
    graph = build_graph()
    result = graph.invoke({
        "question": question,
        "intent": "",
        "retrieve_result": "",
        "calc_result": "",
        "answer": "",
    })
    return result["answer"]


# ----------------------------- 演示（需 DASHSCOPE_API_KEY）-----------------------------
if __name__ == "__main__":
    init_retriever(CORPUS)
    for q in [
        "甲方逾期付款要付多少违约金？",          # 预期：both → 检索 + 计算
        "保密条款有什么风险？",                  # 预期：retrieve
        "本金100万、日万分之五、逾期30天违约金多少",  # 预期：calculate / both
    ]:
        print(f"\n>>> {q}")
        print(ask(q))
