# Conversation execution result API

This API reads persisted conversation data. It does not execute SQL again.

## Request

```http
GET /api/sessions/{sessionId}/execution-result
```

## Successful response

```json
{
  "sessionId": "3cbfe35c-4512-4a6e-a4f8-f0ddcaf8ab0d",
  "sql": [
    "SELECT product_name, SUM(amount) AS total_amount FROM sales GROUP BY product_name"
  ],
  "resultMd": "# Sales summary\n\n| Product | Amount |\n| --- | ---: |\n| A | 100 |"
}
```

- `sql` contains every SQL statement emitted by `SqlExecuteNode`, in execution
  order. SQL that was generated but never sent to the execution node is excluded.
- `resultMd` is the latest Markdown report persisted in the session timeline. For
  older sessions without a report node, the latest assistant `markdown-report` or
  `text` message is returned as a compatibility fallback.
- A session with no persisted execution data returns an empty `sql` array and a
  nullable `resultMd`.
- An unknown `sessionId` returns HTTP `404`.

## Example

```bash
curl --request GET \
  'http://localhost:8065/api/sessions/3cbfe35c-4512-4a6e-a4f8-f0ddcaf8ab0d/execution-result'
```
