#!/usr/bin/env python3
"""Run constraint_compile.jsonl golden cases against the rule+ontology compiler."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from admission_compiler.compiler import AdmissionQueryCompiler
from admission_compiler.ir import CompileRequest, Slots, Task


def load_cases(path: Path) -> list[dict]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            cases.append(json.loads(line))
    return cases


def check_case(case: dict, compiler: AdmissionQueryCompiler) -> list[str]:
    errors: list[str] = []
    expect = case.get("expect") or {}
    prior_slots = None
    if case.get("prior_slots"):
        prior_slots = Slots.model_validate(case["prior_slots"])

    result = compiler.compile(
        CompileRequest(
            message=case["input"],
            prior_slots=prior_slots,
            prior_user_messages=case.get("prior_user_messages") or [],
            use_llm=False,
        )
    )
    query = result.query

    if "task" in expect:
        expected_task = Task(expect["task"])
        if query.task != expected_task:
            errors.append(f"task: expected {expected_task.value}, got {query.task.value}")

    if "score" in expect and query.slots.score != expect["score"]:
        errors.append(f"score: expected {expect['score']}, got {query.slots.score}")

    if "subject_group" in expect and query.slots.subject_group != expect["subject_group"]:
        errors.append(
            f"subject_group: expected {expect['subject_group']}, got {query.slots.subject_group}"
        )

    if "provinces" in expect:
        for province in expect["provinces"]:
            if province not in query.slots.provinces:
                errors.append(f"provinces: missing {province} in {query.slots.provinces}")

    if "exclude_school" in expect:
        for token in expect["exclude_school"]:
            if token not in query.filters.exclude_school_name_contains:
                errors.append(
                    f"exclude_school: missing {token} in {query.filters.exclude_school_name_contains}"
                )

    if "exclude_major" in expect:
        for token in expect["exclude_major"]:
            if not any(token in m for m in query.filters.exclude_major_keywords):
                errors.append(
                    f"exclude_major: missing {token} in {query.filters.exclude_major_keywords}"
                )

    if "preferences" in expect:
        dims = {p.dimension.value for p in query.preferences}
        for dim in expect["preferences"]:
            if dim not in dims:
                errors.append(f"preferences: missing {dim} in {sorted(dims)}")

    if "needs_clarification" in expect:
        for field in expect["needs_clarification"]:
            if field not in query.needs_clarification:
                errors.append(
                    f"needs_clarification: missing {field} in {query.needs_clarification}"
                )
        for field in query.needs_clarification:
            if field not in expect["needs_clarification"]:
                errors.append(
                    f"needs_clarification: unexpected {field} in {query.needs_clarification}"
                )

    return errors


def main() -> int:
    cases_path = ROOT / "eval" / "cases" / "constraint_compile.jsonl"
    compiler = AdmissionQueryCompiler()
    cases = load_cases(cases_path)

    failed = 0
    for case in cases:
        errors = check_case(case, compiler)
        if errors:
            failed += 1
            print(f"FAIL {case['id']}")
            for err in errors:
                print(f"  - {err}")
        else:
            print(f"OK   {case['id']}")

    print(f"\n{len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
