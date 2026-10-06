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

### 合同审查（review 意图）

同一个 `/chatAgent/multi` 端点，**不需要额外参数**——`ScopeCheck` 识别问句中的审查
意图词（审查 / 审核 / 检查 / 看看这份合同等）后自动分流到审查分支。

**触发方式**：把合同文本放进 `question`，配一个审查动词即可。

```
question=审查这份合同：\n第一条 ...\n第二条 ...
```

**响应结构**：`data` 为纯文本审查报告，逐条款一段，段内字段固定。

```
合同审查报告
（共审查 5 条，发现风险 1 条）
================================

1. <条款标签>
【原文】<条款原文>
【法规依据】<法域>-第N条; ...          ← 检索命中的法规，非风险条款也会列出
【数值校验】<确定性校验结论>             ← 数值类条款才有意义，其余为「非金额计算语境」
【风险点】<风险描述>                     ← 仅风险条目有
【改写建议】<修改方向>                   ← 仅风险条目有
【建议替换条款】<改写后的条款文本>        ← 模型给出时才有，未给出为（无）
```

字段说明：

| 字段 | 含义 | 何时出现 |
|---|---|---|
| 条款标签 | `第一条` 等；首条款前的引言标为 `{sessionId}-引言`，无编号整段标为 `-全文` | 总是 |
| `【法规依据】` | 该条款检索到的法规条文（`法域-第N条`），是判定的依据来源 | 总是 |
| `【数值校验】` | 数值类条款的确定性校验结果；非金额语境时为提示语 | 总是 |
| `【风险点】` | 风险描述。无风险时改为 `【结论】未发现明显风险。` | 仅风险条目 |
| `【改写建议】` | 修改方向 | 仅风险条目 |
| `【建议替换条款】` | 可直接替换的条款文本，模型未给出时为 `（无）` | 仅风险条目 |

报告末尾可能出现 `【合同完备性】` 条目：整份合同缺少必要要素时产生，
`【风险点】` 列出缺失项（主体/标的/数量/交付时间/价款/违约责任/争议解决/生效条件）。

**判定口径**（决定同一份合同在不同版本下结论可能不同）：

| 审查维度 | 判定方 | 说明 |
|---|---|---|
| 数值越界（利率 > 4×LPR、违约金比例 > 30%） | 规则 | 确定性，不经模型 |
| 权利义务失衡、免责、解释权 | 模型 | 语义判断 |
| 合同要素是否缺失 | 规则 | 8 项固定检查，条款数 ≥ 2 且缺失 ≥ 2 项才提示 |

同一份合同文本的审查结果会按条款文本 SHA-256 缓存 7 天（Redis 键 `review:clause:*`），
重复审查相同条款直接复用结果。

**多轮追问的限制**：审查结果**不写入短期记忆的条款锚定**（`retrievedDocs` 只记问答路径），
因此在审查之后追问「第三条为什么有问题」会退化为普通问答，且无法定位到具体条款——
它会把上一轮检索过的全部条款当作上下文，答复质量会明显下降。
需要针对具体条款追问时，建议在 `question` 中带上条款原文，让检索重新定位。

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

# 合同审查（同一端点，靠问句里的审查意图词触发）
curl -H "Authorization: Bearer $TOKEN" --get \
  --data-urlencode "kbId=1" --data-urlencode "sessionId=s3" \
  --data-urlencode "question=审查这份合同：
第一条 甲方应于2026年3月1日前向乙方交付货物。
第二条 乙方应于收到货物后7日内支付价款。
第三条 违约方应按合同总价的百分之五十向守约方支付违约金。
第四条 双方发生争议可向有管辖权的人民法院起诉。" \
  "http://localhost:8082/api/rag/chatAgent/multi"

# 上传文档
curl -H "Authorization: Bearer $TOKEN" \
  -F "kbId=1" -F "file=@合同模板.docx" \
  "http://localhost:8082/api/rag/document/upload"
```
