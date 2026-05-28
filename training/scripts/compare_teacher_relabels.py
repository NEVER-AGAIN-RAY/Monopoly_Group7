#!/usr/bin/env python3
"""Compare relabeled decisions against the original trace decisions."""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
from typing import Any


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--original", type=Path, action="append", required=True)
    parser.add_argument("--relabeled", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if len(args.original) != len(args.relabeled):
        raise SystemExit("--original and --relabeled counts must match")

    sections = []
    combined = collections.Counter()
    for original_path, relabeled_path in zip(args.original, args.relabeled):
        section = compare_pair(original_path, relabeled_path)
        sections.append(section)
        for key, value in section["choiceBuckets"].items():
            combined[key] += value

    report = {
        "schema": "monopoly-deal-teacher-relabel-comparison-v1",
        "sections": sections,
        "combinedChoiceBuckets": dict(sorted(combined.items())),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


def compare_pair(original_path: Path, relabeled_path: Path) -> dict[str, Any]:
    originals = load_originals(original_path)
    buckets: collections.Counter[str] = collections.Counter()
    teacher_kinds: collections.Counter[str] = collections.Counter()
    transitions: collections.Counter[tuple[str, str, str]] = collections.Counter()
    missing_original = 0
    rows = 0
    for row in read_jsonl(relabeled_path):
        rows += 1
        request = obj(row.get("request"))
        result = obj(row.get("result"))
        original = originals.get(str(request.get("decisionId", "")))
        if original is None:
            missing_original += 1
            continue
        teacher_choice = str(result.get("choiceId", ""))
        lookahead_choice = original["lookaheadChoice"]
        hard_choice = original["hardChoice"]
        if teacher_choice == lookahead_choice:
            bucket = "same_as_lookahead"
        elif teacher_choice == hard_choice:
            bucket = "same_as_hard"
        else:
            bucket = "other"
        buckets[bucket] += 1
        lookahead_kind = action_kind(original["candidates"].get(lookahead_choice, ""))
        hard_kind = action_kind(original["candidates"].get(hard_choice, ""))
        teacher_kind = action_kind(original["candidates"].get(teacher_choice, ""))
        teacher_kinds[teacher_kind] += 1
        transitions[(lookahead_kind, hard_kind, teacher_kind)] += 1

    total = sum(buckets.values())
    return {
        "originalPath": str(original_path),
        "relabeledPath": str(relabeled_path),
        "rows": rows,
        "matchedRows": total,
        "missingOriginal": missing_original,
        "choiceBuckets": dict(sorted(buckets.items())),
        "choiceRates": {
            key: round(value / total, 4) if total else 0.0
            for key, value in sorted(buckets.items())
        },
        "teacherChosenKinds": dict(teacher_kinds.most_common(30)),
        "topTransitions": [
            {
                "count": count,
                "lookahead": lookahead,
                "hard": hard,
                "teacher": teacher,
            }
            for (lookahead, hard, teacher), count in transitions.most_common(30)
        ],
    }


def load_originals(path: Path) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    for row in read_jsonl(path):
        request = obj(row.get("request"))
        result = obj(row.get("result"))
        metadata = obj(result.get("metadata"))
        candidates = {
            str(candidate.get("id", "")): str(candidate.get("summary", ""))
            for candidate in list_obj(request.get("candidates"))
            if isinstance(candidate, dict)
        }
        out[str(request.get("decisionId", ""))] = {
            "lookaheadChoice": str(result.get("choiceId", "")),
            "hardChoice": str(metadata.get("hardChoiceId", "")),
            "candidates": candidates,
        }
    return out


def action_kind(summary: str) -> str:
    if not summary:
        return "unknown"
    if summary.startswith("Action "):
        parts = summary.split()
        return "Action " + (parts[1].rstrip(".") if len(parts) > 1 else "unknown")
    if summary.startswith("Deploy wild"):
        return "Deploy wild"
    if summary.startswith("Deploy property"):
        return "Deploy property"
    if summary.startswith("Deposit"):
        return "Deposit"
    if summary.startswith("Discard"):
        return "Discard"
    return summary.split()[0]


def read_jsonl(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            raw = line.strip()
            if not raw:
                continue
            try:
                yield json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_obj(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


if __name__ == "__main__":
    raise SystemExit(main())
