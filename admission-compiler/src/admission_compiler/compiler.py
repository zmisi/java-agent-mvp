"""NL -> AdmissionQuery IR compiler (rules + ontology + optional LLM)."""

from __future__ import annotations

from pathlib import Path

from admission_compiler.ir import (
    AdmissionQuery,
    CompileRequest,
    CompileResponse,
    Filters,
    ParseTrace,
    Preference,
    RegionRef,
    Slots,
    Task,
)
from admission_compiler.llm_compiler import enrich_with_llm, should_use_llm
from admission_compiler.merge import merge_slots
from admission_compiler.ontology import (
    Ontology,
    apply_exclusions,
    load_ontology,
    match_preferences,
    match_regions,
)
from admission_compiler.rules import parse_rules


class AdmissionQueryCompiler:
    def __init__(self, ontology: Ontology | None = None) -> None:
        self._ontology = ontology or load_ontology()

    def compile(self, request: CompileRequest) -> CompileResponse:
        message = (request.message or "").strip()
        rule = parse_rules(message)

        regions = match_regions(message, self._ontology)
        exclusion_filters, exclusion_hits = apply_exclusions(message, self._ontology)
        preferences, preference_hits = match_preferences(message, self._ontology)

        region_provinces = _flatten_region_provinces(regions)
        explicit_provinces = rule.provinces or []
        provinces = list(dict.fromkeys([*explicit_provinces, *region_provinces]))

        slots = Slots(
            score=rule.score,
            provinces=provinces,
            subject_group=rule.subject_group,
            year=rule.year,
            admission_type=rule.admission_type,
        )
        slots = merge_slots(
            request.prior_slots,
            slots,
            geography_overridden=bool(regions or explicit_provinces),
        )

        filters = Filters(
            exclude_school_name_contains=list(exclusion_filters.exclude_school_name_contains),
            exclude_major_keywords=list(exclusion_filters.exclude_major_keywords),
            include_major_keywords=list(rule.include_major_keywords or []),
            include_schools=list(rule.include_schools or []),
        )

        ontology_hits = [*exclusion_hits, *preference_hits, *[r.phrase for r in regions]]
        trace = ParseTrace(
            rules_applied=list(rule.rules_applied or []),
            ontology_hits=ontology_hits,
            llm_used=False,
        )

        task = _resolve_task_with_context(rule.task, request, slots)
        needs = _compute_needs_clarification(task, slots)
        confidence = _compute_confidence(task, slots, regions, exclusion_hits, preference_hits)

        query = AdmissionQuery(
            task=task,
            slots=slots,
            filters=filters,
            preferences=preferences,
            regions=regions,
            needs_clarification=needs,
            confidence=confidence,
            raw_message=message,
            parse_trace=trace,
        )

        if should_use_llm(request, query):
            enriched = enrich_with_llm(request, query)
            if enriched is not None:
                query = enriched

        return CompileResponse(query=query)


def _flatten_region_provinces(regions: list[RegionRef]) -> list[str]:
    out: list[str] = []
    for region in regions:
        for province in region.provinces:
            if province not in out:
                out.append(province)
    return out


def _resolve_task_with_context(task: Task, request: CompileRequest, slots: Slots) -> Task:
    if task != Task.UNKNOWN:
        return task
    if request.prior_user_messages:
        prior_text = " ".join(request.prior_user_messages)
        prior_rule = parse_rules(prior_text)
        if prior_rule.task != Task.UNKNOWN:
            return prior_rule.task
    if slots.score is not None:
        return Task.SEARCH_MAJORS
    return Task.UNKNOWN


def _compute_needs_clarification(task: Task, slots: Slots) -> list[str]:
    needs: list[str] = []
    if task in {Task.SEARCH_MAJORS, Task.SEARCH_RANK, Task.REPORT}:
        if slots.score is None:
            needs.append("score")
        if task != Task.SEARCH_RANK and not slots.provinces:
            needs.append("provinces")
        if slots.subject_group is None:
            needs.append("subject_group")
    return needs


def _compute_confidence(
    task: Task,
    slots: Slots,
    regions: list[RegionRef],
    exclusion_hits: list[str],
    preference_hits: list[str],
) -> float:
    if task == Task.UNKNOWN:
        return 0.35
    score = 0.72
    if regions:
        score += 0.08
    if exclusion_hits:
        score += 0.06
    if preference_hits:
        score += 0.04
    if slots.score is not None:
        score += 0.05
    if slots.provinces:
        score += 0.05
    return min(score, 0.98)


def compile_message(
    message: str,
    *,
    prior_slots: Slots | None = None,
    prior_user_messages: list[str] | None = None,
    ontology_dir: Path | None = None,
    use_llm: bool | None = None,
) -> AdmissionQuery:
    ontology = load_ontology(ontology_dir) if ontology_dir else None
    compiler = AdmissionQueryCompiler(ontology=ontology)
    response = compiler.compile(
        CompileRequest(
            message=message,
            prior_slots=prior_slots,
            prior_user_messages=prior_user_messages or [],
            use_llm=use_llm,
        )
    )
    return response.query
