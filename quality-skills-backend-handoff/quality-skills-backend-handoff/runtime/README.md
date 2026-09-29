# Quality Python Skill POC

This POC validates recipe-based Python analysis. It does not modify or invoke DataAgent.

Backend developers should start with [BACKEND_HANDOFF.md](BACKEND_HANDOFF.md). It describes the two packaged Skills, OpenCode Agent deployment, node-boundary integration, contracts, fallback behavior, and validation.

## What it proves

1. An OpenCode Skill can select a typed AnalysisSpec instead of returning arbitrary Python.
2. A fixed capability library can execute approved quality-analysis recipes.
3. A generic launcher can consume JSON from standard input and emit one JSON result, matching the future executor boundary.

## Local checks

~~~sh
cd /Users/ke/Documents/opencode-projects/jiangyin_zhijian/06_prototypes/workflow_demos/python_skill_node_replacement
python3 -m unittest discover -s tests -v
python3 scripts/run_poc.py
python3 scripts/verify_skill_layout.py
~~~

The checks use only synthetic/de-identified rows. They do not call a model or start OpenCode.

## Read-only database POC

Create a local virtual environment and install the pinned dependencies:

~~~sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
~~~

Set QUALITY_DB_URL through the shell or secret manager. Use a dedicated account with only SELECT and SHOW VIEW rights on data2sql_zhijian. Root is rejected by the reader.

~~~sh
export QUALITY_DB_URL='mysql+pymysql://quality_poc_reader:<URL_ENCODED_PASSWORD>@<HOST>:<PORT>/data2sql_zhijian'
.venv/bin/python scripts/check_quality_db.py
~~~

After preflight passes, run one bounded dimension-variation case with explicit rules:

~~~sh
.venv/bin/python scripts/run_live_dimension_poc.py \
  --factory-id <FACTORY_ID> \
  --measurement-date <YYYY-MM-DD> \
  --part-name '<PART_NAME>' \
  --rules-file fixtures/dimension_variation_rules_poc.json \
  --limit 500
~~~

The reader permits only registered templates, metadata reads, and bound parameters. It does not accept Agent-generated SQL.

## Local OpenCode Agent

The POC calls the locally installed OpenCode CLI rather than the unrelated Vite page on port 5173. It uses the same documented pattern as the reference workbench: spawn `opencode run --format json`, provide the prompt through stdin, parse JSONL text events, validate the AnalysisSpec, and terminate on timeout.

Use the configured default local Provider by omitting --model:

~~~sh
.venv/bin/python scripts/run_opencode_spec.py \
  --request-file fixtures/opencode_dimension_request.txt
~~~

Pass --model only when deliberately choosing another configured Provider. Do not override the default chrouter route with openai/... unless that separate OpenAI OAuth credential has been refreshed.

Before a run, verify the fast Agent projection is current:

~~~sh
python3 scripts/sync_planner_agent.py --check
~~~

The Agent receives the Skill's contract and recipe catalog in its first prompt and is denied file/Skill/search tools. It therefore makes one model call to return the AnalysisSpec. The CLI also supplies a fixed session title so OpenCode does not make a separate title-generation model call.

The runtime does not use a browser, does not pass database credentials to OpenCode, and does not accept arbitrary Python or SQL from the Agent.

## Live agent-to-execution POC

This command runs one known dimension case through the Agent, the registered read-only query template, and the fixed Python recipe. It stops after Python execution; it does not call a report node.

~~~sh
.venv/bin/python scripts/run_live_agent_dimension_poc.py \
  --request-file fixtures/opencode_dimension_request.txt \
  --factory-id 1 \
  --measurement-date 2026-09-14 \
  --part-name '后背门外饰板总成'
~~~

`QUALITY_DB_URL` remains in the caller environment. The Agent receives neither that URL nor query results. Its output must select `dimension_variation_v1`; otherwise the command stops before connecting to the database.

## Live spray cases POC

The five approved June 2026 spray cases use fixed query templates, fixed Python aggregation, and a report-fact assessment. They stop before natural-language report generation.

~~~sh
.venv/bin/python scripts/run_live_spray_cases_poc.py \
  --factory-id 1 \
  --start-date 2026-06-01 \
  --end-date 2026-07-01
~~~

Use `--case` to run one case: `weekly_first_pass_rate`, `defect_top3_share`, `three_defect_categories`, `shift_defect_rate`, or `source_overall_pass_rate`.

The first four cases are count-based. `source_overall_pass_rate` is intentionally different: it treats the Excel-owned value as a consistency check and blocks reporting when the requested scope contains more than one value. It never averages that source field.

## Live report cases POC

This POC runs the existing Python Skill path first, then gives only its validated facts to the `quality-report` Skill. The deterministic renderer emits Markdown and a line/bar/pie ECharts configuration from actual result rows.

~~~sh
.venv/bin/python scripts/run_live_report_cases_poc.py \
  --factory-id 1 \
  --start-date 2026-06-01 \
  --end-date 2026-07-01
~~~

The four reportable spray cases must contain one `echarts` block. The `source_overall_pass_rate` case is expected to produce a blocked report with no chart when source-rate values conflict.

## Live dimension report POC

The complex dimension case projects only the verified high-variation-point summary into ReportFacts. It renders a fixed bar chart of point name by range-ratio percentage and preserves the Cpk/Ppk sample-gate limitation.

~~~sh
.venv/bin/python scripts/run_live_dimension_report_poc.py \
  --request-file fixtures/opencode_dimension_request.txt \
  --factory-id 1 \
  --measurement-date 2026-09-14 \
  --part-name '后背门外饰板总成'
~~~

## Boundary

The Skill chooses a recipe and parameter values. It does not execute SQL, write arbitrary Python, change quality data, or mark a DataAgent workflow complete.
