import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))

from admission_compiler.compiler import AdmissionQueryCompiler
from admission_compiler.ir import CompileRequest, Slots, Task


def test_yangtze_no_teacher():
    compiler = AdmissionQueryCompiler()
    result = compiler.compile(
        CompileRequest(message="我要报考长三角的大学，不当老师", use_llm=False)
    )
    q = result.query
    assert q.task == Task.SEARCH_MAJORS
    assert set(q.slots.provinces) == {"江苏", "浙江", "上海"}
    assert "师范" in q.filters.exclude_school_name_contains
    assert "score" in q.needs_clarification
    assert any(r.phrase == "长三角" for r in q.regions)


def test_yangtze_complex_unsupported_constraints():
    compiler = AdmissionQueryCompiler()
    message = "我要报考长三角的大学，不当老师，就业前景比较好，收入比较高，能进央国企"
    result = compiler.compile(CompileRequest(message=message, use_llm=False))
    q = result.query
    assert q.task == Task.SEARCH_MAJORS
    types = {c.constraint_type for c in q.unsupported_constraints}
    assert types == {"employment_data", "salary_data", "state_owned_employability"}
    assert q.preferences == []


def test_score_query_complete():
    compiler = AdmissionQueryCompiler()
    result = compiler.compile(
        CompileRequest(message="安徽物理类620分能上什么专业", use_llm=False)
    )
    q = result.query
    assert q.slots.score == 620
    assert q.slots.subject_group == "物理类"
    assert q.needs_clarification == []


def test_followup_merge_provinces():
    compiler = AdmissionQueryCompiler()
    result = compiler.compile(
        CompileRequest(
            message="那浙江呢？",
            prior_user_messages=["安徽物理类620分能上什么专业"],
            prior_slots=Slots(score=620, provinces=["安徽"], subject_group="物理类"),
            use_llm=False,
        )
    )
    q = result.query
    assert q.slots.score == 620
    assert q.slots.provinces == ["浙江"]


def test_followup_northeast_rank_replaces_prior_provinces():
    compiler = AdmissionQueryCompiler()
    result = compiler.compile(
        CompileRequest(
            message="在东北的排名",
            prior_user_messages=["600分在长三角排名"],
            prior_slots=Slots(score=600, provinces=["江苏", "浙江", "上海"]),
            use_llm=False,
        )
    )
    q = result.query
    assert q.task == Task.SEARCH_RANK
    assert q.slots.score == 600
    assert set(q.slots.provinces) == {"辽宁", "吉林", "黑龙江"}
    assert "江苏" not in q.slots.provinces


def test_followup_northeast_after_province_chain():
    compiler = AdmissionQueryCompiler()
    result = compiler.compile(
        CompileRequest(
            message="在东北",
            prior_user_messages=["在安徽", "600分在江苏的排名"],
            use_llm=False,
        )
    )
    q = result.query
    assert q.task == Task.SEARCH_RANK
    assert q.slots.score == 600
    assert set(q.slots.provinces) == {"辽宁", "吉林", "黑龙江"}
    assert q.needs_clarification == []


def test_greeting_after_major_search_does_not_inherit():
    compiler = AdmissionQueryCompiler()
    result = compiler.compile(
        CompileRequest(
            message="你好",
            prior_user_messages=["物理类", "在安徽可以报考什么学校专业", "600分在安徽排名"],
            prior_slots=Slots(score=600, provinces=["安徽"], subject_group="物理类"),
            use_llm=False,
        )
    )
    q = result.query
    assert q.task == Task.UNKNOWN
    assert q.slots.score is None
    assert q.slots.provinces == []
