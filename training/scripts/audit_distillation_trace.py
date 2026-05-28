#!/usr/bin/env python3
"""Audit Monopoly Deal distillation traces before spending more on training."""

from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Sequence


REQUIRED_KINDS = ["PLAY_CARD", "JUST_SAY_NO", "PAYMENT", "OVERFLOW_DISCARD"]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--preferred-source", default="deepseek")
    parser.add_argument("--min-rows", type=int, default=5000)
    parser.add_argument("--min-source-ratio", type=float, default=0.95)
    parser.add_argument("--max-first-choice-ratio", type=float, default=0.75)
    parser.add_argument("--min-rare-kind-rows", type=int, default=100)
    parser.add_argument("--require-token-usage", action="store_true")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args(argv)

    rows = load_rows(args.jsonl)
    audit = build_audit(
        rows,
        preferred_source=args.preferred_source,
        min_rows=args.min_rows,
        min_source_ratio=args.min_source_ratio,
        max_first_choice_ratio=args.max_first_choice_ratio,
        min_rare_kind_rows=args.min_rare_kind_rows,
        require_token_usage=args.require_token_usage,
    )
    text = json.dumps(audit, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if audit["ok"] else 1


def load_rows(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                rows.append(json.loads(text))
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
    return rows


def build_audit(
    rows: Sequence[Dict[str, Any]],
    preferred_source: str,
    min_rows: int,
    min_source_ratio: float,
    max_first_choice_ratio: float,
    min_rare_kind_rows: int,
    require_token_usage: bool,
) -> Dict[str, Any]:
    by_source = collections.Counter(source(row) for row in rows)
    by_kind = collections.Counter(decision_kind(row) for row in rows)
    by_player_count = collections.Counter(str(player_count(row)) for row in rows)
    candidate_counts = [len(candidates(row)) for row in rows]
    chosen_indices = [chosen_index(row) for row in rows]
    rows_with_usage = sum(1 for row in rows if has_token_usage(row))
    source_ratio = ratio(by_source.get(preferred_source, 0), len(rows))
    first_choice_ratio = ratio(sum(1 for idx in chosen_indices if idx == 0), len(chosen_indices))
    invalid_choice_rows = sum(1 for idx in chosen_indices if idx < 0)
    duplicate_decisions = duplicate_decision_count(rows)
    checks = [
        check("min_rows", len(rows) >= min_rows, f"{len(rows)} / {min_rows}"),
        check("preferred_source_ratio", source_ratio >= min_source_ratio, f"{source_ratio:.1%} / {min_source_ratio:.1%}"),
        check("all_decision_kinds", all(by_kind.get(kind, 0) > 0 for kind in REQUIRED_KINDS), str(dict(sorted(by_kind.items())))),
        check(
            "rare_kind_rows",
            all(by_kind.get(kind, 0) >= min_rare_kind_rows for kind in REQUIRED_KINDS if kind != "PLAY_CARD"),
            f"min rare rows {min_rare_kind_rows}; counts={dict(sorted(by_kind.items()))}",
        ),
        check("valid_choice_ids", invalid_choice_rows == 0, f"invalid rows={invalid_choice_rows}"),
        check("duplicate_decision_ids", duplicate_decisions == 0, f"duplicates={duplicate_decisions}"),
        check("first_choice_bias", first_choice_ratio <= max_first_choice_ratio, f"{first_choice_ratio:.1%} / {max_first_choice_ratio:.1%}"),
    ]
    if require_token_usage:
        checks.append(check(
            "token_usage_present",
            rows_with_usage == len(rows) and len(rows) > 0,
            f"rowsWithUsage={rows_with_usage} / {len(rows)}",
        ))
    else:
        checks.append(check(
            "token_usage_observed",
            rows_with_usage > 0,
            f"rowsWithUsage={rows_with_usage} / {len(rows)}",
            severity="warn",
        ))
    warnings = [item for item in checks if item["severity"] == "warn" and not item["ok"]]
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    return {
        "schema": "monopoly-deal-trace-audit-v1",
        "rows": len(rows),
        "ok": not failures,
        "warningCount": len(warnings),
        "failureCount": len(failures),
        "checks": checks,
        "byTeacherSource": dict(sorted(by_source.items())),
        "preferredSource": preferred_source,
        "preferredSourceRatio": source_ratio,
        "byDecisionKind": dict(sorted(by_kind.items())),
        "byPlayerCount": dict(sorted(by_player_count.items())),
        "candidateCounts": {
            "min": min(candidate_counts) if candidate_counts else 0,
            "p50": percentile(candidate_counts, 50),
            "p90": percentile(candidate_counts, 90),
            "max": max(candidate_counts) if candidate_counts else 0,
            "avg": sum(candidate_counts) / len(candidate_counts) if candidate_counts else 0.0,
        },
        "chosenIndex": dict(sorted(collections.Counter(str(idx) for idx in chosen_indices).items())),
        "firstChoiceRatio": first_choice_ratio,
        "rowsWithTokenUsage": rows_with_usage,
    }


def check(name: str, ok: bool, detail: str, severity: str = "error") -> Dict[str, Any]:
    return {"name": name, "ok": bool(ok), "severity": severity, "detail": detail}


def candidates(row: Dict[str, Any]) -> List[Any]:
    value = obj(row.get("request")).get("candidates")
    return value if isinstance(value, list) else []


def chosen_index(row: Dict[str, Any]) -> int:
    choice = obj(row.get("result")).get("choiceId")
    for index, candidate in enumerate(candidates(row)):
        if isinstance(candidate, dict) and candidate.get("id") == choice:
            return index
    return -1


def duplicate_decision_count(rows: Sequence[Dict[str, Any]]) -> int:
    ids = [str(obj(row.get("request")).get("decisionId", "")) for row in rows]
    counts = collections.Counter(item for item in ids if item)
    return sum(count - 1 for count in counts.values() if count > 1)


def decision_kind(row: Dict[str, Any]) -> str:
    return str(obj(row.get("request")).get("decisionKind", "unknown"))


def player_count(row: Dict[str, Any]) -> int:
    meta = obj(obj(obj(row.get("request")).get("context")).get("gameMeta"))
    try:
        return int(float(meta.get("playerCount", 0)))
    except (TypeError, ValueError):
        return 0


def source(row: Dict[str, Any]) -> str:
    return str(obj(obj(row.get("result")).get("metadata")).get("source", "unknown"))


def has_token_usage(row: Dict[str, Any]) -> bool:
    metadata = obj(obj(row.get("result")).get("metadata"))
    return any(key in metadata for key in ["promptTokensShare", "completionTokensShare", "totalTokensShare"])


def percentile(values: List[int], pct: int) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct / 100))
    return ordered[index]


def ratio(n: int, d: int) -> float:
    return 0.0 if d <= 0 else n / d


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
