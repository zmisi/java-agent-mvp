"""Load and apply ontology YAML (regions, exclusions, preferences)."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

from admission_compiler.ir import Filters, Preference, PreferenceDimension, RegionRef


@dataclass
class Ontology:
    regions: dict[str, list[str]] = field(default_factory=dict)
    exclusions: dict[str, dict[str, list[str]]] = field(default_factory=dict)
    preference_phrases: dict[str, tuple[PreferenceDimension, list[str]]] = field(default_factory=dict)


def default_ontology_dir() -> Path:
    return Path(__file__).resolve().parents[2] / "ontology"


def load_ontology(ontology_dir: Path | None = None) -> Ontology:
    base = ontology_dir or default_ontology_dir()
    regions = _load_regions(base / "regions.yaml")
    exclusions = _load_exclusions(base / "exclusions.yaml")
    preference_phrases = _load_preferences(base / "preferences.yaml")
    return Ontology(
        regions=regions,
        exclusions=exclusions,
        preference_phrases=preference_phrases,
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
