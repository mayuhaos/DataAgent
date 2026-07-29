# 数据库元数据查询接口使用说明

## 1. 接口用途

该接口向外部服务提供智能体已选择数据表的结构化元数据。调用方可以使用真实表名、真实字段名、中文注释、字段类型、表关系和脱敏样例理解数据库结构，并判断用户问题是否缺少查询条件。

该接口为只读查询接口：

- 只读取管理数据库中已经保存的元数据快照。
- 不实时访问业务数据库。
- 不调用 Embedding 服务或向量库。
- 不提供元数据刷新或重新生成功能。
- 不返回数据库连接信息、完整 DDL 或索引详情。

## 2. 请求说明

```http
GET /api/agent/{agentId}/datasources/metadata
```

### 路径参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `agentId` | `Long` | 是 | 智能体 ID |

请求体：无。

## 3. 调用示例

### cURL

```bash
curl \
  "http://localhost:8065/api/agent/1/datasources/metadata" \
  -H "Accept: application/json"
```

### JavaScript

```javascript
const agentId = 1;

const response = await fetch(
  `http://localhost:8065/api/agent/${agentId}/datasources/metadata`,
  {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
  },
);

const result = await response.json();

if (!response.ok || !result.success) {
  throw new Error(result.message || '获取数据库元数据失败');
}

if (!result.data.metadataReady) {
  console.log('当前元数据不完整，缺失表：', result.data.missingTables);
}

console.log(result.data.tables);
```

### Java 21 HttpClient

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

long agentId = 1L;
String url = "http://localhost:8065/api/agent/%d/datasources/metadata".formatted(agentId);

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(url))
    .header("Accept", "application/json")
    .GET()
    .build();

HttpResponse<String> response = HttpClient.newHttpClient()
    .send(request, HttpResponse.BodyHandlers.ofString());

if (response.statusCode() < 200 || response.statusCode() >= 300) {
    throw new IllegalStateException("获取数据库元数据失败: " + response.body());
}

System.out.println(response.body());
```

## 4. 成功响应示例

```json
{
  "success": true,
  "message": "获取数据库元数据成功",
  "data": {
    "agentId": 1,
    "datasourceId": 3,
    "metadataReady": true,
    "selectedTableCount": 2,
    "loadedTableCount": 2,
    "missingTables": [],
    "tables": [
      {
        "schemaName": "saa_data_agent",
        "tableName": "orders",
        "tableComment": "销售订单",
        "displayName": "销售订单",
        "primaryKeys": ["id"],
        "columns": [
          {
            "columnName": "id",
            "columnComment": "订单编号",
            "displayName": "订单编号",
            "dataType": "BIGINT",
            "primaryKey": true,
            "nullable": false,
            "samples": ["10001", "10002", "10003"]
          },
          {
            "columnName": "mobile_phone",
            "columnComment": "联系电话",
            "displayName": "联系电话",
            "dataType": "VARCHAR",
            "primaryKey": false,
            "nullable": true,
            "samples": ["138***5678", "139***5678"]
          }
        ]
      },
      {
        "schemaName": "saa_data_agent",
        "tableName": "users",
        "tableComment": "用户",
        "displayName": "用户",
        "primaryKeys": ["id"],
        "columns": [
          {
            "columnName": "id",
            "columnComment": "用户编号",
            "displayName": "用户编号",
            "dataType": "BIGINT",
            "primaryKey": true,
            "nullable": false,
            "samples": ["10", "11", "12"]
          }
        ]
      }
    ],
    "relations": [
      {
        "sourceTable": "orders",
        "sourceColumn": "user_id",
        "targetTable": "users",
        "targetColumn": "id",
        "relationType": "PHYSICAL",
        "cardinality": null,
        "description": null
      },
      {
        "sourceTable": "orders",
        "sourceColumn": "customer_id",
        "targetTable": "users",
        "targetColumn": "id",
        "relationType": "LOGICAL",
        "cardinality": "N:1",
        "description": "订单所属客户"
      }
    ]
  }
}
```

## 5. 响应字段

### 通用响应

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `success` | `Boolean` | 请求是否成功 |
| `message` | `String` | 响应说明或错误信息 |
| `data` | `Object` | 数据库元数据 |

### 元数据

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `agentId` | `Long` | 智能体 ID |
| `datasourceId` | `Integer` | 当前启用的数据源 ID |
| `metadataReady` | `Boolean` | 当前已选表是否都有可用快照 |
| `selectedTableCount` | `Integer` | 当前选择的表数量 |
| `loadedTableCount` | `Integer` | 快照中成功加载的表数量 |
| `missingTables` | `String[]` | 当前已选但快照中缺失的真实表名 |
| `tables` | `TableMetadata[]` | 表及字段元数据 |
| `relations` | `RelationMetadata[]` | 表间关系 |

### 表元数据 `TableMetadata`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schemaName` | `String` | 数据库 Schema 名称 |
| `tableName` | `String` | 数据库真实表名 |
| `tableComment` | `String` | 数据库表注释 |
| `displayName` | `String` | 优先使用表注释；注释为空时使用真实表名 |
| `primaryKeys` | `String[]` | 主键字段名 |
| `columns` | `ColumnMetadata[]` | 字段元数据 |

### 字段元数据 `ColumnMetadata`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `columnName` | `String` | 数据库真实字段名 |
| `columnComment` | `String` | 数据库字段注释或中文含义 |
| `displayName` | `String` | 优先使用字段注释；注释为空时使用真实字段名 |
| `dataType` | `String` | 数据库字段类型 |
| `primaryKey` | `Boolean` | 是否为主键 |
| `nullable` | `Boolean` | 是否允许为空 |
| `samples` | `String[]` | 脱敏样例，每列最多 3 个；密码、密钥和 Token 类字段为空数组 |

### 关系元数据 `RelationMetadata`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sourceTable` | `String` | 来源真实表名 |
| `sourceColumn` | `String` | 来源真实字段名 |
| `targetTable` | `String` | 目标真实表名 |
| `targetColumn` | `String` | 目标真实字段名 |
| `relationType` | `PHYSICAL \| LOGICAL` | 物理外键或逻辑关系 |
| `cardinality` | `String \| null` | 关系基数，例如 `1:1`、`1:N`、`N:1` |
| `description` | `String \| null` | 关系说明 |

## 6. 元数据未就绪

接口调用成功但尚无完整快照时，`metadataReady` 为 `false`：

```json
{
  "success": true,
  "message": "获取数据库元数据成功",
  "data": {
    "agentId": 1,
    "datasourceId": 3,
    "metadataReady": false,
    "selectedTableCount": 2,
    "loadedTableCount": 0,
    "missingTables": ["orders", "users"],
    "tables": [],
    "relations": []
  }
}
```

调用方应根据 `metadataReady` 和 `missingTables` 判断元数据是否可用。外部调用方不负责生成或刷新元数据。

## 7. 常见错误

- 智能体不存在或没有启用的数据源。
- 已保存快照无法解析。
- 服务端或管理数据库不可用。

调用方应同时检查 HTTP 状态码、`success` 和 `message`，不要只判断是否返回 JSON。
