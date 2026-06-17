"""Merge slots across multi-turn conversation."""

from __future__ import annotations

from admission_compiler.ir import Filters, Slots


def merge_slots(prior: Slots | None, current: Slots) -> Slots:
    if prior is None:
        return current

    provinces = _merge_list(prior.provinces, current.provinces)
    return Slots(
        score=current.score if current.score is not None else prior.score,
        provinces=provinces,
        subject_group=current.subject_group or prior.subject_group,
        year=current.year if current.year is not None else prior.year,
        admission_type=current.admission_type or prior.admission_type,
    )


def merge_filters(prior: Filters | None, current: Filters) -> Filters:
    if prior is None:
        return current
    return Filters(
        exclude_school_name_contains=_merge_list(
            prior.exclude_school_name_contains, current.exclude_school_name_contains
        ),
        exclude_major_keywords=_merge_list(
            prior.exclude_major_keywords, current.exclude_major_keywords
        ),
        include_major_keywords=_merge_list(
            prior.include_major_keywords, current.include_major_keywords
        ),
        include_schools=_merge_list(prior.include_schools, current.include_schools),
    )


def _merge_list(prior: list[str], current: list[str]) -> list[str]:
    if not prior:
        return list(current)
    if not current:
        return list(prior)
    return list(dict.fromkeys([*prior, *current]))
