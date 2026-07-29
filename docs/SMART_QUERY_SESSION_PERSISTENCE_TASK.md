# 智能问数 HTTP 链路会话持久化任务

## 任务目标

总控在本地完成意图识别与问数门禁，门禁通过后路由到 `delivery-smart-query`。

需要保证通过 HTTP 封装发起的每次问数均创建全新的独立会话，并且在问数结束后刷新 Web 页面仍能看到：

1. 本次新建的会话；
2. 用户的原始问题；
3. assistant 的最终答案；
4. 可选的分析过程 timeline；
5. 失败时可理解的错误消息。

请先结合实际 HTTP 封装代码补充本文档中的“待补充信息”，确认方案后直接完成实现和测试。

## 已确认的根因

当前外部调用链为：

```text
POST /api/agent/{agentId}/sessions
GET  /api/stream/search?...
GET  /api/sessions/{sessionId}/messages   # 仅在 SSE 没有答案时调用
```

该链路没有持久化消息。

- `POST /api/agent/{agentId}/sessions` 只写入 `chat_session`。
- `GET /api/stream/search` 只执行图并返回 SSE，不写入 `chat_message`。
- `GET /api/sessions/{sessionId}/messages` 只读取已持久化消息，不能从 SSE 或图运行上下文重建消息。
- Web 前端之所以能在刷新后恢复对话，是因为前端另外调用了 `POST /api/sessions/{sessionId}/messages` 保存用户消息、timeline 和最终答案。

因此，“仅在 SSE 无答案时读取 messages 兜底”在当前后端实现下通常只会得到空结果。

相关实现位置：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/ChatController.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`
- `data-agent-frontend-nuxt/app/stores/chat.ts`
- `data-agent-user-frontend-nuxt/app/stores/chat.ts`

## ID 语义

必须严格区分以下两个 ID：

| ID | 含义 | 用途 |
| --- | --- | --- |
| `sessionId` | 数据库会话 ID | 调用 `/api/sessions/{sessionId}/messages`，关联 `chat_session` 与 `chat_message` |
| `threadId` | 图运行 ID | SSE 重连、澄清、人工反馈等图运行恢复 |

新建独立会话的第一次搜索可以不传 `threadId`，后端会生成新的 `threadId`。不得因为二者都是 UUID 就默认它们等价。

## 必须实现的调用顺序

### 1. 创建会话

```http
POST /api/agent/{agentId}/sessions
Content-Type: application/json

{
  "title": "新会话"
}
```

保存响应中的 `id`，作为后续所有消息请求的 `sessionId`。

### 2. 在 SSE 前保存用户原始问题

```http
POST /api/sessions/{sessionId}/messages
Content-Type: application/json

{
  "role": "user",
  "content": "用户的原始问题",
  "messageType": "text",
  "titleNeeded": true
}
```

保存成功后才能发起搜索。此操作还会更新 session 活跃时间，并触发首次会话标题生成。

### 3. 发起并消费 SSE

```http
GET /api/stream/search?agentId={agentId}&query={urlEncodedQuestion}
Accept: text/event-stream
```

至少传递：

- `agentId`
- 未改写的用户原始问题 `query`
- 封装层已有且确实需要的其他图参数

消费规则：

1. 逐条解析 SSE 的 `data` JSON；
2. 保存响应里的 `threadId`，仅用于本次图运行的重连或恢复；
3. 按顺序拼接 `textType == "FINAL_ANSWER"` 的 `text`；
4. 如现有版本使用 `eventType == "FINAL_ANSWER"` 返回最终答案，也应兼容该形式；
5. `event: complete` 只表示流结束，不能把 complete 事件自身当作答案；
6. `event: error` 或响应中 `error == true` 视为执行失败；
7. 心跳注释不是业务数据，不参与答案判断。

### 4. SSE 成功后保存 assistant 最终答案

当最终答案 `trim()` 后非空时：

```http
POST /api/sessions/{sessionId}/messages
Content-Type: application/json

{
  "role": "assistant",
  "content": "拼接后的完整最终答案",
  "messageType": "text",
  "titleNeeded": false
}
```

只有该请求成功，才可以向总控返回“问数成功”。不得先返回结果、再进行不受保障的异步保存。

### 5. 可选保存 timeline

如果产品要求刷新后展示分析阶段，则参照前端 `chat.ts` 的数据结构，将收集到的节点响应分组后保存：

```http
POST /api/sessions/{sessionId}/messages
Content-Type: application/json

{
  "role": "assistant",
  "content": "序列化后的 timeline JSON",
  "messageType": "timeline",
  "titleNeeded": false
}
```

如果只要求刷新后看到问答正文，timeline 可以不保存。

### 6. 失败处理

如果 SSE 结束后没有有效最终答案：

1. 可以调用 `GET /api/sessions/{sessionId}/messages`，查找最新的非空 assistant 消息；
2. 该读取只能作为兼容其他持久化写入方的兜底，不能代替本任务中的显式保存；
3. 如果仍无 assistant 答案，保存一条 `messageType: "error"` 的 assistant 消息；
4. 向总控返回明确失败，不得把空字符串当作成功答案。

示例错误消息：

```json
{
  "role": "assistant",
  "content": "问数执行结束，但未返回有效答案",
  "messageType": "error",
  "titleNeeded": false
}
```

## 推荐伪代码

```text
session = createNewSession(agentId)
sessionId = session.id

saveMessage(sessionId, user, rawQuestion, text, titleNeeded=true)

sseResult = consumeSearchStream(agentId, rawQuestion)

if sseResult.finalAnswer is not blank:
    if timeline persistence is enabled:
        saveMessage(sessionId, assistant, timelineJson, timeline)
    saveMessage(sessionId, assistant, sseResult.finalAnswer, text)
    return success(sessionId, sseResult.threadId, sseResult.finalAnswer)

messages = getSessionMessages(sessionId)
fallback = latest non-empty assistant message
if fallback exists:
    return success(sessionId, sseResult.threadId, fallback.content)

saveMessage(sessionId, assistant, failureReason, error)
return failure(sessionId, sseResult.threadId, failureReason)
```

## 幂等与重复写入

实现前必须确认调用入口：

- 如果持久化逻辑加在 `delivery-smart-query` 的 HTTP 封装层，只允许该封装层保存一次。
- 如果选择修改 `/api/stream/search` 后端自动保存，必须新增明确的 `sessionId`/持久化开关，并同步移除或禁用两个 Nuxt 前端现有的保存逻辑，否则会产生重复消息。
- 本任务推荐优先修改 HTTP 封装层，不改变现有 `/api/stream/search` 的行为，影响面最小。
- 对网络重试要有去重策略。若当前 API 没有幂等键，至少保证同一次调用不会在成功响应后再次执行保存；如封装层存在自动重试，应补充 request/message key 后再实现可靠去重。

## 待补充信息

开始编码前，请在本节补充实际情况：

- `delivery-smart-query` HTTP 封装代码所在仓库、模块和文件：
- 创建 session 的方法名：
- SSE 消费方法名：
- 当前有效答案判定逻辑：
- 当前返回给总控的数据结构：
- HTTP 客户端是否自动重试：
- 是否需要持久化 timeline：
- 是否存在同一 session 的澄清或多轮问数：
- 计划修改的文件：
- 计划新增或修改的测试：

补充完成后，检查实际实现是否与本文假设冲突。如冲突，以代码事实为准，但必须记录差异及最终选择。

## 验收标准

至少覆盖以下场景：

1. 门禁不通过：不创建 session，不发送 SSE，不保存消息。
2. 门禁通过且 SSE 成功：创建一个全新 session，依次存在 user 与 assistant text 消息。
3. 连续调用两次：产生两个不同 `sessionId`，不复用历史 session。
4. SSE 分块返回：最终保存内容是所有 `FINAL_ANSWER` 分块按顺序拼接后的完整文本。
5. SSE 只有 complete、没有答案：不得返回成功；会话中保存 error 消息。
6. SSE 返回 error 或连接异常：会话保留用户问题，并保存或返回明确错误。
7. assistant 消息保存失败：整体调用不得报告成功。
8. 刷新 Web 页面并进入相同 `agentId`：能在会话列表中找到新 session，打开后能看到原始问题和最终答案。
9. 不产生重复 user、assistant 或 timeline 消息。

接口级校验：

```http
GET /api/agent/{agentId}/sessions
GET /api/sessions/{sessionId}/messages
```

数据库校验：

```sql
SELECT id, agent_id, title, status, create_time, update_time
FROM chat_session
WHERE id = '<sessionId>';

SELECT id, session_id, role, message_type, content, create_time
FROM chat_message
WHERE session_id = '<sessionId>'
ORDER BY create_time ASC, id ASC;
```

成功场景下，第二条 SQL 至少应按顺序返回：

```text
user      text      原始问题
assistant text      最终答案
```

## 非目标与注意事项

- 不修改本地问数门禁规则和意图识别逻辑。
- 不复用旧 session。
- 不把 `threadId` 当作 `sessionId`。
- 不依赖内存中的图上下文实现页面刷新恢复。
- 不改动与本任务无关的配置文件或用户已有修改。
- 不仅修改文档；补充实际信息后应完成代码、测试和端到端验证。
