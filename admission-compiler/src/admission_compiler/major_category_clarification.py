"""Detect unresolved major-category filter intent for clarify prompts."""

from __future__ import annotations

import re

from admission_compiler.rules import parse_include_major_keywords

FIELD = "major_category"
OPTIONS = ["工科", "理科", "文科", "医学", "经管"]
FIELD_PROMPT = "专业大类（" + "/".join(OPTIONS) + "）"

_DIRECTIVE = re.compile(r"(?:只看|只要|仅|限定|筛选|限制)([^，。！？；\s]{1,16})")
_CATEGORY_SUFFIX = re.compile(r"([^，。！？；\s]{2,10})(?:专业大类|学科门类)")
_CLASS_SUFFIX = re.compile(r"(?:只看|只要|仅)?([^，。！？；\s]{2,8})类专业")


def expresses_category_filter_intent(message: str) -> bool:
    if "专业大类" in message or "学科门类" in message:
        return True
    if _DIRECTIVE.search(message):
        return True
    if _CATEGORY_SUFFIX.search(message):
        return True
    return _CLASS_SUFFIX.search(message) is not None


def needs_major_category_clarification(
    message: str,
    major_category_hits: list[str],
) -> bool:
    if not message or not message.strip():
        return False
    if major_category_hits:
        return False
    if parse_include_major_keywords(message):
        return False
    return expresses_category_filter_intent(message)
