#!/usr/bin/env python3
"""CLI: compile a user message to AdmissionQuery IR JSON."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from admission_compiler import compile_message


def main() -> int:
    parser = argparse.ArgumentParser(description="Compile NL to AdmissionQuery IR")
    parser.add_argument("message", help="User message")
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON")
    parser.add_argument("--llm", action="store_true", help="Enable LLM enrichment")
    args = parser.parse_args()

    query = compile_message(args.message, use_llm=args.llm)
    payload = query.model_dump(mode="json")
    if args.pretty:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(payload, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
