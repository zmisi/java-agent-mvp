"""Rule-based slot and task extraction (L1), aligned with Java AdmissionInputParser."""

from __future__ import annotations

import re
from dataclasses import dataclass

from admission_compiler.ir import Task

MIN_SCORE = 200
MAX_SCORE = 750

PROVINCES = (
    "安徽|北京|上海|天津|重庆|江苏|浙江|山东|河南|河北|湖北|湖南|广东|四川|陕西|福建|江西|"
    "山西|辽宁|吉林|黑龙江|内蒙古|广西|云南|贵州|甘肃|海南|宁夏|青海|西藏|新疆"
)

SCORE_WITH_FEN = re.compile(r"(?<!\d)(\d{3,4})\s*分")
BARE_NUMBER = re.compile(r"(?<!\d)(\d{3,4})(?!\d)")
PROVINCE_PATTERN = re.compile(f"({PROVINCES})")
SUBJECT_PATTERN = re.compile(r"(物理类?|历史类?|物理组|历史组|物理|历史)")
YEAR_PATTERN = re.compile(r"(20\d{2})\s*年?")
ADMISSION_TYPE_PATTERN = re.compile(r"(普通批|国家专项|地方专项|提前批|本科提前批|专科批)")

POLICY_PATTERN = re.compile(
    r"招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则"
)
MAJOR_SEARCH_HINT = re.compile(
    r"专业|报什么|什么专业|哪些专业|可报|能上什么|报考|报志愿|志愿|什么学校|哪些学校|院校"
)
UNIVERSITY_HINT = re.compile(r"大学")
RANK_WITH_MING = re.compile(r"排名\s*(\d+)\s*名")
RANK_DEDI = re.compile(r"第\s*(\d+)\s*名")
RANK_WEICI = re.compile(r"位次\s*(\d+)")
RANK_WEI = re.compile(r"(\d+)\s*位(?!次)")
RANK_HINT = re.compile(r"排名|位次|名次|排多少|多少位|多少名|排第几")

MAJOR_KEYWORDS = (
    "计算机科学与技术",
    "计算机",
    "软件工程",
    "人工智能",
    "信息安全",
    "物联网工程",
)


@dataclass
class RuleParseResult:
    score: int | None = None
    rank: int | None = None
    provinces: list[str] | None = None
    subject_group: str | None = None
    year: int | None = None
    admission_type: str | None = None
    include_major_keywords: list[str] | None = None
    include_schools: list[str] | None = None
    task: Task = Task.UNKNOWN
    rules_applied: list[str] | None = None


def parse_rules(message: str) -> RuleParseResult:
    normalized = (message or "").strip()
    applied: list[str] = []
    if not normalized:
        return RuleParseResult(rules_applied=applied)

    score = _parse_score(normalized)
    if score is not None:
        applied.append("score")

    rank = _parse_rank(normalized)
    if rank is not None:
        applied.append("rank")

    provinces = _parse_provinces(normalized)
    if provinces:
        applied.append("provinces")

    subject_group = _parse_subject_group(normalized)
    if subject_group:
        applied.append("subject_group")

    year = _parse_year(normalized)
    if year is not None:
        applied.append("year")

    admission_type = _parse_admission_type(normalized)
    if admission_type:
        applied.append("admission_type")

    majors = _parse_major_keywords(normalized)
    if majors:
        applied.append("major_keywords")

    task = _detect_task(normalized, score, rank)
    applied.append(f"task:{task.value}")

    return RuleParseResult(
        score=score,
        rank=rank,
        provinces=provinces,
        subject_group=subject_group,
        year=year,
        admission_type=admission_type,
        include_major_keywords=majors,
        task=task,
        rules_applied=applied,
    )


def _parse_score(message: str) -> int | None:
    match = SCORE_WITH_FEN.search(message)
    if match:
        value = int(match.group(1))
        if _is_plausible_score(value):
            return value

    years = {int(m.group(1)) for m in YEAR_PATTERN.finditer(message)}
    last: int | None = None
    for match in BARE_NUMBER.finditer(message):
        value = int(match.group(1))
        if value in years or not _is_plausible_score(value):
            continue
        last = value
    return last


def _is_plausible_score(value: int) -> bool:
    return MIN_SCORE <= value <= MAX_SCORE


def _parse_provinces(message: str) -> list[str]:
    return list(dict.fromkeys(m.group(1) for m in PROVINCE_PATTERN.finditer(message)))


def _parse_subject_group(message: str) -> str | None:
    match = SUBJECT_PATTERN.search(message)
    if not match:
        return None
    value = match.group(1)
    if value.startswith("物理"):
        return "物理类"
    if value.startswith("历史"):
        return "历史类"
    return value


def _parse_year(message: str) -> int | None:
    match = YEAR_PATTERN.search(message)
    return int(match.group(1)) if match else None


def _parse_admission_type(message: str) -> str | None:
    match = ADMISSION_TYPE_PATTERN.search(message)
    return match.group(1) if match else None


def _parse_major_keywords(message: str) -> list[str]:
    matched: list[str] = []
    for keyword in sorted(MAJOR_KEYWORDS, key=len, reverse=True):
        if keyword in message and keyword not in matched:
            matched.append(keyword)
    if "计算机科学与技术" in matched and "计算机" in matched:
        matched.remove("计算机")
    return matched


def parse_include_major_keywords(message: str) -> list[str]:
    return _parse_major_keywords((message or "").strip())


def _parse_rank(message: str) -> int | None:
    for pattern in (RANK_WITH_MING, RANK_DEDI, RANK_WEICI, RANK_WEI):
        match = pattern.search(message)
        if match:
            return int(match.group(1))

    has_rank_hint = bool(RANK_HINT.search(message))
    match = RANK_MING.search(message)
    if match and has_rank_hint:
        return int(match.group(1))

    years = {int(m.group(1)) for m in YEAR_PATTERN.finditer(message)}
    last: int | None = None
    for match in BARE_NUMBER.finditer(message):
        value = int(match.group(1))
        if value in years or _is_plausible_score(value):
            continue
        last = value
    if last is not None and has_rank_hint:
        return last
    return None


def _detect_task(message: str, score: int | None, rank: int | None) -> Task:
    has_policy = bool(POLICY_PATTERN.search(message))
    has_major_search = bool(MAJOR_SEARCH_HINT.search(message))
    has_university_search = bool(UNIVERSITY_HINT.search(message)) and not has_policy
    has_rank = bool(RANK_HINT.search(message)) and not has_major_search

    if score is not None and has_policy:
        return Task.REPORT
    if has_rank:
        return Task.SEARCH_RANK
    if has_policy and score is None and not has_major_search and not has_university_search:
        return Task.POLICY_QA
    if has_major_search or has_university_search:
        return Task.SEARCH_MAJORS
    if score is not None or rank is not None:
        return Task.SEARCH_MAJORS
    return Task.UNKNOWN
