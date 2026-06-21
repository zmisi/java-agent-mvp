"""Load and apply ontology YAML (regions, exclusions, preferences)."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

from admission_compiler.ir import Filters, Preference, PreferenceDimension, RegionRef, UnsupportedConstraint


@dataclass
class Ontology:
    regions: dict[str, list[str]] = field(default_factory=dict)
    exclusions: dict[str, dict[str, list[str]]] = field(default_factory=dict)
    preference_phrases: dict[str, tuple[PreferenceDimension, list[str]]] = field(default_factory=dict)
    unsupported_signals: dict[str, tuple[str, str, list[str]]] = field(default_factory=dict)
    major_category_filters: dict[str, tuple[list[str], list[str], list[str]]] = field(default_factory=dict)


def default_ontology_dir() -> Path:
    repo_root = Path(__file__).resolve().parents[3]
    shared = repo_root / "src" / "main" / "resources" / "admission-ontology"
    if shared.is_dir():
        return shared
    return Path(__file__).resolve().parents[2] / "ontology"


def load_ontology(ontology_dir: Path | None = None) -> Ontology:
    base = ontology_dir or default_ontology_dir()
    regions = _load_regions(base / "regions.yaml")
    exclusions = _load_exclusions(base / "exclusions.yaml")
    preference_phrases = _load_preferences(base / "preferences.yaml")
    unsupported_signals = _load_unsupported_signals(base / "unsupported_signals.yaml")
    major_category_filters = _load_major_category_filters(base / "major_category_filters.yaml")
    return Ontology(
        regions=regions,
        exclusions=exclusions,
        preference_phrases=preference_phrases,
        unsupported_signals=unsupported_signals,
        major_category_filters=major_category_filters,
    )


def _load_regions(path: Path) -> dict[str, list[str]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out: dict[str, list[str]] = {}
    for phrase, body in (data.get("regions") or {}).items():
        provinces = body.get("provinces") if isinstance(body, dict) else None
        if provinces:
            out[str(phrase)] = [str(p) for p in provinces]
    return out


def _load_exclusions(path: Path) -> dict[str, dict[str, list[str]]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out: dict[str, dict[str, list[str]]] = {}
    for phrase, body in (data.get("exclusions") or {}).items():
        if isinstance(body, dict):
            out[str(phrase)] = {
                k: [str(v) for v in vals]
                for k, vals in body.items()
                if isinstance(vals, list)
            }
    return out


def _load_preferences(path: Path) -> dict[str, tuple[PreferenceDimension, list[str]]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out: dict[str, tuple[PreferenceDimension, list[str]]] = {}
    for phrase, body in (data.get("preferences") or {}).items():
        if not isinstance(body, dict):
            continue
        dim_raw = body.get("dimension")
        if not dim_raw:
            continue
        dimension = PreferenceDimension(str(dim_raw))
        aliases = [str(a) for a in (body.get("aliases") or [])]
        out[str(phrase)] = (dimension, aliases)
    return out


def _sorted_phrases(phrases: list[str]) -> list[str]:
    return sorted(phrases, key=len, reverse=True)


def match_regions(message: str, ontology: Ontology) -> list[RegionRef]:
    matched: list[RegionRef] = []
    seen_phrases: set[str] = set()
    for phrase in _sorted_phrases(list(ontology.regions.keys())):
        if phrase in message and phrase not in seen_phrases:
            matched.append(RegionRef(phrase=phrase, provinces=list(ontology.regions[phrase])))
            seen_phrases.add(phrase)
    return matched


def apply_exclusions(message: str, ontology: Ontology) -> tuple[Filters, list[str]]:
    filters = Filters()
    hits: list[str] = []
    for phrase in _sorted_phrases(list(ontology.exclusions.keys())):
        if phrase not in message:
            continue
        hits.append(phrase)
        body = ontology.exclusions[phrase]
        for school in body.get("exclude_school_name_contains", []):
            if school not in filters.exclude_school_name_contains:
                filters.exclude_school_name_contains.append(school)
        for major in body.get("exclude_major_keywords", []):
            if major not in filters.exclude_major_keywords:
                filters.exclude_major_keywords.append(major)
    return filters, hits


def match_preferences(message: str, ontology: Ontology) -> tuple[list[Preference], list[str]]:
    preferences: list[Preference] = []
    hits: list[str] = []
    seen_dims: set[PreferenceDimension] = set()

    candidates: list[tuple[str, PreferenceDimension]] = []
    for phrase, (dimension, aliases) in ontology.preference_phrases.items():
        candidates.append((phrase, dimension))
        for alias in aliases:
            candidates.append((alias, dimension))

    for phrase, dimension in sorted(candidates, key=lambda x: len(x[0]), reverse=True):
        if phrase not in message or dimension in seen_dims:
            continue
        seen_dims.add(dimension)
        hits.append(phrase)
        preferences.append(Preference(dimension=dimension, weight=1.0, raw_phrase=phrase))

    if len(preferences) > 1:
        weight = round(1.0 / len(preferences), 3)
        for pref in preferences:
            pref.weight = weight

    return preferences, hits


def _load_unsupported_signals(path: Path) -> dict[str, tuple[str, str, list[str]]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out: dict[str, tuple[str, str, list[str]]] = {}
    for phrase, body in (data.get("unsupported_signals") or {}).items():
        if not isinstance(body, dict):
            continue
        constraint_type = str(body.get("constraint_type") or "unknown")
        reason = str(body.get("reason") or "no_data")
        aliases = [str(a) for a in (body.get("aliases") or [])]
        out[str(phrase)] = (constraint_type, reason, aliases)
    return out


def match_unsupported_constraints(message: str, ontology: Ontology) -> list[UnsupportedConstraint]:
    matched: list[UnsupportedConstraint] = []
    seen_types: set[str] = set()
    candidates: list[tuple[str, str, str]] = []
    for phrase, (constraint_type, reason, aliases) in ontology.unsupported_signals.items():
        candidates.append((phrase, constraint_type, reason))
        for alias in aliases:
            candidates.append((alias, constraint_type, reason))

    for phrase, constraint_type, reason in sorted(candidates, key=lambda x: len(x[0]), reverse=True):
        if phrase not in message or constraint_type in seen_types:
            continue
        seen_types.add(constraint_type)
        matched.append(
            UnsupportedConstraint(raw_phrase=phrase, constraint_type=constraint_type, reason=reason)
        )
    return matched


def match_major_category_filters(message: str, ontology: Ontology) -> tuple[list[str], list[str], list[str]]:
    groups: list[str] = []
    categories: list[str] = []
    hits: list[str] = []
    seen_keys: set[str] = set()
    candidates: list[tuple[str, str, list[str], list[str]]] = []
    for key, (discipline_groups, discipline_categories, aliases) in ontology.major_category_filters.items():
        candidates.append((key, key, discipline_groups, discipline_categories))
        for alias in aliases:
            candidates.append((alias, key, discipline_groups, discipline_categories))

    for phrase, key, discipline_groups, discipline_categories in sorted(
        candidates, key=lambda x: len(x[0]), reverse=True
    ):
        if phrase not in message or key in seen_keys:
            continue
        seen_keys.add(key)
        hits.append(phrase)
        for group in discipline_groups:
            if group not in groups:
                groups.append(group)
        for category in discipline_categories:
            if category not in categories:
                categories.append(category)
    return groups, categories, hits


def _load_major_category_filters(path: Path) -> dict[str, tuple[list[str], list[str], list[str]]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    out: dict[str, tuple[list[str], list[str], list[str]]] = {}
    for phrase, body in (data.get("major_category_filters") or {}).items():
        if not isinstance(body, dict):
            continue
        groups = [str(g) for g in (body.get("discipline_groups") or [])]
        categories = [str(c) for c in (body.get("discipline_categories") or [])]
        aliases = [str(a) for a in (body.get("aliases") or [])]
        out[str(phrase)] = (groups, categories, aliases)
    return out
