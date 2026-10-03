# API 文档

## 通用约定

- **Base URL**：`http://{host}:{port}/api/rag`
- **编码**：请求与响应均为 `application/json; charset=utf-8`（SSE 端点为 `text/event-stream`）
- **鉴权**：请求头 `Authorization: Bearer {token}`
- **通用响应结构**：

```json
{ "code": 200, "data": ... }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | 业务状态码，成功恒为 200 |
| `data` | any | 业务数据，各接口不同 |
| `meta` | object | **仅** `/chatAgent/multiDebug` 返回，见下 |

### userId 解析规则

`/api/rag/**` 在 `SecurityConfig` 中放行，由 `JwtAuthenticationFilter` 尽力解析：

1. 携带合法 token 且 DB 中存在对应用户 → 走 `UserContext` 解析真实 `userId`
2. token 有效但 DB 无此用户（手签的评测 token）→ 回落 filter 写入的 request 属性
3. 无 token → 回落 `1L`

这个设计让评测脚本无需真实用户即可跑通，同时生产环境有 token 时仍走真实身份。

---

## 一、多智能体问答

### GET /chatAgent/multi

生产端点。走完整 4 节点链路：范围判定 → 检索（+计算）→ 综合 → 评审。

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `kbId` | long | 是 | 知识库 ID |
| `sessionId` | string | 是 | 会话 ID，短期记忆的键；同一 sessionId 内多轮共享上下文 |
| `question` | string | 是 | 用户问题（口语化即可） |

**响应**

```json
{
  "code": 200,
  "data": "根据《民法典》第五百八十五条……（含 [1][2] 形式引用）"
}
```

---

### GET /chatAgent/multiDebug

与 `/chatAgent/multi` 完全同链路，额外返回 `meta` 供量化评测消费。
**生产调用不应使用此端点**（响应体更大，且暴露内部检索细节）。

**额外的 `meta` 结构**

```json
{
  "code": 200,
  "data": "……答案文本……",
  "meta": {
    "retrieved": [
      {
        "clauseId": "民法典-第585条",
        "content": "第五百八十五条　当事人可以约定……",
        "kbId": "1",
        "score": 0.0325,
        "bm25Rank": 3,
        "knnRank": 1
      }
    ],
    "extracted": { "principal": 1000000.0, "days": 30, "daily_rate_per_mille": 0.5 },
    "computed_penalty": 15000.0,
    "rejected": false,
    "replanned": true
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `retrieved` | array | 命中的条款片段。`clauseId` 即 ES 的 `_id`（如 `民法典-第585条`）。`score` 是加权 RRF 融合分；`bm25Rank` / `knnRank` 只在对应召回列表中出现 |
| `extracted` | object | 解析出的计算参数。`principal` 本金（元）、`days` 天数、`daily_rate_per_mille` 日利率（千分比，如 `0.5` = 万分之五） |
| `computed_penalty` | number \| null | 计算结果（元）。未触发计算或解析失败时为 `null` |
| `rejected` | boolean | `true` 表示因范围越界或检索失败而拒答 |
| `replanned` | boolean | `true` 表示 Critic 判定依据不足，触发了重规划重检索 |

**拒答时的 meta**：`rejected=true`、`retrieved=[]`、`computed_penalty=null`。
若因范围越界拒答，`rejected=true` 且完全不产生检索开销。

---

## 二、单图与单轮问答

### GET /chatAgent/stream

只跑内层检索子图（`retrieve → grade → (rewrite) → generate`），
**不做**范围判定、计算触发、评审。适用于"我明确知道问题在范围内"的场景。

参数与 `/chatAgent/multi` 相同。返回纯文本答案字符串。

### GET /chat/stream

单轮 RAG：检索 → 拼引用 → LLM 成文 → 落历史。SSE 流式返回。

### GET /chat/history

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | 是 | 会话 ID |

**响应**

```json
{
  "code": 200,
  "data": [
    { "question": "100万逾期30天违约金多少", "answer": "……" },
    { "question": "改成45天呢", "answer": "……" }
  ]
}
```

---

## 三、知识库管理

### POST /kb/create

**请求体**

```json
{ "name": "合同模板库", "type": "private", "description": "常用合同模板" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 知识库名称 |
| `type` | string | 否 | `private`（默认）/ `public` |
| `description` | string | 否 | 描述 |

**响应**：`data` 为新建的知识库对象（含回填的 `id`）。

### GET /kb/list

列出当前用户可见的知识库（自己的 + `public` 的），按创建时间倒序。

### POST /document/upload

**参数**：`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `kbId` | long | 是 | 目标知识库 |
| `file` | file | 是 | 文档（支持 `docx` / `txt` / `md` / `csv`） |

**处理流程**：落库 → 解析纯文本 → 按 1500 字切分（重叠 200）→ 逐片向量化 → 写入 ES。
接口**同步返回**，向量写入完成后才响应；大文件耗时较长。

**响应**

```json
{ "code": 200, "message": "上传成功，正在处理中..." }
```

---

## 四、记忆调试端点

### /api/memory/**

前缀 `/api/memory/**` 的端点**强制 JWT 保护**（不在放行范围内），用于观测记忆层：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/memory/metrics` | 四个 facet（history / calcParams / calcType / docs）的 hit/miss 计数 |
| GET | `/api/memory/debug/{sessionId}` | 查看某会话的记忆内容 |

这些端点用于验证"跨轮继承是否真的走到了结构化键"这类问题，不属于业务 API。

---

## 五、错误码

当前实现未做细分错误码，异常统一由 Spring 兜底。建议关注以下状态码：

| 状态码 | 场景 | 排查方向 |
|---|---|---|
| 401 | 无 token / token 无效 | 鉴权配置或 token 过期 |
| 500 | 未捕获异常 | 看日志，多为 ES 或 DashScope 连接失败 |
| 200 + `rejected=true` | 正常拒答 | **不是错误**，是范围判定或检索门控的结果 |

`rejected` 是设计内的正常输出，评测时不应计入失败样本。

---

## 六、调用示例

```bash
TOKEN=your_jwt_token

# 生产问答
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8082/api/rag/chatAgent/multi?kbId=1&sessionId=s1&question=100万逾期30天违约金多少"

# 带评测元数据
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8082/api/rag/chatAgent/multiDebug?kbId=1&sessionId=s1&question=100万逾期30天违约金多少"

# 范围外（应得 rejected=true）
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8082/api/rag/chatAgent/multiDebug?kbId=1&sessionId=s2&question=帮我注册一个商标"

# 上传文档
curl -H "Authorization: Bearer $TOKEN" \
  -F "kbId=1" -F "file=@合同模板.docx" \
  "http://localhost:8082/api/rag/document/upload"
```
