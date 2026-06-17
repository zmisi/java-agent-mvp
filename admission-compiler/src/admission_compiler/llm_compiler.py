"""Optional LLM enrichment for NL -> IR (L3)."""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from admission_compiler.ir import AdmissionQuery, CompileRequest, PreferenceDimension, Task

logger = logging.getLogger(__name__)

_SYSTEM_PROMPT = """你是高考志愿报考查询编译器。将用户自然语言编译为 JSON，仅输出 JSON，不要解释。

字段说明：
- task: search_majors | search_rank | policy_qa | report | unknown
- slots: { score, provinces[], subject_group, year, admission_type }
- filters: { exclude_school_name_contains[], exclude_major_keywords[], include_major_keywords[], include_schools[] }
- preferences: [{ dimension: employment_outlook|salary|state_owned_employability, weight, raw_phrase }]
- regions: [{ phrase, provinces[] }]
- needs_clarification: 缺少必填信息时列出，如 score, subject_group, provinces
- confidence: 0-1

规则：
- 「长三角」「江浙沪」展开为江苏、浙江、上海
- 「不当老师」「不要师范」排除师范院校与师范类专业
- 「就业前景」「收入」「央国企」写入 preferences
- 查位次用 search_rank；查可报专业/大学用 search_majors
- 不要编造分数或省份；没有则留空并在 needs_clarification 标注
"""


def llm_enabled_by_default() -> bool:
    return os.getenv("COMPILER_USE_LLM", "").strip().lower() in {"1", "true", "yes"}


def should_use_llm(request: CompileRequest, rule_query: AdmissionQuery) -> bool:
    if request.use_llm is False:
        return False
    if request.use_llm is True:
        return True
    if not llm_enabled_by_default():
        return False
    if not os.getenv("DASHSCOPE_API_KEY") and not os.getenv("OPENAI_API_KEY"):
        return False
    if rule_query.task == Task.UNKNOWN:
        return True
    if rule_query.confidence < 0.75:
        return True
    return False


def enrich_with_llm(request: CompileRequest, rule_query: AdmissionQuery) -> AdmissionQuery | None:
    api_key = os.getenv("DASHSCOPE_API_KEY") or os.getenv("OPENAI_API_KEY")
    if not api_key:
        logger.warning("LLM enrichment skipped: no API key")
        return None

    try:
        from openai import OpenAI
    except ImportError:
        logger.warning("LLM enrichment skipped: openai package missing")
        return None

    base_url = os.getenv("COMPILER_LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    model = os.getenv("COMPILER_LLM_MODEL", "qwen-plus")

    client = OpenAI(api_key=api_key, base_url=base_url)

    context = ""
    if request.prior_user_messages:
        context = "历史用户消息：\n" + "\n".join(f"- {m}" for m in request.prior_user_messages[-5:]) + "\n\n"
    if request.prior_slots:
        context += f"已有槽位：{request.prior_slots.model_dump_json()}\n\n"
    context += f"规则层初步结果：{rule_query.model_dump_json()}\n\n"
    context += f"当前用户消息：{request.message.strip()}"

    try:
        completion = client.chat.completions.create(
            model=model,
            temperature=0,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": context},
            ],
        )
        raw = completion.choices[0].message.content or "{}"
        payload = json.loads(raw)
        return _payload_to_query(payload, request.message, rule_query)
    except Exception as ex:
        logger.warning("LLM enrichment failed: %s", ex)
        return None


def _payload_to_query(payload: dict[str, Any], message: str, fallback: AdmissionQuery) -> AdmissionQuery:
    try:
        merged = fallback.model_dump()
        for key in ("task", "slots", "filters", "preferences", "regions", "needs_clarification", "confidence"):
            if key in payload and payload[key] is not None:
                merged[key] = payload[key]
        merged["raw_message"] = message
        merged["parse_trace"] = {
            **fallback.parse_trace.model_dump(),
            "llm_used": True,
        }
        query = AdmissionQuery.model_validate(merged)
        for pref in query.preferences:
            if isinstance(pref.dimension, str):
                pref.dimension = PreferenceDimension(pref.dimension)
        if isinstance(query.task, str):
            query.task = Task(query.task)
        return query
    except Exception:
        return fallback
