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


def test_yangtze_complex_preferences():
    compiler = AdmissionQueryCompiler()
    message = "我要报考长三角的大学，不当老师，就业前景比较好，收入比较高，能进央国企"
    result = compiler.compile(CompileRequest(message=message, use_llm=False))
    q = result.query
    assert q.task == Task.SEARCH_MAJORS
    dims = {p.dimension.value for p in q.preferences}
    assert dims == {"employment_outlook", "salary", "state_owned_employability"}
    assert abs(sum(p.weight for p in q.preferences) - 1.0) < 0.01


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
    assert "浙江" in q.slots.provinces
    assert "安徽" in q.slots.provinces
