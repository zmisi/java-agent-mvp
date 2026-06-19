# Agent evaluation golden sets

## Case files

| File | Purpose |
|------|---------|
| `cases/intent_classify.jsonl` | Intent classification only (no DB/MCP/LLM) |
| `cases/workflow_deterministic.jsonl` | Workflow orchestration with mocked MCP/RAG |
| `cases/workflow_live.jsonl` | Full stack against local Postgres + MCP + DashScope |

## CI (default)

```bash
mvn test
```

Runs `IntentClassifyEvalTest` and `WorkflowDeterministicEvalTest` (included in default surefire). Live cases are excluded via `@Tag("eval-live")`.

## Live eval

Requires running stack (Postgres, admission-score MCP, optional DashScope for disabled synthesis path):

```bash
EVAL_LIVE=1 mvn test -Peval-live
# or
./eval/scripts/run-eval-live.sh
```

Report written to `eval/reports/latest.md` (gitignored).

## Case schema

Intent case:

```json
{"id":"...", "input":"...", "expectIntent":"SCORE|POLICY|REPORT|UNKNOWN"}
```

Workflow case:

```json
{
  "id": "report-hfut-620",
  "input": "安徽物理类620分，合工大计算机和软件工程政策",
  "expectIntent": "REPORT",
  "expectStatus": "SUCCEEDED",
  "expectNodesExecuted": ["compile_query", "score_tool", "policy_rag"],
  "requireScoreResult": true,
  "requirePolicySources": true,
  "maxLatencyMs": 60000
}
```
