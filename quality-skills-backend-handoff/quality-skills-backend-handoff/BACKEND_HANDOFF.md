# Quality Skills Backend Handoff

This package contains two completed, local OpenCode Skills for the Jiangyin quality-query workflow:

| Skill | Replaces inside original flow | Agent output | Application-owned execution |
| --- | --- | --- | --- |
| `quality-python` | `PythonGenerateNode` internal code-generation call | `AnalysisSpec` | Fixed recipe library / stable Python launcher |
| `quality-report` | `ReportGeneratorNode` internal report-generation call | `ReportDraft` + approved `ChartSpec` | Markdown table and ECharts Option renderer |

The outer DataAgent workflow, its SQL nodes, Python executor, Python analysis node, SSE protocol, and frontend are not replaced by this package.

## Delivered Files

```text
quality-python.skill                 Installable Python Skill
quality-report.skill                 Installable Report Skill
BACKEND_HANDOFF.md                   This document
runtime/
  .opencode/agents/                 Generated no-tool OpenCode Agents
  .opencode/opencode.json           Project Skill discovery configuration
  quality_analysis/                 Fixed Python recipe library
  quality_report/                   Fixed report/chart library
  scripts/                          Sync checks and POC runners
  requirements.txt                  Python runtime dependencies
  fixtures/                         Canonical POC prompts and rule examples
  tests/                            Contract tests
```

The handoff bundle intentionally excludes `.venv`, `node_modules`, test output, database credentials, and the raw DataAgent source clone.

## Design Boundary

The OpenCode Agents are **no-tool by design**. They cannot read files, run shell commands, query a database, execute Python, or write ECharts Option JSON.

| Responsibility | Owner |
| --- | --- |
| User intent, recipe/report phrasing | OpenCode Agent |
| SQL and database access | Existing DataAgent SQL path |
| Numeric calculations | Fixed Python recipe library |
| Report facts, table values, ECharts Option, blocked report | Application / fixed renderer |
| Markdown streaming and frontend ECharts display | Existing DataAgent nodes and frontend |

This prevents a reporting Agent from recalculating data or a Python Agent from writing arbitrary code.

## Server Prerequisites

1. Install the OpenCode CLI on the backend host and configure an approved provider/model.
2. Install Python 3.11+ and the dependencies in `requirements.txt` for the fixed capability worker.
3. Use a dedicated database account with `SELECT` and `SHOW VIEW` only. Do not bind a root account to an Agent datasource.
4. Place the runtime files in a backend-owned directory, for example `/opt/dataagent-quality-skills/`.
5. Do not expose provider keys or database credentials in prompts, Skill files, logs, or child-process command arguments.

## Install OpenCode Assets

The `.skill` files are source packages. The generated Agent definitions are also required at runtime because the Agents use a compiled Skill projection to avoid the multi-turn dynamic Skill-loading delay.

```sh
mkdir -p /opt/dataagent-quality-skills/.opencode/skills
unzip quality-python.skill -d /opt/dataagent-quality-skills/.opencode/skills
unzip quality-report.skill -d /opt/dataagent-quality-skills/.opencode/skills

cp -R runtime/.opencode/agents /opt/dataagent-quality-skills/.opencode/
cp runtime/.opencode/opencode.json /opt/dataagent-quality-skills/.opencode/
cp -R runtime/quality_analysis runtime/quality_report runtime/scripts /opt/dataagent-quality-skills/
cp runtime/requirements.txt /opt/dataagent-quality-skills/
```

After any edit to Skill reference files, regenerate and verify the matching Agent prompt:

```sh
cd /opt/dataagent-quality-skills
python scripts/sync_planner_agent.py
python scripts/sync_report_agent.py
python scripts/sync_planner_agent.py --check
python scripts/sync_report_agent.py --check
```

Do not change an Agent into a dynamic `load_skill` workflow. The compiled projection is intentional: it removes repeated model/tool rounds while retaining the Skill files as the maintained source of truth.

## Python Node Integration

### Input from existing DataAgent nodes

Keep existing intent, schema, planning, SQL generation, and SQL execution nodes. At the Python boundary, the adapter must build a bounded canonical request from already validated state:

```text
user question
canonical query / approved business scope
available SQL-result row fields
confirmed rules or thresholds
```

Invoke the local Agent without a shell, send that request through stdin, and parse JSONL output:

```sh
opencode run --format json \
  --agent quality-python-planner \
  --title quality-python-plan
```

The only accepted text output is an `AnalysisSpec` JSON object. Validate it before any calculation.

```json
{
  "contract_version": "1.0",
  "mode": "recipe",
  "recipe_id": "tabular_aggregate_v1",
  "recipe_version": "1.0",
  "parameters": {}
}
```

### Execution

- `recipe` mode: pass SQL result rows plus the validated `AnalysisSpec` to the fixed `quality_analysis` library. For a direct replacement of the existing Python executor boundary, use `quality_analysis.launcher.render_launcher()` to render the stable stdin/stdout Python script.
- `legacy` mode: do not force an unsupported query into a recipe. Route to the original `PythonGenerateNode` implementation or return the existing typed unsupported response, according to the backend's fallback policy.
- Invalid JSON, unknown recipe, timeout, or invalid fields: do not execute. Use the original node fallback or surface a typed failure.

The POC's direct database reader is only a local verification harness. Production integration must consume the existing DataAgent `SqlExecuteNode` result/state; it must not let OpenCode generate SQL or establish a second uncontrolled database connection.

## Report Node Integration

### Build `ReportFacts`

After fixed Python execution and analysis, build a bounded report contract. Do not pass the full plan, raw SQL result dump, or raw measurement rows to the Report Agent.

```json
{
  "contract_version": "1.0",
  "status": "reportable",
  "case_id": "weekly_first_pass_rate",
  "question": "2026年6月按周一次合格率趋势",
  "scope": {"factory_id": 1, "start_date": "2026-06-01", "end_date_exclusive": "2026-07-01"},
  "source_tables": ["paint_product_shift_stats"],
  "metric_definition": "每周一次合格率 = SUM(first_pass_count) / SUM(check_total) * 100。",
  "rows": [{"week": "2026-W23", "first_pass_rate": 53.22}],
  "fact_ids": ["scope", "metric_definition", "result_rows", "trend"],
  "chart_requirement": {
    "chart_type": "line",
    "dataset_id": "analysis_rows",
    "x_field": "week",
    "y_field": "first_pass_rate",
    "title": "一次合格率周趋势"
  },
  "limitations": ["结论仅覆盖所列统计范围。"]
}
```

Invoke OpenCode:

```sh
opencode run --format json \
  --agent quality-report-writer \
  --title quality-report
```

Validate the returned `ReportDraft`. Each observation must reference known fact IDs. Its chart type, dataset ID, x field, and y field must exactly match the approved chart requirement.

### Render final response

Call `quality_report.render_report(facts, draft)`. It returns:

- Markdown with summary, fixed result table, scope, metric definition, source tables, and limitations.
- A valid ECharts Option built from actual rows, wrapped as a fenced `echarts` Markdown block.

The current frontend already parses these blocks and calls ECharts. Keep the existing stream marker and Markdown delivery protocol unchanged.

### Blocked Facts

For `status: "blocked"`, do **not** call `quality-report-writer`. Call the deterministic renderer directly. It produces a no-chart report explaining the conflict. Example: the current product total pass-rate case has 300 distinct source values, so it must not be averaged or charted.

## Allowed Chart Registry

V1 supports only `line`, `bar`, and `pie`. The validated POC mappings are:

| Case | Chart |
| --- | --- |
| Weekly first-pass-rate trend | `line`: `week` -> `first_pass_rate` |
| Defect Top3 | `bar`: `defect_name` -> `defect_total` |
| Three defect categories | `bar`: `defect_category` -> `defect_total` |
| Shift defect rate | `bar`: `shift_name` -> `defect_rate` |
| Dimension variation | `bar`: `measurement_point` -> `range_ratio_percent` |

New chart types require a registered transformer and tests. Do not accept an arbitrary model-written ECharts Option.

## Tested POC Results

| Scenario | Result | End-to-end POC time |
| --- | --- | ---:|
| Weekly first-pass trend | Markdown + line chart | 32.86s |
| Defect Top3 | Markdown + bar chart | 27.74s |
| Three defect categories | Markdown + bar chart | 23.50s |
| Shift defect rate | Markdown + bar chart | 32.08s |
| Source overall pass rate | Blocked report, no chart | 11.83s |
| Complex dimension variation | Markdown + bar chart | 39.32s |

Fixed database reads, Python calculations, and chart rendering were millisecond-scale. Most elapsed time was local OpenCode Agent planning/writing.

## Verification

```sh
cd /opt/dataagent-quality-skills
python -m unittest discover -s tests -v
python scripts/sync_planner_agent.py --check
python scripts/sync_report_agent.py --check
```

For local read-only POC validation, set `QUALITY_DB_URL` only through the host environment or a secret manager, then use the included `run_live_*_poc.py` commands. Do not put a connection string in a command stored in shell history, repository configuration, or handoff files.

## Deferred Production Work

- Add a DataAgent adapter at the two node boundaries described above.
- Configure a non-root, read-only DataAgent datasource.
- Add a server-side test-only pause after Python and after ReportDraft validation.
- Verify frontend visual rendering of streamed `echarts` blocks in the deployed environment.
- Define confirmed formula/aggregation rules for currently blocked metrics before enabling them.
