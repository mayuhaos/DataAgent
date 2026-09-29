# Agent 连接 QUALITY_DB 的使用说明

本文说明 Agent 如何连接并查询 MySQL 数据库 `data2sql_zhijian`。连接时使用环境变量，禁止将密码硬编码到源码、提示词、日志或提交记录中。

## 1. 配置连接串

在本地 `.env` 文件或部署环境的密钥管理服务中配置：

```dotenv
QUALITY_DB_URL=mysql+pymysql://root:Hangju%402025%40root@mxdemo1.qunl.com:3307/data2sql_zhijian
```

连接串格式为：

```text
mysql+pymysql://<用户名>:<URL 编码后的密码>@<主机>:<端口>/<数据库名>
```

其中密码中的 `@` 必须 URL 编码为 `%40`。不要在代码中对该值再次 URL 编码。

将 `.env` 加入 `.gitignore`，并在生产环境通过平台的 Secret/环境变量配置该值。

## 2. 安装依赖

```bash
pip install sqlalchemy pymysql python-dotenv
```

## 3. Python 连接与查询

```python
import os

from dotenv import load_dotenv
from sqlalchemy import create_engine, text

load_dotenv()

database_url = os.environ["QUALITY_DB_URL"]
engine = create_engine(
    database_url,
    pool_pre_ping=True,
    pool_recycle=1800,
    connect_args={"connect_timeout": 10},
)

with engine.connect() as connection:
    result = connection.execute(text("SHOW TABLES"))
    tables = [row[0] for row in result]

print(tables)
```

参数化查询示例：

```python
from sqlalchemy import text

statement = text("SELECT * FROM `quality_record` WHERE `id` = :record_id LIMIT 1")

with engine.connect() as connection:
    row = connection.execute(statement, {"record_id": 123}).mappings().first()
```

表名和字段名须以实际库表结构为准。先用 `SHOW TABLES`、`DESCRIBE <table_name>` 或 `information_schema` 确认元数据，再生成业务 SQL。

## 4. Agent 执行约束

- 默认只执行只读 SQL：`SELECT`、`SHOW`、`DESCRIBE`、`EXPLAIN`。
- 查询必须带 `LIMIT`，除非任务明确要求汇总结果；大表先使用聚合或分页。
- 所有外部输入一律使用绑定参数，禁止字符串拼接，避免 SQL 注入。
- 禁止执行 `DROP`、`TRUNCATE`、`DELETE`、`UPDATE`、`INSERT`、`ALTER`、`CREATE` 等写操作，除非用户明确授权且已确认影响范围。
- 连接失败时只报告主机、端口和异常类型；不得在异常、日志或回复中输出完整 `QUALITY_DB_URL`、密码或其他密钥。
- 查询结果包含个人信息、质量敏感信息或其他受限数据时，按最小必要原则返回并做好脱敏。

## 5. 连通性检查

可使用以下最小查询确认连接正常：

```python
from sqlalchemy import text

with engine.connect() as connection:
    server_version = connection.execute(text("SELECT VERSION()")).scalar_one()

print(server_version)
```

连接成功后，Agent 应记录查询目的、执行时间和 SQL 模板（不含参数值中的敏感信息），以便审计和排查。
