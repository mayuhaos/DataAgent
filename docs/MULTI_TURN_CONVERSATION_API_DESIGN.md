# 多轮数据对话 API 方案

## 1. 背景

当前 DataAgent 的数据问答链路可以完成单次分析、SQL 查询、Python 分析和报告生成，也已经有 `chat_session`、`chat_message` 与基础的 `analysis_artifact` 持久化能力。

单轮链路无法稳定处理以下自然语言追问：

- “华南呢？”：继承上一轮指标和时间范围，仅替换地区。
- “给我看明细”：从上一轮聚合口径下钻到明细粒度。
- “按客户汇总”：继承过滤条件，切换分组维度。
- “把刚才报告改得简洁些”：修改报告版本，不重新查库。
- “把柱状图改成折线图”：只有现有结果满足条件时才复用数据重渲染。
- “回到库存问题”：同一会话中存在多个相关或不相关主题，不能默认引用最近一轮。

本方案采用 **直接调用大模型 Chat API + 本地受控执行** 的方式实现多轮能力，不使用 Agent、自主工具调用或模型循环执行。

## 2. 目标与非目标

### 2.1 目标

1. 支持同一会话内 N 轮相关、不相关、跨主题的自然语言数据问答。
2. 支持基于历史查询结果或报告的修改、解释、比较、派生和下钻。
3. 根据用户语义和现有数据能力选择复用结果、本地转换、重新查询、重新生成报告或澄清。
4. 不将全量会话记录或全量结果 JSON 发送给模型，保证输入长度可控。
5. 保持现有正常问数流程可用；多轮能力不可用时可降级为单轮正常问数。
6. 大模型只负责语义理解和返回结构化计划；权限、数据访问、SQL 执行和策略裁决全部留在本地。

### 2.2 非目标

1. 不构建 Agent，不允许模型直接调用数据库、内部工具或无限循环。
2. 不把完整结果集、数据库凭据、用户敏感原始数据传给远程模型。
3. 不要求通过关键词或穷举意图判断用户请求。
4. 本期不移除现有 `agentId` 领域实体。它仍可作为数据源、语义模型、知识和权限配置的作用域，但不再承担 Agent 运行语义。

## 3. 核心约定

### 3.1 ID 语义

| ID | 含义 | 生命周期 | 用途 |
|---|---|---|---|
| `sessionId` | 用户聊天会话 ID | 长期稳定 | 多轮记忆、Topic、Artifact 归属 |
| `threadId` | 单次图/流运行 ID | 单轮短生命周期 | SSE 重连、澄清、人工反馈、取消运行 |
| `topicId` | 会话内语义主题 ID | 可跨多轮 | 销售、库存、交付等主题聚类 |
| `artifactId` | 可引用分析对象 ID | 长期，受 TTL/状态控制 | 查询结果、图表、报告、答案版本引用 |
| `resultRef` | 完整结果受控存储引用 | 有 TTL | 本地复用、转换、重渲染，不发送给模型 |

普通追问必须保持相同 `sessionId`，但必须创建新的 `threadId`。仅澄清回复、人工反馈、SSE 重连才复用原 `threadId`。

### 3.2 模型与本地职责

| 职责 | 大模型 Chat API | 本地服务 |
|---|---:|---:|
| 识别用户引用的 Topic / Artifact | 是 | 提供候选对象 |
| 理解用户变更要求 | 是 | 校验输出 Schema |
| 决定建议执行模式 | 是 | 最终裁决 |
| 权限、租户、脱敏校验 | 否 | 是 |
| 读取完整结果 | 否 | 是 |
| SQL 生成和执行 | 否 | 是 |
| 本地结果转换、图表渲染 | 否 | 是 |
| 报告文本生成 | 可选 | 本地调用独立内容生成 API |
| 保存 Session / Topic / Artifact | 否 | 是 |

### 3.3 关键原则

1. 模型输出的是 `OperationPlan`，不是可执行 SQL、工具调用或最终权限决策。
2. 本地执行器不盲信模型：任何计划都必须通过 `PlanValidator`。
3. “仅展示变化”是否可以快速执行，由模型理解 + 本地结果可渲染性校验共同决定。
4. 数据口径、数据范围、指标、维度、时间、粒度发生变化时，必须重新查询或明确澄清。
5. 报告和图表不可原地覆盖，必须生成有父子关系的新 Artifact 版本。

## 4. 数据模型

### 4.1 会话对象图

```text
Session
  ├─ Topic: 销售分析
  │   ├─ QueryResult artifact: 按月销售额
  │   ├─ Chart artifact: 柱状图
  │   └─ Report artifact: v1 -> v2
  ├─ Topic: 库存分析
  │   └─ QueryResult artifact: 仓库库存汇总
  └─ Topic: 自由问答
```

消息记录用于展示和审计；Artifact 用于机器可理解的历史引用；Topic 用于解决同一会话内多个不相关主题。

### 4.2 Artifact

现有 `analysis_artifact` 需要扩展或演进为通用 Artifact。建议字段如下：

| 字段 | 说明 |
|---|---|
| `id` | Artifact ID |
| `session_id` | 所属会话 |
| `topic_id` | 所属主题 |
| `parent_artifact_id` | 派生/修订来源 |
| `type` | `QUERY_RESULT`、`CHART`、`REPORT`、`ANSWER` |
| `input_spec` | 结构化数据语义：指标、维度、过滤、排序、时间、粒度、TopN |
| `sql_query` | 实际执行 SQL，仅本地受控使用 |
| `result_ref` | 完整结果缓存/临时表引用 |
| `result_schema` | 字段、类型、业务含义 |
| `result_summary` | 行数、统计量、时间范围、关键值 |
| `result_sample` | 3-20 行有限脱敏样本 |
| `presentation_spec` | 表、图表、X/Y、排序、标题、字段显示 |
| `content_ref` | 报告正文或文件引用 |
| `provenance` | 数据源、Schema 版本、权限版本、脱敏策略、产生时间 |
| `status` | `SUCCESS`、`EXPIRED`、`INVALID`、`FAILED` |
| `expire_time` | `result_ref` 的有效期 |

### 4.3 Topic

建议新增 `conversation_topic`：

| 字段 | 说明 |
|---|---|
| `id` | Topic ID |
| `session_id` | 所属会话 |
| `title` | 例如“2026 年华东销售分析” |
| `summary` | 严格受长度限制的滚动摘要 |
| `status` | active / archived |
| `create_time` / `update_time` | 生命周期 |

Topic 不要求模型每轮新建。模型可以引用现有 Topic，也可以在本地允许的情况下建议创建新 Topic。

## 5. 模型 API 契约

### 5.1 API 定位

调用远程 OpenCloud 或 OpenAI 兼容的 Chat API。该调用不是 Agent，也不允许 tool calling。

模型 API 的第一类职责是 `conversation-plan`：输入当前用户问题和受控上下文，输出 `OperationPlan` JSON。

可选的第二类职责是 `content-generation`：在本地已确定数据和报告规格后，生成报告正文或解释文本。

### 5.2 `conversation-plan` 输入

模型输入必须受 token 预算控制，示例：

```json
{
  "userMessage": "把刚才销售报告改成折线图，并精简结论",
  "sessionSummary": "本会话包含销售、库存和交付三个分析主题。",
  "topics": [
    {"id": "topic-sales", "title": "2026 年华东销售分析", "summary": "按月销售额和客户贡献"},
    {"id": "topic-inventory", "title": "当前库存分析", "summary": "按仓库库存与缺货风险"}
  ],
  "candidateArtifacts": [
    {
      "id": "report-3",
      "topicId": "topic-sales",
      "type": "REPORT",
      "summary": "销售报告，引用按月销售额结果",
      "sourceArtifactIds": ["result-2"]
    },
    {
      "id": "result-2",
      "topicId": "topic-sales",
      "type": "QUERY_RESULT",
      "inputSpec": {"metrics": ["SUM(amount)"], "dimensions": ["month"]},
      "schema": ["month", "sales_amount"],
      "summary": {"rowCount": 12},
      "presentationSpec": {"type": "bar", "x": "month", "y": ["sales_amount"]}
    }
  ]
}
```

禁止发送：全量聊天消息、全量结果 JSON、数据库凭据、未脱敏敏感字段、`resultRef` 的实际访问凭据。

### 5.3 `OperationPlan` 输出

模型必须通过 JSON Schema / Structured Output 返回以下结构；不接受自由文本作为执行输入。

```json
{
  "operation": "CREATE | DERIVE | TRANSFORM | REVISE | EXPLAIN | COMPARE | CONTINUE | SWITCH_TOPIC | ASK_CLARIFICATION",
  "target": {
    "topicIds": ["topic-sales"],
    "artifactIds": ["report-3", "result-2"]
  },
  "changes": {
    "data": {
      "metrics": [],
      "dimensions": [],
      "filters": {},
      "timeRange": null,
      "grain": null,
      "sort": null,
      "limit": null
    },
    "presentation": {
      "chartType": "line",
      "x": null,
      "y": [],
      "title": null,
      "visibleFields": []
    },
    "report": {
      "style": "concise",
      "sections": []
    }
  },
  "executionMode": "NO_EXECUTION | REUSE | TRANSFORM_LOCAL | REGENERATE_REPORT | REQUERY | ASK_CLARIFICATION",
  "confidence": 0.0,
  "clarificationQuestion": null,
  "reason": "简短、可审计的判断依据"
}
```

## 6. 后端处理流程

### 6.1 总流程

```text
POST /api/conversations/{sessionId}/messages
  -> 保存用户消息
  -> 获取会话摘要、Topic、候选 Artifact
  -> 调用 conversation-plan 模型 API
  -> JSON Schema 校验
  -> PlanValidator 校验
  -> OperationExecutor 执行
  -> 保存新 Artifact / Topic / 消息
  -> SSE 返回进度与最终结果
```

### 6.2 Context Builder

Context Builder 必须从外部存储检索，不得按消息顺序拼接整个会话。

推荐 token 预算：

| 内容 | 建议预算 |
|---|---:|
| 会话摘要 | 500-1000 tokens |
| 每个 Topic 摘要 | 150-300 tokens |
| 候选 Artifact 卡片 | 每个 200-500 tokens |
| 当前最相关 Artifact 详情 | 800-1500 tokens |
| 结果样本 | 3-10 行，按字段裁剪 |
| 完整结果 JSON | 0 tokens，绝不发送 |

检索顺序：用户明确提及 ID/名称/时间 > Topic 或 Artifact 元数据匹配 > 向量或关键词检索 > 模型在候选集合中重排序。候选对象不明确时，模型返回 `ASK_CLARIFICATION`。

### 6.3 PlanValidator

模型计划在本地必须依次通过：

1. `sessionId`、Topic、Artifact 是否存在且归属当前会话。
2. 当前用户是否仍具备 Artifact 产生时所需的数据权限。
3. Artifact 的数据源、Schema、权限版本和 `resultRef` 是否仍有效。
4. `executionMode` 是否与请求的数据变化一致。
5. 现有结果是否满足新图表、字段、粒度和数据点数量要求。
6. 本地转换是否无损且不会扩大数据范围或改变业务口径。
7. JSON 中的字段、过滤条件和图表参数是否在允许的 Schema 内。

任何校验失败时：

- 数据不足或数据范围变化：升级为 `REQUERY`。
- 引用不明确：升级为 `ASK_CLARIFICATION`。
- 权限不足或 Artifact 不属于会话：拒绝执行。
- 远程模型不可用、JSON 无效：降级为现有单轮问数流程。

### 6.4 执行器

| 模式 | 执行器行为 | 是否查库 |
|---|---|---:|
| `NO_EXECUTION` | 基于已验证 Artifact 进行解释 | 否 |
| `REUSE` | 更改图例、标题、字段显示等纯展示属性 | 否 |
| `TRANSFORM_LOCAL` | 从 `resultRef` 读取完整结果后排序、TopN、无损过滤、透视 | 否 |
| `REGENERATE_REPORT` | 使用已有结果与报告规格生成新的报告版本 | 否 |
| `REQUERY` | 继承 `input_spec`、权限和数据范围，调用现有 Text-to-SQL/SQL 执行链路 | 是 |
| `ASK_CLARIFICATION` | 保存澄清消息，等待用户补充 | 否 |

### 6.5 正常问数兼容

现有问数能力不能被破坏。

```text
未传 sessionId 或多轮功能开关关闭
  -> 直接走现有 Graph / Text-to-SQL / SQL 执行流程

传入 sessionId 且多轮功能开关开启
  -> Conversation API 生成并校验 OperationPlan
  -> 若模式为 REQUERY，仍调用现有 Text-to-SQL / SQL 执行 / 报告能力
```

因此，多轮方案是现有数据执行链路的编排层，而不是替换其数据库查询能力。

## 7. 关键场景

### 7.1 数据范围修改

```text
第一轮：今年华东销售额是多少？
第二轮：华南呢？
```

模型计划引用销售 Artifact，继承指标和时间范围，仅将地区改为华南，模式为 `REQUERY`。本地复用现有 Text-to-SQL 和 SQL 执行能力。

### 7.2 明细下钻

```text
第一轮：按区域汇总今年销售额。
第二轮：给我看华东区明细。
```

模型计划引用聚合 Artifact，继承时间、区域、数据权限，要求切换到订单明细粒度，模式为 `REQUERY`。若明细粒度或可展示字段不明确，应返回澄清问题，不得编造字段。

### 7.3 仅报告修改

```text
把刚才销售报告改得简洁些，突出风险。
```

模型计划引用 Report Artifact，模式为 `REGENERATE_REPORT`。本地复用已验证的来源结果，不重新查询数据库，生成新的报告版本并设置 `parent_artifact_id`。

### 7.4 图表修改

```text
把柱状图改成折线图。
```

模型提出目标图表规格。PlanValidator 读取 Artifact 的 Schema 与完整结果统计，检查 X 轴是否有序、Y 轴是否数值、是否至少两个有效点、粒度是否匹配。

- 校验通过：`REUSE` 或 `REGENERATE_REPORT`，不查库。
- 当前结果不支持：升级为 `REQUERY` 或 `ASK_CLARIFICATION`。

### 7.5 跨 Topic

```text
回到库存问题，和销售额对比一下。
```

模型引用库存和销售两个 Topic 的 Artifact。PlanValidator 校验时间范围、单位和粒度是否可比较；可比较时本地转换或组合查询，不可比较时澄清或重新查询。

## 8. 结果缓存与安全

1. `resultRef` 使用 Redis、对象存储或临时结果表实现，必须有 TTL、会话归属和权限校验。
2. Artifact 只保存结果摘要和有限样本，样本默认最多 20 行，并先经过脱敏。
3. `resultRef` 过期后，历史 Artifact 仍可用于理解语义，但任何需要完整数据的操作必须重新查询。
4. 任何本地转换必须基于完整受控结果，不能基于发送给模型的小样本得出最终数据结论。
5. 每次执行记录：模型输入摘要哈希、OperationPlan、校验结果、实际 SQL、引用 Artifact、生成 Artifact、权限版本和耗时。

## 9. 建议接口

```text
POST /api/conversations/{sessionId}/messages
GET  /api/conversations/{sessionId}/topics
GET  /api/conversations/{sessionId}/artifacts
GET  /api/artifacts/{artifactId}
GET  /api/artifacts/{artifactId}/result
```

`POST /messages` 是前端唯一必须调用的多轮入口。前端只发送自然语言；服务端内部完成上下文构建、模型 API 调用、校验、执行、Artifact 保存和 SSE 输出。

## 10. 实施阶段

### 阶段 1：基础对象与兼容链路

1. 扩展 Artifact：`topicId`、`type`、`inputSpec`、`resultRef`、`provenance`、TTL。
2. 实现完整结果缓存和权限保护的 `resultRef` 读取。
3. 保留现有单轮问数入口，增加多轮功能开关。

### 阶段 2：模型 API 编排

1. 定义 `OperationPlan` JSON Schema。
2. 实现 OpenCloud/兼容模型 Chat API 客户端，禁用 tool calling。
3. 实现 Context Builder 和候选 Topic/Artifact 检索。
4. 实现 PlanValidator。

### 阶段 3：执行器

1. 实现 `REUSE`、`TRANSFORM_LOCAL`、`REGENERATE_REPORT`。
2. 将 `REQUERY` 适配到现有 Text-to-SQL、SQL 执行和报告能力。
3. 实现 `ASK_CLARIFICATION` 和失败降级。

### 阶段 4：N 轮稳定性

1. 增加 Topic 滚动摘要。
2. 限制 Prompt token 预算并实现裁剪策略。
3. 增加跨 Topic 比较、Artifact 版本链和过期处理。
4. 增加审计、回放、离线评测和可观测性。

## 11. 验收标准

### 11.1 正常问数不回归

1. 多轮功能关闭或未传 `sessionId` 时，现有单轮问数链路行为不变。
2. 多轮 `REQUERY` 场景仍使用现有 Text-to-SQL、SQL 执行和报告能力。
3. 远程模型 API 不可用、计划不合法时，系统能够降级为正常单轮问数，不阻断用户查询。

### 11.2 多轮与 Topic

1. 同一 `sessionId` 连续 50 轮可正常工作，普通追问每轮使用新的 `threadId`。
2. 同一会话内交替询问销售、库存、交付后，“回到库存问题”能引用库存 Topic，而非默认最近一轮。
3. 指代存在多个候选对象时，返回澄清问题，不随机选择。
4. 模型输入不包含完整历史消息或完整结果 JSON，且满足配置的 token 预算。

### 11.3 数据执行

1. “华南呢”继承上一轮指标和时间，仅改变地区，并重新查询。
2. “查看明细”继承原有过滤、权限和数据范围，切换粒度后重新查询。
3. 修改指标、维度、时间范围、数据范围时，不能错误走结果复用路径。
4. `resultRef` 过期或权限版本变化时，不能复用旧完整结果。

### 11.4 报告与图表

1. “精简报告结论”生成新的 Report Artifact 版本，不重新查库。
2. “柱状图改折线图”只有通过结果可渲染性校验时才不查库。
3. 当前结果不具备趋势字段或有效点不足时，系统不能硬画折线图；必须重查或澄清。
4. 新版报告/图表可追溯到来源结果和父版本。

### 11.5 安全与审计

1. 远程模型不能收到数据库凭据、完整结果和未脱敏敏感数据。
2. 模型引用其他会话或无权限 Artifact 时，本地必须拒绝。
3. 每轮可以追溯：用户消息、模型计划、校验结果、实际执行、引用 Artifact、生成 Artifact。

