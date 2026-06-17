"""Admission query intermediate representation (IR)."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class Task(str, Enum):
    SEARCH_MAJORS = "search_majors"
    SEARCH_RANK = "search_rank"
    POLICY_QA = "policy_qa"
    REPORT = "report"
    UNKNOWN = "unknown"


class PreferenceDimension(str, Enum):
    EMPLOYMENT_OUTLOOK = "employment_outlook"
    SALARY = "salary"
    STATE_OWNED_EMPLOYABILITY = "state_owned_employability"


class Preference(BaseModel):
    dimension: PreferenceDimension
    weight: float = Field(default=1.0, ge=0.0, le=1.0)
    raw_phrase: str | None = None


class Filters(BaseModel):
    exclude_school_name_contains: list[str] = Field(default_factory=list)
    exclude_major_keywords: list[str] = Field(default_factory=list)
    include_major_keywords: list[str] = Field(default_factory=list)
    include_schools: list[str] = Field(default_factory=list)


class Slots(BaseModel):
    score: int | None = Field(default=None, ge=200, le=750)
    provinces: list[str] = Field(default_factory=list)
    subject_group: str | None = None
    year: int | None = Field(default=None, ge=2020, le=2030)
    admission_type: str | None = None


class RegionRef(BaseModel):
    phrase: str
    provinces: list[str]


class ParseTrace(BaseModel):
    rules_applied: list[str] = Field(default_factory=list)
    ontology_hits: list[str] = Field(default_factory=list)
    llm_used: bool = False


class AdmissionQuery(BaseModel):
    """Structured compile result for one user turn."""

    task: Task
    slots: Slots = Field(default_factory=Slots)
    filters: Filters = Field(default_factory=Filters)
    preferences: list[Preference] = Field(default_factory=list)
    regions: list[RegionRef] = Field(default_factory=list)
    needs_clarification: list[str] = Field(default_factory=list)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    raw_message: str = ""
    parse_trace: ParseTrace = Field(default_factory=ParseTrace)

    def model_dump_json_compatible(self) -> dict[str, Any]:
        return self.model_dump(mode="json")


class CompileRequest(BaseModel):
    message: str
    prior_slots: Slots | None = None
    prior_user_messages: list[str] = Field(default_factory=list)
    use_llm: bool | None = None


class CompileResponse(BaseModel):
    query: AdmissionQuery
    schema_version: str = "1.0"
