#!/usr/bin/env python3
"""Run constraint_compile*.jsonl golden cases against the rule+ontology compiler."""

from __future__ import annotations

import argparse
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
    if "turns" in case:
        return []  # multi-turn scenario container; compile per-turn in a dedicated runner

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

    if "rank" in expect and query.slots.rank != expect["rank"]:
        errors.append(f"rank: expected {expect['rank']}, got {query.slots.rank}")

    if "subject_group" in expect and query.slots.subject_group != expect["subject_group"]:
        errors.append(
            f"subject_group: expected {expect['subject_group']}, got {query.slots.subject_group}"
        )

    if "provinces" in expect:
        if expect.get("provinces_exact"):
            if query.slots.provinces != expect["provinces"]:
                errors.append(
                    f"provinces: expected exact {expect['provinces']}, got {query.slots.provinces}"
                )
        else:
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

    if "include_major" in expect:
        for token in expect["include_major"]:
            if not any(token in m for m in query.filters.include_major_keywords):
                errors.append(
                    f"include_major: missing {token} in {query.filters.include_major_keywords}"
                )

    if "include_major_discipline_groups" in expect:
        for token in expect["include_major_discipline_groups"]:
            if token not in query.filters.include_major_discipline_groups:
                errors.append(
                    "include_major_discipline_groups: missing "
                    f"{token} in {query.filters.include_major_discipline_groups}"
                )

    if "include_discipline_categories" in expect:
        for token in expect["include_discipline_categories"]:
            if token not in query.filters.include_discipline_categories:
                errors.append(
                    "include_discipline_categories: missing "
                    f"{token} in {query.filters.include_discipline_categories}"
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

    if "unsupported_constraints" in expect:
        types = {c.constraint_type for c in query.unsupported_constraints}
        for constraint_type in expect["unsupported_constraints"]:
            if constraint_type not in types:
                errors.append(
                    f"unsupported_constraints: missing {constraint_type} in {sorted(types)}"
                )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Run constraint compile golden cases")
    parser.add_argument(
        "--cases",
        type=Path,
        default=ROOT / "eval" / "cases" / "constraint_compile.jsonl",
        help="Path to JSONL cases (single-step or multiturn file with per-line steps)",
    )
    parser.add_argument(
        "--include-target",
        action="store_true",
        help="Also run cases marked status=target (aspirational, may fail)",
    )
    args = parser.parse_args()

    compiler = AdmissionQueryCompiler()
    cases = load_cases(args.cases)

    runnable = [
        c
        for c in cases
        if "turns" not in c and "input" in c and (args.include_target or c.get("status") != "target")
    ]
    skipped = len(cases) - len(runnable)
    target_skipped = sum(1 for c in cases if c.get("status") == "target" and not args.include_target)

    failed = 0
    for case in runnable:
        errors = check_case(case, compiler)
        if errors:
            failed += 1
            print(f"FAIL {case['id']}")
            for err in errors:
                print(f"  - {err}")
        else:
            print(f"OK   {case['id']}")

    print(f"\n{len(runnable) - failed}/{len(runnable)} passed", end="")
    if skipped:
        parts = []
        if target_skipped:
            parts.append(f"{target_skipped} target")
        other = skipped - target_skipped
        if other:
            parts.append(f"{other} other")
        print(f" ({', '.join(parts)} skipped)")
    else:
        print()
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
