# Agent evaluation golden sets

## Case files

| File | Purpose |
|------|---------|
| `cases/intent_classify.jsonl` | IR compile → `AdmissionQueryIr.toIntent()` (same path as Chat `AdmissionQueryAdvisor`) |
| `cases/constraint_compile_multiturn.jsonl` | Multi-turn IR compile golden set (`ConstraintCompileMultiturnEvalTest`; skips `status=target`) |
| `cases/workflow_deterministic.jsonl` | Workflow orchestration with mocked MCP/RAG |
| `cases/workflow_live.jsonl` | Full stack against local Postgres + MCP + DashScope |

Also: `admission-compiler/eval/cases/constraint_compile.jsonl` — single-turn / simple prior_slots cases for the Python compiler (`admission-compiler/eval/run_eval.py`).

## CI (default)

```bash
mvn test
```

Runs `IntentClassifyEvalTest`, `ConstraintCompileMultiturnEvalTest`, and `WorkflowDeterministicEvalTest` (included in default surefire). Live cases are excluded via `@Tag("eval-live")`.

## Live eval

Requires running stack (Postgres, admission-score MCP, optional DashScope for disabled synthesis path):

```bash
EVAL_LIVE=1 mvn test -Peval-live
# or
./eval/scripts/run-eval-live.sh
```

Report written to `eval/reports/latest.md` (gitignored).

## Case schema

Intent case (single-turn; intent from local IR compiler, `app.admission-compiler.enabled=false`):

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

Constraint compile (multi-turn) — single step within a scenario:

```json
{
  "id": "mt-clarify-then-score-turn2",
  "prior_user_messages": ["我要报考长三角的大学，不当老师"],
  "input": "620分，物理类",
  "expect": {
    "task": "search_majors",
    "score": 620,
    "subject_group": "物理类",
    "provinces": ["江苏", "浙江", "上海"],
    "exclude_school": ["师范"],
    "needs_clarification": []
  }
}
```

Multi-turn scenario (multiple `turns[]` in one line) — see `constraint_compile_multiturn.jsonl` and `docs/intent-execution-checklist.md`.

Python compiler eval:

```bash
cd admission-compiler && .venv/bin/python eval/run_eval.py
```
