"""Multi-turn intent inheritance aligned with Java MultiturnIntentSupport."""

from __future__ import annotations

import re

from admission_compiler.ir import Filters, Slots, Task
from admission_compiler.rules import RuleParseResult, parse_rules

MAJOR_QUERY_HINT = re.compile(
    r"专业|报考|报志愿|志愿|可报|能上|录取|哪些|什么专业|报什么|什么学校|哪些学校|院校"
)
POLICY_PATTERN = re.compile(
    r"招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则"
)
SHORT_FOLLOW_UP = re.compile(r"^(?:那|还有|换成|那么|同样)?\s*.+(?:呢|怎么样)[？?]?$")
GEOGRAPHY_SHORT = re.compile(r"^在.+$")
RANK_HINT = re.compile(r"排名|位次|名次|排多少|多少位|多少名|排第几")


def has_admission_refinement(
    *,
    filters: Filters,
    regions: list,
    preferences: list,
    unsupported_constraints: list,
) -> bool:
    if filters.exclude_school_name_contains or filters.exclude_major_keywords:
        return True
    if filters.include_major_keywords or filters.include_schools:
        return True
    if filters.include_major_discipline_groups or filters.include_discipline_categories:
        return True
    if regions or preferences or unsupported_constraints:
        return True
    return False


def _has_slot_value(rule: RuleParseResult) -> bool:
    return any(
        [
            rule.score is not None,
            bool(rule.provinces),
            rule.subject_group is not None,
            rule.year is not None,
            rule.admission_type is not None,
        ]
    )


def is_current_turn_admission_related(
    message: str,
    rule: RuleParseResult,
    *,
    filters: Filters,
    regions: list,
    preferences: list,
    unsupported_constraints: list,
) -> bool:
    normalized = (message or "").strip()
    if not normalized:
        return False
    if rule.task not in {Task.UNKNOWN, Task.POLICY_QA} and rule.task is not None:
        if rule.task == Task.POLICY_QA:
            return True
        if rule.task != Task.UNKNOWN:
            return True
    if RANK_HINT.search(normalized):
        return True
    if has_admission_refinement(
        filters=filters,
        regions=regions,
        preferences=preferences,
        unsupported_constraints=unsupported_constraints,
    ):
        return True
    if _has_slot_value(rule):
        return True
    if can_inherit_as_follow_up(message, rule):
        return True
    if POLICY_PATTERN.search(normalized):
        return True
    return bool(MAJOR_QUERY_HINT.search(normalized))


def can_inherit_as_follow_up(message: str, rule: RuleParseResult) -> bool:
    normalized = (message or "").strip()
    if not normalized:
        return False
    if POLICY_PATTERN.search(normalized):
        return False
    if RANK_HINT.search(normalized):
        return False
    if MAJOR_QUERY_HINT.search(normalized):
        return False
    if _has_slot_value(rule):
        return True
    if GEOGRAPHY_SHORT.fullmatch(normalized):
        return True
    return bool(SHORT_FOLLOW_UP.fullmatch(normalized))


def infer_prior_task(prior_user_messages: list[str], prior_slots: Slots | None = None) -> Task:
    if not prior_user_messages:
        return Task.UNKNOWN
    task = Task.UNKNOWN
    for message in prior_user_messages:
        if not message or not message.strip():
            continue
        parsed = parse_rules(message)
        if parsed.task == Task.POLICY_QA:
            task = Task.POLICY_QA
        elif parsed.task != Task.UNKNOWN:
            task = parsed.task
    if task != Task.UNKNOWN:
        return task
    if prior_slots and prior_slots.score is not None:
        for message in prior_user_messages:
            if message and RANK_HINT.search(message):
                return Task.SEARCH_RANK
        return Task.SEARCH_MAJORS
    return Task.UNKNOWN


def should_inherit_prior_context(
    message: str,
    rule: RuleParseResult,
    *,
    prior_user_messages: list[str],
    prior_slots: Slots | None,
    filters: Filters,
    regions: list,
    preferences: list,
    unsupported_constraints: list,
    geography_on_current_turn: bool,
) -> bool:
    if rule.task != Task.UNKNOWN:
        return True
    prior_task = infer_prior_task(prior_user_messages, prior_slots)
    if prior_task not in {Task.SEARCH_MAJORS, Task.SEARCH_RANK}:
        return False
    return (
        can_inherit_as_follow_up(message, rule)
        or geography_on_current_turn
        or has_admission_refinement(
            filters=filters,
            regions=regions,
            preferences=preferences,
            unsupported_constraints=unsupported_constraints,
        )
    )


def resolve_task_with_context(
    rule_task: Task,
    message: str,
    *,
    prior_user_messages: list[str],
    prior_slots: Slots | None,
    filters: Filters,
    regions: list,
    preferences: list,
    unsupported_constraints: list,
    geography_on_current_turn: bool,
) -> Task:
    if rule_task != Task.UNKNOWN:
        return rule_task
    prior_task = infer_prior_task(prior_user_messages, prior_slots)
    if prior_task != Task.UNKNOWN and should_inherit_prior_context(
        message,
        parse_rules(message),
        prior_user_messages=prior_user_messages,
        prior_slots=prior_slots,
        filters=filters,
        regions=regions,
        preferences=preferences,
        unsupported_constraints=unsupported_constraints,
        geography_on_current_turn=geography_on_current_turn,
    ):
        return prior_task
    if rule_task == Task.UNKNOWN and is_current_turn_admission_related(
        message,
        parse_rules(message),
        filters=filters,
        regions=regions,
        preferences=preferences,
        unsupported_constraints=unsupported_constraints,
    ):
        if RANK_HINT.search((message or "").strip()):
            return Task.SEARCH_RANK
        return Task.SEARCH_MAJORS
    return Task.UNKNOWN
